package com.ghaly.findme.util

import android.widget.EditText

/**
 * Mauritanian mobile numbers only: exactly 8 digits, starting with 2
 * (Mattel), 3 (Mauritel) or 4 (Chinguitel) — "just those, not more, not
 * less... it should be 8, not less or more". Used both client-side (so
 * someone finds out immediately, not after a failed upload) and mirrored
 * server-side in every endpoint that stores a phone number.
 */
object PhoneValidator {
    private val PATTERN = Regex("^[234][0-9]{7}$")

    fun isValid(phone: String): Boolean = PATTERN.matches(phone)

    /** Restricts [input] to digits only, capped at 8 characters, and live-
     *  flags (via [EditText.setError]) once 8 digits are in but the number
     *  doesn't start with 2/3/4 — "dynamically", as they type, rather than
     *  only when they try to save. Call once, right after inflating the
     *  dialog that holds [input]. [errorMessage] is looked up lazily so this
     *  can be called before a Context-dependent string is available. */
    fun attachLiveValidation(input: EditText, errorMessage: () -> String) {
        input.filters = arrayOf(
            android.text.InputFilter.LengthFilter(8),
            android.text.InputFilter { source, _, _, _, _, _ -> source.filter { it.isDigit() } }
        )
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val text = s?.toString().orEmpty()
                input.error = if (text.isNotEmpty() && !isValid(text) && text.length == 8) {
                    errorMessage()
                } else {
                    null
                }
            }
        })
    }
}
