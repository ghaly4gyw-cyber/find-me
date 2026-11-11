package com.ghaly.findme.update

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Checks whether this device has been blocked from the admin dashboard (see
 * status-check.php and the dashboard's Block button) and caches the last
 * confirmed answer locally — so the gate the Activity shows (see
 * MainActivity.enforceBlockState) reflects the most recently confirmed
 * state even between checks, including while offline, and clears itself
 * automatically the next time a check confirms the device was unblocked.
 *
 * Deliberately conservative: a failed check (offline, server unreachable)
 * never changes the cached state either way — only an actual answer from
 * the server does.
 */
object BlockChecker {
    private const val ENDPOINT_URL = "https://patrons.digital/findme-api/status-check.php"
    private const val PREFS = "block_checker"
    private const val KEY_LAST_BLOCKED = "last_blocked"
    private const val KEY_LAST_CHECK_AT = "last_check_at"
    private const val MIN_INTERVAL_MS = 15 * 60 * 1000L

    /** The most recently confirmed status — false until a check ever
     *  succeeds, and holds its last confirmed value between checks rather
     *  than resetting while offline. */
    fun isBlocked(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_LAST_BLOCKED, false)
    }

    private fun dueForCheck(context: Context): Boolean {
        val last = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_CHECK_AT, 0L)
        return System.currentTimeMillis() - last >= MIN_INTERVAL_MS
    }

    /** Call off the main thread. Never throws. Updates the cached status
     *  only on an actual successful response. */
    fun checkIfDue(context: Context, deviceId: String) {
        if (!dueForCheck(context)) return
        try {
            val encoded = URLEncoder.encode(deviceId, "UTF-8")
            val connection = (URL("$ENDPOINT_URL?device_id=$encoded").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            connection.connect()
            if (connection.responseCode !in 200..299) return
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val blocked = json.optBoolean("blocked", false)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_LAST_BLOCKED, blocked)
                .putLong(KEY_LAST_CHECK_AT, System.currentTimeMillis())
                .apply()
        } catch (e: Exception) {
            // Offline or server down — cached state (if any) is left as-is.
        }
    }
}
