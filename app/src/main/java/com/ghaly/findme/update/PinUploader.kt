package com.ghaly.findme.update

import android.content.Context
import com.ghaly.findme.mesh.ProfileStore
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Syncs a newly set/changed/cleared recovery PIN (see ProfileStore
 * .setPendingPin) to the server the next time the phone is online. Unlike
 * the other opportunistic uploaders this isn't throttled — a PIN change is
 * rare and deliberate (the person just tapped Save on it), so it should
 * reach the server on the very next connectivity check, not wait out a
 * 15-minute window. Still entirely silent/best-effort: until it succeeds,
 * [ProfileStore.pendingPin] just keeps holding the plaintext for the next
 * attempt, and the PIN keeps working offline for everything except an
 * actual reclaim (which needs the server to have it).
 */
object PinUploader {
    private const val ENDPOINT_URL = "https://patrons.digital/findme-api/set-pin.php"

    /** Call off the main thread. Never throws. */
    fun uploadIfPending(context: Context, phone: String, deviceId: String) {
        val pin = ProfileStore.pendingPin(context) ?: return
        try {
            val body = JSONObject()
                .put("phone", phone)
                .put("device_id", deviceId)
                .put("pin", pin)
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
                ProfileStore.confirmPinSynced(context, pin)
            }
            // Any other outcome (offline, 403 stale device_id, etc.) just
            // leaves the pending pin in place for the next opportunistic try.
        } catch (e: Exception) {
            // offline, server down — retried the next time this is called
        }
    }
}
