package com.ghaly.findme.update

import android.content.Context
import com.ghaly.findme.mesh.ProfileStore
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Best-effort, silent-failure upload of the local registration (name, phone,
 * device id) to the backend, whenever the app happens to be online — exactly
 * the "register offline, upload when online" split the app's whole
 * registration flow is built around. Registration itself never depends on
 * this succeeding; this only syncs it up afterward.
 *
 * The one thing this endpoint can catch that the device alone never could:
 * the same phone number already registered on a different device. If the
 * server rejects for that reason, it's recorded here and surfaced once via
 * [consumePendingConflictMessage] — not blocking, not punitive, just an
 * honest heads-up.
 */
object RegistrationUploader {

    private const val ENDPOINT_URL = "https://patrons.digital/findme-api/register.php"
    private const val PREFS = "registration_uploader"
    private const val KEY_UPLOADED = "uploaded"
    private const val KEY_CONFLICT_PENDING = "conflict_pending"
    private const val KEY_CONTACT_CODE = "contact_code"

    fun isUploaded(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_UPLOADED, false)
    }

    /** The short code register.php generated for this account (see
     *  add-contact.php) — null until the first successful upload. Shown in
     *  the profile info dialog so it can be shared with someone who wants to
     *  add this account as an online contact. */
    fun getContactCode(context: Context): String? {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CONTACT_CODE, null)
    }

    /** Call off the main thread. Never throws — any failure (offline, server
     *  down, malformed response) just leaves it to retry on a later call.
     *  [description] and [photoBase64] are optional (captured once at the
     *  same first-run registration moment as name/phone) and are only sent
     *  when present. */
    fun uploadIfNeeded(
        context: Context,
        name: String,
        phone: String,
        deviceId: String,
        description: String? = null,
        photoBase64: String? = null
    ) {
        if (isUploaded(context)) return
        try {
            val body = JSONObject().apply {
                put("name", name)
                put("phone", phone)
                put("device_id", deviceId)
                if (!description.isNullOrEmpty()) put("description", description)
                if (!photoBase64.isNullOrEmpty()) put("photo_base64", photoBase64)
            }
            val connection = (URL(ENDPOINT_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 10_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }
            val json = text?.let { JSONObject(it) }

            when {
                responseCode in 200..299 && json?.optString("status") == "ok" -> {
                    val code = json.optString("contact_code", "")
                    val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putBoolean(KEY_UPLOADED, true)
                    if (code.isNotEmpty()) editor.putString(KEY_CONTACT_CODE, code)
                    editor.apply()
                    // Fastest path to an accurate activation-gate state — the
                    // very first server round trip this account ever makes
                    // already says whether it's activated/admin, no need to
                    // wait for BlockChecker's own separate periodic poll.
                    ProfileStore.applyActivationStatus(
                        context,
                        activated = json.optBoolean("activated", false),
                        isAdmin = json.optBoolean("is_admin", false)
                    )
                }
                responseCode == 409 -> {
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putBoolean(KEY_CONFLICT_PENDING, true)
                        .apply()
                }
                else -> {
                    // Any other outcome: leave isUploaded() false so the next
                    // opportunistic call (next launch, next time online) just
                    // tries again.
                }
            }
        } catch (e: Exception) {
            // Offline, DNS failure, server down, timeout — all silent. This
            // is a best-effort sync, never something the app surfaces as an
            // error to someone who may have no way to fix it anyway.
        }
    }

    /** True once, the first time it's read after a conflict was recorded —
     *  reading it clears the flag, so it's shown to the person exactly once. */
    fun consumePendingConflict(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pending = prefs.getBoolean(KEY_CONFLICT_PENDING, false)
        if (pending) {
            prefs.edit().putBoolean(KEY_CONFLICT_PENDING, false).apply()
        }
        return pending
    }
}
