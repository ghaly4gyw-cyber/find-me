package com.ghaly.findme.update

import android.content.Context
import com.ghaly.findme.model.SavedPlace
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Best-effort, silent sync of this phone's PUBLIC saved places (ones marked
 * "share with team" at save time — see SavedPlaceRepository/RadarMapFragment)
 * to the backend, so they show up on the admin dashboard even for a site
 * hundreds of km from any other, with no mesh connection between them at
 * all. Purely a one-way upload: a place only ever travels from a phone to
 * the server here, never the other way — the mesh broadcast (MeshManager
 * .sharePlace) is still what spreads a place to nearby teammates in real
 * time; this is the separate, slower "everyone, eventually, once online"
 * channel. Same throttled, silent, offline-safe shape as every other
 * Uploader in this package.
 */
object PublicPlaceUploader {
    private const val ENDPOINT_URL = "https://patrons.digital/findme-api/public-place-upload.php"
    private const val PREFS = "public_place_uploader"
    private const val KEY_LAST_UPLOAD_AT = "last_upload_at"
    private const val MIN_INTERVAL_MS = 15 * 60 * 1000L

    private fun dueForUpload(context: Context): Boolean {
        val last = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_UPLOAD_AT, 0L)
        return System.currentTimeMillis() - last >= MIN_INTERVAL_MS
    }

    /** Call off the main thread. [places] should already be filtered down to
     *  the ones marked public (sharedByName != null) — nothing private ever
     *  reaches this function in the first place. Never throws; any failure
     *  (offline, server down) just leaves it to retry on the next due tick. */
    fun uploadIfDue(context: Context, deviceId: String, places: List<SavedPlace>) {
        if (places.isEmpty()) return
        if (!dueForUpload(context)) return
        try {
            val arr = JSONArray()
            for (p in places) {
                arr.put(
                    JSONObject()
                        .put("place_id", p.id)
                        .put("name", p.name)
                        .put("lat", p.lat)
                        .put("lon", p.lon)
                        .put("shared_by_name", p.sharedByName ?: "")
                        .put("ts", p.ts)
                )
            }
            val body = JSONObject()
                .put("device_id", deviceId)
                .put("places", arr)
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
