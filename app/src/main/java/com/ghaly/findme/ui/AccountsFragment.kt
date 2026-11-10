package com.ghaly.findme.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ghaly.findme.R
import com.ghaly.findme.export.LedgerExport
import com.ghaly.findme.model.LedgerAccount
import com.ghaly.findme.model.LedgerBook
import com.ghaly.findme.model.LedgerCurrency
import com.ghaly.findme.repo.LedgerRepository
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** The accounts/debts ledger — who owes the device's owner, and who they
 *  owe, per contact. Purely local bookkeeping, reached from Tools, not a
 *  bottom tab: unlike everything else in the app it never touches the mesh
 *  at all. Tapping an account opens [AccountDetailFragment] for its
 *  transaction history. */
class AccountsFragment : Fragment(R.layout.fragment_accounts) {

    private val adapter = AccountAdapter(
        onClick = { account -> openAccount(account) },
        onLongClick = { account -> showAccountActionMenu(requireContext(), account) { showDeleteAccountConfirm(account) } }
    )

    // Held only while the "add account" dialog is open, same pattern as the
    // profile/listing photo pickers elsewhere in the app.
    private var pendingPhotoBase64: String? = null
    private var pendingPhotoPreview: ImageView? = null
    private var pendingDateMillis: Long = System.currentTimeMillis()

    private val pickPhotoLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) handlePickedPhoto(uri)
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        LedgerRepository.init(requireContext())

        val list = view.findViewById<RecyclerView>(R.id.accountsList)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter
        val emptyText = view.findViewById<TextView>(R.id.accountsEmptyText)
        val totalTheyOwe = view.findViewById<TextView>(R.id.accountsTotalTheyOwe)
        val totalIOwe = view.findViewById<TextView>(R.id.accountsTotalIOwe)
        val searchInput = view.findViewById<EditText>(R.id.accountsSearchInput)
        val bookSwitcher = view.findViewById<TextView>(R.id.accountsBookSwitcher)

        view.findViewById<TextView>(R.id.addAccountButton).setOnClickListener { showAddAccountDialog() }
        view.findViewById<TextView>(R.id.accountsExportButton).setOnClickListener { showExportMenu() }
        bookSwitcher.setOnClickListener { showBookPickerDialog() }
        bookSwitcher.setOnLongClickListener { showCurrentBookActionMenu(); true }

        var allAccounts: List<LedgerAccount> = emptyList()
        var currentBookId = ""
        fun applyFilter() {
            val query = searchInput.text?.toString()?.trim().orEmpty()
            val inBook = allAccounts.filter { it.bookId == currentBookId }
            val filtered = if (query.isEmpty()) inBook else inBook.filter {
                it.name.contains(query, ignoreCase = true) || it.phone?.contains(query, ignoreCase = true) == true
            }
            adapter.items = filtered
            emptyText.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        }
        searchInput.addTextChangedListener(simpleTextWatcher { applyFilter() })

        // Books, accounts and the active book id can each change independently
        // (adding a book, renaming it, recording a deal) — combine so every
        // one of them re-renders the switcher label, the filtered list and
        // the book-scoped totals together, rather than three separate
        // collectors that could show a stale label next to a fresh total.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    LedgerRepository.accounts,
                    LedgerRepository.books,
                    LedgerRepository.currentBookId
                ) { accounts, books, bookId -> Triple(accounts, books, bookId) }
                    .collect { (accounts, books, bookId) ->
                        allAccounts = accounts
                        currentBookId = bookId
                        applyFilter()
                        bookSwitcher.text = getString(
                            R.string.accounts_book_switcher_fmt,
                            books.find { it.id == bookId }?.name.orEmpty()
                        )
                        bindTotal(totalTheyOwe, LedgerCurrency.LOCAL, positive = true, bookId = bookId)
                        bindTotal(totalIOwe, LedgerCurrency.LOCAL, positive = false, bookId = bookId)
                    }
            }
        }
    }

    private fun bindTotal(view: TextView, currency: LedgerCurrency, positive: Boolean, bookId: String) {
        val total = LedgerRepository.totalBalance(currency, bookId)
        val amount = if (positive) total.coerceAtLeast(0.0) else (-total).coerceAtLeast(0.0)
        val label = currencyLabel(requireContext(), currency)
        view.text = getString(
            if (positive) R.string.accounts_total_they_owe_fmt else R.string.accounts_total_i_owe_fmt,
            formatLedgerAmount(amount),
            label
        )
    }

    /** Switch to another existing book, or create a new one — the book
     *  itself is renamed/deleted from [showCurrentBookActionMenu] instead,
     *  reached by long-pressing the switcher rather than crowding this
     *  list with per-row actions. */
    private fun showBookPickerDialog() {
        val books = LedgerRepository.books.value
        val labels = books.map { it.name }.toTypedArray() + getString(R.string.book_add_new)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.book_picker_title)
            .setItems(labels) { _, which ->
                if (which == books.size) {
                    showAddBookDialog()
                } else {
                    LedgerRepository.setCurrentBook(books[which].id)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAddBookDialog() {
        val input = EditText(requireContext())
        input.hint = getString(R.string.book_name_hint)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.book_add_new)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isNotEmpty()) LedgerRepository.addBook(name)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Rename or delete the book currently in view. Deleting the last
     *  remaining book would leave nowhere for accounts to live, so that
     *  option is refused rather than offered. */
    private fun showCurrentBookActionMenu() {
        val book = LedgerRepository.book(LedgerRepository.currentBookId.value) ?: return
        val options = arrayOf(getString(R.string.book_rename), getString(R.string.book_delete))
        AlertDialog.Builder(requireContext())
            .setTitle(book.name)
            .setItems(options) { _, which ->
                if (which == 0) {
                    showRenameBookDialog(book)
                } else {
                    if (LedgerRepository.books.value.size <= 1) {
                        Toast.makeText(requireContext(), R.string.book_delete_last_blocked, Toast.LENGTH_SHORT).show()
                    } else {
                        showDeleteBookConfirm(book)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showRenameBookDialog(book: LedgerBook) {
        val input = EditText(requireContext())
        input.setText(book.name)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.book_rename)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isNotEmpty()) LedgerRepository.renameBook(book.id, name)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showDeleteBookConfirm(book: LedgerBook) {
        AlertDialog.Builder(requireContext())
            .setTitle(book.name)
            .setMessage(R.string.book_delete_confirm)
            .setPositiveButton(R.string.notes_delete_confirm) { _, _ -> LedgerRepository.removeBook(book.id) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openAccount(account: LedgerAccount) {
        requireActivity().supportFragmentManager.beginTransaction()
            .setCustomAnimations(
                R.anim.slide_in_right, R.anim.slide_out_left,
                R.anim.slide_in_right, R.anim.slide_out_left
            )
            .replace(R.id.contentContainer, AccountDetailFragment.newInstance(account.id))
            .addToBackStack(null)
            .commit()
    }

    /** Both options cover every account in the current book, not just what's
     *  currently filtered by the search box — a statement meant to be shared
     *  should hold the whole book's record, not whatever happened to be on
     *  screen, and not another book's unrelated deals. */
    private fun showExportMenu() {
        val accounts = LedgerRepository.accountsInBook(LedgerRepository.currentBookId.value)
        if (accounts.isEmpty()) {
            Toast.makeText(requireContext(), R.string.accounts_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val options = arrayOf(getString(R.string.account_export_pdf), getString(R.string.account_export_excel))
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.account_export_menu_title)
            .setItems(options) { _, which ->
                try {
                    if (which == 0) {
                        val file = LedgerExport.exportPdf(requireContext(), accounts, getString(R.string.app_name))
                        LedgerExport.shareFile(requireContext(), file, "application/pdf")
                    } else {
                        val file = LedgerExport.exportCsv(requireContext(), accounts)
                        LedgerExport.shareFile(requireContext(), file, "text/csv")
                    }
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), R.string.account_export_failed, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showDeleteAccountConfirm(account: LedgerAccount) {
        AlertDialog.Builder(requireContext())
            .setTitle(account.name)
            .setMessage(R.string.account_delete_confirm)
            .setPositiveButton(R.string.notes_delete_confirm) { _, _ -> LedgerRepository.removeAccount(account.id) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** One combined dialog: a new account plus its opening transaction —
     *  same shape as the reference app this was modeled on, where adding a
     *  customer and recording what they owe is a single step. */
    private fun showAddAccountDialog() {
        pendingPhotoBase64 = null
        pendingDateMillis = System.currentTimeMillis()

        val view = layoutInflater.inflate(R.layout.dialog_add_account, null)
        val nameInput = view.findViewById<EditText>(R.id.accountNameInput)
        val phoneInput = view.findViewById<EditText>(R.id.accountPhoneInput)
        val fields = LedgerEntryFieldViews(view)
        pendingPhotoPreview = fields.photoPreview
        wireLedgerEntryFields(
            requireContext(),
            fields,
            getDateMillis = { pendingDateMillis },
            setDateMillis = { pendingDateMillis = it },
            onPickPhotoClicked = { pickPhotoLauncher.launch("image/*") }
        )

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.account_add_title)
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = nameInput.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.account_name_required, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val amount = fields.amountInput.text?.toString()?.trim()?.toDoubleOrNull()
                val account = LedgerRepository.addAccount(name, phoneInput.text?.toString()?.trim())
                if (amount != null && amount > 0) {
                    LedgerRepository.addEntry(
                        accountId = account.id,
                        amount = amount,
                        currency = currentLedgerCurrency(requireContext(), fields),
                        direction = currentLedgerDirection(fields),
                        details = fields.detailsInput.text?.toString()?.trim().orEmpty(),
                        photoBase64 = pendingPhotoBase64,
                        ts = pendingDateMillis
                    )
                }
                pendingPhotoPreview = null
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> pendingPhotoPreview = null }
            .show()
    }

    private fun handlePickedPhoto(uri: Uri) {
        try {
            val input = requireContext().contentResolver.openInputStream(uri) ?: return
            val original = BitmapFactory.decodeStream(input)
            input.close()
            val maxDim = 320
            val scale = maxDim.toFloat() / maxOf(original.width, original.height)
            val resized = if (scale < 1f) {
                Bitmap.createScaledBitmap(original, (original.width * scale).toInt().coerceAtLeast(1), (original.height * scale).toInt().coerceAtLeast(1), true)
            } else original
            val out = ByteArrayOutputStream()
            resized.compress(Bitmap.CompressFormat.JPEG, 60, out)
            pendingPhotoBase64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            pendingPhotoPreview?.let {
                it.setImageBitmap(resized)
                it.visibility = View.VISIBLE
            }
        } catch (e: Exception) {
            Toast.makeText(requireContext(), R.string.photo_load_failed, Toast.LENGTH_SHORT).show()
        }
    }
}

/** Small helper so a search box can filter-as-you-type without pulling in a
 *  full TextWatcher implementation at every call site. */
internal fun simpleTextWatcher(onChanged: () -> Unit): android.text.TextWatcher =
    object : android.text.TextWatcher {
        override fun afterTextChanged(s: android.text.Editable?) = onChanged()
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    }
