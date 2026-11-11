package com.ghaly.findme.mesh

import android.content.Context
import com.ghaly.findme.model.Profile

/** My own profile, persisted locally. Name itself still lives in [DeviceIdentity]
 *  (kept for backward compatibility with existing saved data); this adds the rest. */
object ProfileStore {
    private const val PREFS = "find_me_profile"
    private const val KEY_PHONE = "phone"
    private const val KEY_DESC = "description"
    private const val KEY_PHOTO = "photo_b64"
    private const val KEY_REGISTERED = "registered"

    // Recovery PIN — deliberately NOT part of Profile/Models.kt, which is
    // broadcast to every nearby peer over the (unencrypted) mesh. The PIN
    // only ever travels over HTTPS to set-pin.php/reclaim.php. KEY_PIN_PENDING
    // holds the plaintext only until PinUploader confirms the server has it;
    // KEY_PIN_IS_SET is what the UI reads afterward to show "PIN set" without
    // needing the plaintext around anymore.
    private const val KEY_PIN_PENDING = "pin_pending_upload"
    private const val KEY_PIN_IS_SET = "pin_is_set"

    fun get(context: Context): Profile {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Profile(
            deviceId = DeviceIdentity.id(context),
            name = DeviceIdentity.name(context),
            phone = prefs.getString(KEY_PHONE, null)?.ifEmpty { null },
            description = prefs.getString(KEY_DESC, null)?.ifEmpty { null },
            photoBase64 = prefs.getString(KEY_PHOTO, null)
        )
    }

    fun save(context: Context, phone: String?, description: String?, photoBase64: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PHONE, phone.orEmpty())
            .putString(KEY_DESC, description.orEmpty())
            .apply {
                if (photoBase64 != null) putString(KEY_PHOTO, photoBase64)
            }
            .apply()
    }

    /** True once the mandatory first-run registration (name + phone) has been
     *  completed. After that, the registration gate never shows again and the
     *  profile becomes read-only — per the "register once, can't edit after"
     *  requirement. */
    fun isRegistered(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_REGISTERED, false)
    }

    fun markRegistered(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_REGISTERED, true)
            .apply()
    }

    /** True once a PIN has been successfully set on the server (or was known
     *  to be set before this device even asked, e.g. right after a reclaim). */
    fun isPinSet(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PIN_IS_SET, false)
    }

    /** Stashes a newly chosen PIN (or "" to clear it) for [PinUploader] to
     *  sync next time the phone is online — see its class doc for why this
     *  isn't just folded into [save]/[Profile]. */
    fun setPendingPin(context: Context, pin: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PIN_PENDING, pin)
            .apply()
    }

    /** Null if there's nothing waiting to be synced. */
    fun pendingPin(context: Context): String? {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PIN_PENDING, null)
    }

    /** Called by PinUploader once the server confirms it has [pin] — clears
     *  the plaintext copy and records whether a PIN now exists at all. */
    fun confirmPinSynced(context: Context, pin: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_PIN_PENDING)
            .putBoolean(KEY_PIN_IS_SET, pin.isNotEmpty())
            .apply()
    }

    /** Called right after a successful reclaim onto this phone (the server
     *  already has a PIN on file by definition — reclaiming required it). */
    fun markPinSetFromReclaim(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_PIN_IS_SET, true)
            .apply()
    }

    /** Clears every locally-stored field — used when this device signs
     *  itself out after learning (via status-check.php) that its phone
     *  number now belongs to a different device (see
     *  MainActivity.enforceSessionState). Deliberately does NOT touch the
     *  ledger/notes/saved-places repositories — that data stays on this
     *  phone; only the account binding itself is cleared, the same way the
     *  very first run of the app looked before anyone ever registered. */
    fun clearForSignOut(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
