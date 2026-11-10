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
import com.ghaly.findme.model.LedgerAccount
import com.ghaly.findme.model.LedgerCurrency
import com.ghaly.findme.model.LedgerEntry
import com.ghaly.findme.repo.LedgerRepository
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.launch

/** One account's transaction history — every entry recorded against this
 *  contact, with the running balance per currency, and a button to record
 *  another transaction. Purely local, opened from [AccountsFragment]. */
class AccountDetailFragment : Fragment(R.layout.fragment_account_detail) {

    private val accountId: String by lazy { requireArguments().getString(ARG_ACCOUNT_ID)!! }
    private val adapter = LedgerEntryAdapter(onLongClick = { entry -> showDeleteEntryConfirm(entry) })

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

        val nameView = view.findViewById<TextView>(R.id.accountDetailName)
        val phoneView = view.findViewById<TextView>(R.id.accountDetailPhone)
        val balanceLocal = view.findViewById<TextView>(R.id.accountDetailBalanceLocal)
        val balanceUsd = view.findViewById<TextView>(R.id.accountDetailBalanceUsd)
        val emptyText = view.findViewById<TextView>(R.id.accountDetailEmptyText)
        val list = view.findViewById<RecyclerView>(R.id.accountDetailEntries)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter

        nameView.setOnLongClickListener { showDeleteAccountConfirm(); true }
        view.findViewById<TextView>(R.id.addEntryButton).setOnClickListener { showAddEntryDialog() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                LedgerRepository.accounts.collect { accounts ->
                    val account = accounts.find { it.id == accountId }
                    if (account == null) {
                        // Deleted (e.g. from the list's long-press) while this screen was open.
                        requireActivity().supportFragmentManager.popBackStack()
                        return@collect
                    }
                    bindAccount(account, nameView, phoneView, balanceLocal, balanceUsd, emptyText)
                }
            }
        }
    }

    private fun bindAccount(
        account: LedgerAccount,
        nameView: TextView,
        phoneView: TextView,
        balanceLocal: TextView,
        balanceUsd: TextView,
        emptyText: TextView
    ) {
        nameView.text = account.name
        if (account.phone.isNullOrBlank()) {
            phoneView.visibility = View.GONE
        } else {
            phoneView.visibility = View.VISIBLE
            phoneView.text = account.phone
        }
        bindBalanceLine(balanceLocal, account, LedgerCurrency.LOCAL, alwaysShow = true)
        val hasUsd = account.entries.any { it.currency == LedgerCurrency.USD }
        if (hasUsd) {
            balanceUsd.visibility = View.VISIBLE
            bindBalanceLine(balanceUsd, account, LedgerCurrency.USD, alwaysShow = true)
        } else {
            balanceUsd.visibility = View.GONE
        }
        adapter.items = account.entries
        emptyText.visibility = if (account.entries.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun bindBalanceLine(view: TextView, account: LedgerAccount, currency: LedgerCurrency, alwaysShow: Boolean) {
        val balance = account.balance(currency)
        val label = currencyLabel(requireContext(), currency)
        val amountText = formatLedgerAmount(kotlin.math.abs(balance))
        when {
            balance > 0 -> {
                view.text = getString(R.string.account_detail_balance_they_owe_fmt, amountText, label)
                view.setTextColor(requireContext().getColor(R.color.teal_400))
            }
            balance < 0 -> {
                view.text = getString(R.string.account_detail_balance_i_owe_fmt, amountText, label)
                view.setTextColor(requireContext().getColor(R.color.sos_red))
            }
            else -> {
                view.text = getString(R.string.ledger_settled_fmt, label)
                view.setTextColor(requireContext().getColor(R.color.sand_300))
            }
        }
    }

    private fun showDeleteAccountConfirm() {
        val account = LedgerRepository.account(accountId) ?: return
        AlertDialog.Builder(requireContext())
            .setTitle(account.name)
            .setMessage(R.string.account_delete_confirm)
            .setPositiveButton(R.string.notes_delete_confirm) { _, _ ->
                LedgerRepository.removeAccount(accountId)
                requireActivity().supportFragmentManager.popBackStack()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showDeleteEntryConfirm(entry: LedgerEntry) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.account_delete_entry_title)
            .setPositiveButton(R.string.notes_delete_confirm) { _, _ -> LedgerRepository.removeEntry(accountId, entry.id) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAddEntryDialog() {
        pendingPhotoBase64 = null
        pendingDateMillis = System.currentTimeMillis()

        val view = layoutInflater.inflate(R.layout.dialog_add_ledger_entry, null)
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
            .setTitle(R.string.account_add_entry_title)
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                val amount = fields.amountInput.text?.toString()?.trim()?.toDoubleOrNull()
                if (amount == null || amount <= 0) {
                    Toast.makeText(requireContext(), R.string.account_amount_required, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                LedgerRepository.addEntry(
                    accountId = accountId,
                    amount = amount,
                    currency = currentLedgerCurrency(requireContext(), fields),
                    direction = currentLedgerDirection(fields),
                    details = fields.detailsInput.text?.toString()?.trim().orEmpty(),
                    photoBase64 = pendingPhotoBase64,
                    ts = pendingDateMillis
                )
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

    companion object {
        private const val ARG_ACCOUNT_ID = "accountId"

        fun newInstance(accountId: String): AccountDetailFragment = AccountDetailFragment().apply {
            arguments = Bundle().apply { putString(ARG_ACCOUNT_ID, accountId) }
        }
    }
}
