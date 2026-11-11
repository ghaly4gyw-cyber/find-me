package com.ghaly.findme.util

import android.content.Context

/**
 * Offline activation codes — entered right on the activation-gate screen
 * with zero network involved at the moment of checking (see
 * MainActivity.enforceActivationGate / ProfileStore.markActivatedLocally).
 * "The app cannot be activated without that password", and checking has to
 * work even where there's no signal at all, which is most of the desert
 * this app is for — so the actual comparison always happens against
 * whatever this device last learned, cached locally, never a live call.
 *
 * What's now dynamic: the codes themselves live in the admin_accounts table
 * on the backend and can be changed from the web panel's "Your account" /
 * "Manage admins" sections (see admin/code-save.php) — no new app release
 * needed. register.php, status-check.php, and reclaim.php all now hand a
 * device the current full list of valid codes whenever it happens to be
 * online (the same opportunistic pattern as the kill switch, blocked
 * status, etc.), and [updateKnownCodes] replaces this device's cached list
 * with that — so a code that was just changed on the panel stops working
 * here the next time this device checks in, and a newly set one starts.
 *
 * The honest limit, same as everywhere else in this file's family: a
 * device with no signal at all never learns about a change until it
 * reconnects, and a device that's NEVER been online falls back to the two
 * original codes this app first shipped with (below) — there's no way
 * around either of those for an offline-first app, and changing a code on
 * the panel doesn't retroactively revoke it from a phone that's simply
 * never checked in since.
 */
object ActivationCodes {
    // The two codes this app originally shipped with — kept only as the
    // bootstrap fallback for a device that has never once synced a list
    // from the server (a brand-new install with no signal yet, say).
    private const val BOOTSTRAP_GHALY_CODE = "YAKMP5MS"
    private const val BOOTSTRAP_OSAMA_CODE = "EARJB47Q"

    private const val PREFS = "activation_codes"
    private const val KEY_KNOWN_CODES = "known_codes" // comma-joined
    private const val KEY_HAS_SYNCED = "has_synced"

    /** Case-insensitive, whitespace-trimmed — so "yakmp5ms" or " YAKMP5MS "
     *  typed on a cramped on-screen keyboard still works. */
    fun isValid(context: Context, input: String): Boolean {
        val normalized = input.trim().uppercase()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_HAS_SYNCED, false)) {
            return normalized == BOOTSTRAP_GHALY_CODE || normalized == BOOTSTRAP_OSAMA_CODE
        }
        val known = prefs.getString(KEY_KNOWN_CODES, "") ?: ""
        return known.split(",").contains(normalized)
    }

    /** Replaces this device's cached list of valid codes wholesale — called
     *  from RegistrationUploader/BlockChecker/AccountReclaimer whenever the
     *  server hands back a fresh "activation_codes" array. Never throws on
     *  an empty list — that just means no admin has a code set right now,
     *  and [isValid] will correctly accept nothing until one does. */
    fun updateKnownCodes(context: Context, codes: List<String>) {
        val normalized = codes.map { it.trim().uppercase() }.filter { it.isNotEmpty() }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_KNOWN_CODES, normalized.joinToString(","))
            .putBoolean(KEY_HAS_SYNCED, true)
            .apply()
    }

    /** Display-only (not used for any check) — shown on the activation gate
     *  so someone stuck waiting knows exactly who to call. Mirrors
     *  ADMIN_PHONES in the backend's schema.php; keep both in sync if either
     *  number ever changes. */
    const val GHALY_CONTACT_PHONE = "+222 47 33 99 60"
    const val OSAMA_CONTACT_PHONE = "+222 47 05 27 97"
}
