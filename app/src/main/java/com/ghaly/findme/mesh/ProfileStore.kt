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

    // Account-activation gate — "the app cannot be activated without that
    // password or accepting the demand". KEY_ACTIVATED is the one flag
    // MainActivity's gate actually checks; KEY_PENDING_ACTIVATION_CONFIRM is
    // set only by a LOCAL code activation (ActivationCodes.kt), so the next
    // time the phone is online, ActivationUploader can tell the server too —
    // see that class's doc comment for why the gate itself never waits on it.
    private const val KEY_ACTIVATED = "activated"
    private const val KEY_IS_ADMIN = "is_admin"
    private const val KEY_PENDING_ACTIVATION_CONFIRM = "pending_activation_confirm"
    // The exact code string that was typed in, kept only long enough for
    // ActivationUploader to report it alongside the pending confirm above —
    // lets the server credit whichever supplier/admin owns that code (see
    // activation-confirm.php). Never used for anything else on-device.
    private const val KEY_PENDING_ACTIVATION_CODE = "pending_activation_code"

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

    /** False until an admin approves this account (or it was always one of
     *  Ghaly/Osama's own numbers) — see MainActivity.enforceActivationGate.
     *  Defaults to false: a never-yet-confirmed account is locked, not open,
     *  which is the safe direction for a gate whose whole point is keeping
     *  the app out of the wrong hands. */
    fun isActivated(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ACTIVATED, false)
    }

    /** True only for Ghaly's/Osama's own registered phone numbers (server
     *  decides this, via ADMIN_PHONES in schema.php) — shows the extra
     *  "pending activations" screen in the app. */
    fun isAdmin(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_IS_ADMIN, false)
    }

    /** Applied from a server response (register.php or status-check.php).
     *  Deliberately monotonic on [activated]: an account this phone already
     *  unlocked for itself by entering a code (see ActivationCodes.kt) must
     *  never be re-locked just because a particular sync happened to run
     *  before ActivationUploader's own confirm call reached the server —
     *  only ever OR's in a true from the server, never clears a true that's
     *  already set locally. [isAdmin] has no such concern (it only ever
     *  reflects the server's own ADMIN_PHONES check) so it's just applied
     *  directly. */
    fun applyActivationStatus(context: Context, activated: Boolean, isAdmin: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_ACTIVATED, prefs.getBoolean(KEY_ACTIVATED, false) || activated)
            .putBoolean(KEY_IS_ADMIN, isAdmin)
            .apply()
    }

    /** Called the moment someone enters a valid offline activation code
     *  (ActivationCodes.isValid) — unlocks the app on this device instantly,
     *  with no network involved, and leaves a marker for ActivationUploader
     *  to tell the server too, next time this phone happens to be online.
     *  [code] is the exact string they typed, so the server can credit
     *  whichever supplier/admin owns it. */
    fun markActivatedLocally(context: Context, code: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ACTIVATED, true)
            .putBoolean(KEY_PENDING_ACTIVATION_CONFIRM, true)
            .putString(KEY_PENDING_ACTIVATION_CODE, code.trim().uppercase())
            .apply()
    }

    /** Null (nothing pending) once ActivationUploader has successfully told
     *  the server about a local code activation, or if this account was
     *  never activated by code in the first place (e.g. admin-approved). */
    fun hasPendingActivationConfirm(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PENDING_ACTIVATION_CONFIRM, false)
    }

    /** The code ActivationUploader should report alongside the pending
     *  confirm — empty if this account was never activated by a typed code. */
    fun pendingActivationCode(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PENDING_ACTIVATION_CODE, "") ?: ""
    }

    fun clearPendingActivationConfirm(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_PENDING_ACTIVATION_CONFIRM, false)
            .apply()
    }
}
