package com.ghaly.findme.update

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Best-effort, silent-failure upload of this phone's last-known location to
 * the backend, whenever it's online and the mesh already has a GPS fix —
 * this is the anti-theft feature: a registered phone's last position stays
 * visible on the admin dashboard, so a lost or stolen phone can be pointed
 * to. Disclosed to the person at registration time (see the registration
 * screen copy) — nothing about this is hidden from them.
 *
 * Throttled to avoid being chatty: a successful upload is remembered and
 * not repeated for [MIN_INTERVAL_MS], independent of how often the caller
 * happens to check in (the periodic header-refresh tick, app open, etc).
 */
object LocationUploader {

    private const val ENDPOINT_URL = "https://patrons.digital/findme-api/location-update.php"
    private const val PREFS = "location_uploader"
    private const val KEY_LAST_UPLOAD_AT = "last_upload_at"
    private const val MIN_INTERVAL_MS = 15 * 60 * 1000L // 15 minutes

    private fun dueForUpload(context: Context): Boolean {
        val lastAt = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_UPLOAD_AT, 0L)
        return System.currentTimeMillis() - lastAt >= MIN_INTERVAL_MS
    }

    /** Call off the main thread. Never throws — any failure (offline, no GPS
     *  fix yet, server down) just leaves it to try again next time this is
     *  called and enough time has passed. Piggybacks a basic device/network
     *  snapshot (see DeviceInfoCollector) on the same call, since this is
     *  already the periodic "check in" the app makes — no reason for a
     *  second one just for that. */
    fun uploadIfDue(context: Context, deviceId: String, lat: Double, lng: Double) {
        if (!dueForUpload(context)) return
        try {
            val info = DeviceInfoCollector.collect(context)
            val body = JSONObject().apply {
                put("device_id", deviceId)
                put("lat", lat)
                put("lng", lng)
                put("device_model", info.deviceModel)
                put("android_version", info.androidVersion)
                put("app_version", info.appVersionName)
                put("network_type", info.networkType)
                if (!info.networkName.isNullOrEmpty()) put("network_name", info.networkName)
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
            // Any other response (device not registered server-side yet,
            // server error) just leaves the throttle untouched so the next
            // opportunistic call retries — no error surfaced to the person.
        } catch (e: Exception) {
            // Offline, DNS failure, timeout, server down — all silent.
        }
    }
}
