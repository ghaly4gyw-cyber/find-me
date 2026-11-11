package com.ghaly.findme.update

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** One pending activation request, as returned by
 *  activation-requests-list.php — only ever fetched by an admin device. */
data class PendingActivation(
    val id: Int,
    val phone: String,
    val name: String,
    val deviceId: String,
    val requestedAtRaw: String
)

/**
 * The "request to admin" half of account activation — activate-request.php
 * (anyone), activation-requests-list.php / activation-decide.php (Osama/
 * Ghaly only). The other half, the offline code, never touches the network
 * at all (see ActivationCodes.kt) and has no endpoint here.
 */
object ActivationMessenger {
    private const val BASE_URL = "https://patrons.digital/findme-api"

    /** Call off the main thread. Returns true if the request reached the
     *  server (whether it was newly queued or the account turned out to
     *  already be activated), false on any failure. */
    fun requestActivation(context: Context, phone: String, deviceId: String): Boolean {
        return try {
            val (responseCode, json) = postJson(
                "$BASE_URL/activate-request.php",
                JSONObject().put("phone", phone).put("device_id", deviceId)
            )
            responseCode in 200..299 && json?.optString("status") == "ok"
        } catch (e: Exception) {
            false
        }
    }

    /** Call off the main thread. Null on any failure (offline, not an admin,
     *  server down) — callers just leave the last-shown list as-is. */
    fun fetchPendingRequests(context: Context, adminPhone: String, deviceId: String): List<PendingActivation>? {
        return try {
            val (responseCode, json) = postJson(
                "$BASE_URL/activation-requests-list.php",
                JSONObject().put("phone", adminPhone).put("device_id", deviceId)
            )
            if (responseCode !in 200..299 || json?.optString("status") != "ok") return null
            val arr = json.optJSONArray("requests") ?: org.json.JSONArray()
            val result = mutableListOf<PendingActivation>()
            for (i in 0 until arr.length()) {
                val r = arr.getJSONObject(i)
                result += PendingActivation(
                    id = r.getInt("id"),
                    phone = r.getString("phone"),
                    name = r.getString("name"),
                    deviceId = r.optString("device_id", ""),
                    requestedAtRaw = r.optString("requested_at", "")
                )
            }
            result
        } catch (e: Exception) {
            null
        }
    }

    /** Call off the main thread. [approve] = true to activate, false to
     *  reject. Returns true on success. */
    fun decideRequest(context: Context, adminPhone: String, deviceId: String, requestId: Int, approve: Boolean): Boolean {
        return try {
            val (responseCode, json) = postJson(
                "$BASE_URL/activation-decide.php",
                JSONObject()
                    .put("phone", adminPhone)
                    .put("device_id", deviceId)
                    .put("request_id", requestId)
                    .put("decision", if (approve) "approve" else "reject")
            )
            responseCode in 200..299 && json?.optString("status") == "ok"
        } catch (e: Exception) {
            false
        }
    }

    private fun postJson(url: String, body: JSONObject): Pair<Int, JSONObject?> {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
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
        return Pair(responseCode, text?.let { JSONObject(it) })
    }
}
