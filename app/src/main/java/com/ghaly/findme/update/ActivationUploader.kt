package com.ghaly.findme.update

import android.content.Context
import com.ghaly.findme.mesh.ProfileStore
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Tells the server about a LOCAL, fully-offline code activation (see
 * ActivationCodes.kt / ProfileStore.markActivatedLocally) the next time this
 * phone happens to be online — same unthrottled, retry-until-it-succeeds
 * shape as PinUploader, since this is a rare, deliberate event, not a
 * recurring ping. The app itself is already unlocked the moment the code was
 * entered; this only keeps the server's own record (and Osama/Ghaly's
 * pending-requests list) honest, and is never something the gate waits on.
 */
object ActivationUploader {
    private const val ENDPOINT_URL = "https://patrons.digital/findme-api/activation-confirm.php"

    /** Call off the main thread. Never throws. */
    fun uploadIfPending(context: Context, phone: String, deviceId: String) {
        if (!ProfileStore.hasPendingActivationConfirm(context)) return
        try {
            val body = JSONObject()
                .put("phone", phone)
                .put("device_id", deviceId)
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
            if (responseCode in 200..299 && json?.optString("status") == "ok") {
                ProfileStore.clearPendingActivationConfirm(context)
            }
            // Any other outcome (offline, stale device_id, server down) just
            // leaves the pending flag in place for the next opportunistic try.
        } catch (e: Exception) {
            // offline, server down — retried the next time this is called
        }
    }
}
