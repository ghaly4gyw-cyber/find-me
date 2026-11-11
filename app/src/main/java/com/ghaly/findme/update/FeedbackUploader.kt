package com.ghaly.findme.update

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sends a person's free-text suggestion to the backend so it shows up on
 * the admin dashboard. Unlike the other uploaders in this package, this
 * always runs the moment someone taps "Send" — not silent, not throttled —
 * because they chose to say something and deserve to know whether it went
 * through.
 */
object FeedbackUploader {
    private const val ENDPOINT_URL = "https://patrons.digital/findme-api/feedback.php"

    /** Call off the main thread. Returns true only if the server actually
     *  accepted it. */
    fun send(deviceId: String, name: String?, message: String): Boolean {
        return try {
            val body = JSONObject().apply {
                put("device_id", deviceId)
                put("message", message)
                if (!name.isNullOrBlank()) put("name", name)
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
            responseCode in 200..299 && json?.optString("status") == "ok"
        } catch (e: Exception) {
            false
        }
    }
}
