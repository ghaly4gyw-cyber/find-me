package com.ghaly.findme.util

/**
 * The two offline activation codes — Ghaly's and Osama's own, each capable
 * of unlocking ANY account on its own, entered right on the activation-gate
 * screen with zero network involved (see MainActivity.enforceActivationGate
 * / ProfileStore.markActivatedLocally). Deliberately baked into the app
 * rather than checked against the server — "the app cannot be activated
 * without that password", and this has to work even where there's no signal
 * at all, which is most of the desert this app is for.
 *
 * The trade-off, accepted on purpose: anyone who decompiles the APK can read
 * these two strings. That's an acceptable risk for keeping random people and
 * casual leaks out, which is the actual threat here — not a determined
 * reverse-engineer. Changing either code means updating the value below and
 * shipping a new release (there's no in-app way to rotate it, by design —
 * see the project notes on why a server-checked code was rejected in favor
 * of this).
 */
object ActivationCodes {
    private const val GHALY_CODE = "YAKMP5MS"
    private const val OSAMA_CODE = "EARJB47Q"

    /** Case-insensitive, whitespace-trimmed — so "yakmp5ms" or " YAKMP5MS "
     *  typed on a cramped on-screen keyboard still works. */
    fun isValid(input: String): Boolean {
        val normalized = input.trim().uppercase()
        return normalized == GHALY_CODE || normalized == OSAMA_CODE
    }

    /** Display-only (not used for any check) — shown on the activation gate
     *  so someone stuck waiting knows exactly who to call. Mirrors
     *  ADMIN_PHONES in the backend's schema.php; keep both in sync if either
     *  number ever changes. */
    const val GHALY_CONTACT_PHONE = "+222 47 33 99 60"
    const val OSAMA_CONTACT_PHONE = "+222 47 05 27 97"
}
