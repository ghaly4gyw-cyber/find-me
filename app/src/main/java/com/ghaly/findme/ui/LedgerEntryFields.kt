package com.ghaly.findme.ui

import android.app.DatePickerDialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.ghaly.findme.R
import com.ghaly.findme.model.LedgerAccount
import com.ghaly.findme.model.LedgerCurrency
import com.ghaly.findme.model.LedgerDirection
import com.ghaly.findme.repo.LedgerRepository
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** The amount/currency/direction/date/details/photo fields shared by the
 *  "add account" (which bundles an opening entry) and "add entry" dialogs —
 *  both inflate partial_ledger_entry_fields.xml, so both bind to this same
 *  set of view ids. */
internal class LedgerEntryFieldViews(root: View) {
    val amountInput: EditText = root.findViewById(R.id.entryAmountInput)
    val currencyToggle: TextView = root.findViewById(R.id.entryCurrencyToggle)
    val directionGroup: RadioGroup = root.findViewById(R.id.entryDirectionGroup)
    val dateInput: TextView = root.findViewById(R.id.entryDateInput)
    val detailsInput: EditText = root.findViewById(R.id.entryDetailsInput)
    val photoPreview: ImageView = root.findViewById(R.id.entryPhotoPreview)
    val choosePhotoText: TextView = root.findViewById(R.id.entryChoosePhotoText)
}

internal fun currentLedgerCurrency(context: Context, fields: LedgerEntryFieldViews): LedgerCurrency =
    if (fields.currencyToggle.text == context.getString(R.string.ledger_currency_usd)) {
        LedgerCurrency.USD
    } else {
        LedgerCurrency.LOCAL
    }

internal fun currentLedgerDirection(fields: LedgerEntryFieldViews): LedgerDirection =
    if (fields.directionGroup.checkedRadioButtonId == R.id.entryDirectionIOweThem) {
        LedgerDirection.I_OWE_THEM
    } else {
        LedgerDirection.THEY_OWE_ME
    }

/** Wires the currency toggle, the date field's picker, and the photo-pick
 *  clicks. The caller keeps owning the actual "pending date" state (each
 *  dialog has its own field for it) and passes it in/out via the two
 *  lambdas, rather than this helper holding state of its own. */
internal fun wireLedgerEntryFields(
    context: Context,
    fields: LedgerEntryFieldViews,
    getDateMillis: () -> Long,
    setDateMillis: (Long) -> Unit,
    onPickPhotoClicked: () -> Unit
) {
    fields.currencyToggle.setOnClickListener {
        fields.currencyToggle.text = if (fields.currencyToggle.text == context.getString(R.string.ledger_currency_local)) {
            context.getString(R.string.ledger_currency_usd)
        } else {
            context.getString(R.string.ledger_currency_local)
        }
    }

    fun refreshDateText() {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        fields.dateInput.text = fmt.format(getDateMillis())
    }
    refreshDateText()

    fields.dateInput.setOnClickListener {
        val cal = Calendar.getInstance().apply { timeInMillis = getDateMillis() }
        DatePickerDialog(
            context,
            { _, year, month, day ->
                val picked = Calendar.getInstance().apply { set(year, month, day) }
                setDateMillis(picked.timeInMillis)
                refreshDateText()
            },
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    val photoClick = View.OnClickListener { onPickPhotoClicked() }
    fields.photoPreview.setOnClickListener(photoClick)
    fields.choosePhotoText.setOnClickListener(photoClick)
}

/** Name + phone editor for an existing account — shared by the accounts
 *  list (long-press) and the account detail screen (long-press the name),
 *  so renaming a contact behaves the same from either place. */
internal fun showEditAccountDialog(context: Context, account: LedgerAccount) {
    val view = LayoutInflater.from(context).inflate(R.layout.dialog_edit_account, null)
    val nameInput = view.findViewById<EditText>(R.id.accountNameInput)
    val phoneInput = view.findViewById<EditText>(R.id.accountPhoneInput)
    nameInput.setText(account.name)
    phoneInput.setText(account.phone ?: "")

    AlertDialog.Builder(context)
        .setTitle(R.string.account_edit_title)
        .setView(view)
        .setPositiveButton(R.string.save) { _, _ ->
            val name = nameInput.text?.toString()?.trim().orEmpty()
            if (name.isEmpty()) {
                Toast.makeText(context, R.string.account_name_required, Toast.LENGTH_SHORT).show()
                return@setPositiveButton
            }
            LedgerRepository.updateAccount(account.id, name, phoneInput.text?.toString()?.trim())
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}

/** "Edit" / "Delete" picker for an account — [onDelete] is left to the
 *  caller since the accounts list and the detail screen each need to do
 *  something slightly different afterwards (the detail screen also has to
 *  leave the now-gone account's own screen). */
internal fun showAccountActionMenu(context: Context, account: LedgerAccount, onDelete: () -> Unit) {
    val options = arrayOf(context.getString(R.string.account_edit_action), context.getString(R.string.notes_delete_confirm))
    AlertDialog.Builder(context)
        .setTitle(account.name)
        .setItems(options) { _, which ->
            if (which == 0) showEditAccountDialog(context, account) else onDelete()
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}
