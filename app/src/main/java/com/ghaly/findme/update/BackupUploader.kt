package com.ghaly.findme.update

import android.content.Context
import com.ghaly.findme.repo.LedgerRepository
import com.ghaly.findme.repo.NotesRepository
import com.ghaly.findme.repo.SavedPlaceRepository
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Best-effort, silent backup of this phone's own accounts ledger, notes and
 * saved places to the backend — "their data should not be auto-deleted...
 * their data should be on the host and auto go with them" if the phone is
 * ever lost, broken, or reinstalled. Only means anything together with a PIN
 * (see PinUploader) and a reclaim on the new phone (see AccountReclaimer) —
 * without a PIN there's nothing proving who gets this backup back, so it's
 * uploaded either way (cheap, harmless) but only ever retrievable via a
 * successful reclaim.
 *
 * Same throttled, silent, offline-safe shape as every other Uploader in this
 * package — bundles everything into one JSON blob the server doesn't need to
 * understand (see backup-upload.php), rather than one request per repository.
 */
object BackupUploader {
    private const val ENDPOINT_URL = "https://patrons.digital/findme-api/backup-upload.php"
    private const val PREFS = "backup_uploader"
    private const val KEY_LAST_UPLOAD_AT = "last_upload_at"
    private const val MIN_INTERVAL_MS = 15 * 60 * 1000L

    private fun dueForUpload(context: Context): Boolean {
        val last = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_UPLOAD_AT, 0L)
        return System.currentTimeMillis() - last >= MIN_INTERVAL_MS
    }

    /** Call off the main thread. Never throws — any failure (offline, server
     *  down, backup too large) just leaves it to retry on the next due tick. */
    fun uploadIfDue(context: Context, phone: String, deviceId: String) {
        if (!dueForUpload(context)) return
        try {
            val (booksJson, accountsJson) = LedgerRepository.exportForBackup()
            val bundle = JSONObject()
                .put("ledgerBooks", org.json.JSONArray(booksJson))
                .put("ledgerAccounts", org.json.JSONArray(accountsJson))
                .put("notes", org.json.JSONArray(NotesRepository.exportForBackup()))
                .put("places", org.json.JSONArray(SavedPlaceRepository.exportForBackup()))

            val body = JSONObject()
                .put("phone", phone)
                .put("device_id", deviceId)
                .put("backup_json", bundle.toString())

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
            // offline, server down, malformed local cache — silently retried
            // on the next due tick; nothing here is ever the only copy while
            // the phone itself is still alive.
        }
    }
}
