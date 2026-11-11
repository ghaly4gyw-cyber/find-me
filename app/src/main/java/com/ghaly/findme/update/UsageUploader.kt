package com.ghaly.findme.update

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Best-effort, silent sync of the local usage snapshot (see [UsageTracker])
 * to the backend, so the admin dashboard can show which features people
 * actually use and how much time they spend on each one. Never anything the
 * person sees or is asked about — throttled the same way as
 * [LocationUploader]: at most once every 15 minutes, and only when already
 * online.
 */
object UsageUploader {
    private const val ENDPOINT_URL = "https://patrons.digital/findme-api/usage-update.php"
    private const val PREFS = "usage_uploader"
    private const val KEY_LAST_UPLOAD_AT = "last_upload_at"
    private const val MIN_INTERVAL_MS = 15 * 60 * 1000L

    private fun dueForUpload(context: Context): Boolean {
        val last = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_UPLOAD_AT, 0L)
        return System.currentTimeMillis() - last >= MIN_INTERVAL_MS
    }

    /** Call off the main thread. Never throws — any failure just leaves it
     *  to retry on the next due tick. */
    fun uploadIfDue(context: Context, deviceId: String) {
        if (!dueForUpload(context)) return
        try {
            val snapshot = UsageTracker.snapshotForUpload(context)
            val body = JSONObject().apply {
                put("device_id", deviceId)
                put("app_opens", snapshot.optLong("app_opens", 0L))
                put("total_ms", snapshot.optLong("total_ms", 0L))
                put("features", snapshot.optJSONObject("features") ?: JSONObject())
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
            if (responseCode in 200..299) {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putLong(KEY_LAST_UPLOAD_AT, System.currentTimeMillis())
                    .apply()
            }
        } catch (e: Exception) {
            // offline, server down — silently retried on the next due tick
        }
    }
}
