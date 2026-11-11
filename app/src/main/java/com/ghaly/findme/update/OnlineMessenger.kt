package com.ghaly.findme.update

import android.content.Context
import com.ghaly.findme.model.OnlineContact
import com.ghaly.findme.model.OnlineMessage
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Everything to do with the online (internet, not mesh) side of contacts and
 * messaging — heartbeat.php, add-contact.php, contacts-status.php,
 * send-message.php, fetch-messages.php. Every call here needs a live
 * connection by nature (that's the whole point: reaching someone who isn't
 * in Bluetooth/Wi-Fi mesh range right now), so callers always run these off
 * the main thread and handle a null/failure result as "try again later" —
 * nothing here is more urgent than the offline mesh features, which keep
 * working exactly as before regardless of any of this.
 */
object OnlineMessenger {
    private const val BASE_URL = "https://patrons.digital/findme-api"

    sealed class AddContactResult {
        data class Success(val phone: String, val name: String) : AddContactResult()
        object NotFound : AddContactResult()
        object CannotAddSelf : AddContactResult()
        object NetworkError : AddContactResult()
    }

    /** Call off the main thread. Silent on failure — this is a background
     *  presence ping, never worth bothering anyone about. */
    fun heartbeat(context: Context, phone: String, deviceId: String) {
        try {
            postJson("$BASE_URL/heartbeat.php", JSONObject().put("phone", phone).put("device_id", deviceId))
        } catch (e: Exception) {
            // best-effort — the next periodic tick just tries again
        }
    }

    /** Call off the main thread. */
    fun addContact(context: Context, phone: String, deviceId: String, contactCode: String): AddContactResult {
        return try {
            val (responseCode, json) = postJson(
                "$BASE_URL/add-contact.php",
                JSONObject().put("phone", phone).put("device_id", deviceId).put("contact_code", contactCode)
            )
            if (responseCode in 200..299 && json?.optString("status") == "ok") {
                AddContactResult.Success(json.optString("phone"), json.optString("name"))
            } else when (json?.optString("reason")) {
                "contact_code_not_found" -> AddContactResult.NotFound
                "cannot_add_self" -> AddContactResult.CannotAddSelf
                else -> AddContactResult.NetworkError
            }
        } catch (e: Exception) {
            AddContactResult.NetworkError
        }
    }

    /** Call off the main thread. Returns null on any failure — callers just
     *  leave the existing online/offline dots as they were until the next
     *  refresh succeeds. */
    fun fetchContactsStatus(
        context: Context,
        phone: String,
        deviceId: String,
        contactPhones: List<String>
    ): Map<String, Pair<Boolean, Int?>>? {
        if (contactPhones.isEmpty()) return emptyMap()
        return try {
            val phonesArray = org.json.JSONArray()
            contactPhones.forEach { phonesArray.put(it) }
            val (responseCode, json) = postJson(
                "$BASE_URL/contacts-status.php",
                JSONObject().put("phone", phone).put("device_id", deviceId).put("phones", phonesArray)
            )
            if (responseCode !in 200..299 || json?.optString("status") != "ok") return null
            val result = mutableMapOf<String, Pair<Boolean, Int?>>()
            val arr = json.optJSONArray("contacts") ?: return result
            for (i in 0 until arr.length()) {
                val entry = arr.getJSONObject(i)
                val lastSeen = if (entry.isNull("last_seen_seconds_ago")) null else entry.optInt("last_seen_seconds_ago")
                result[entry.getString("phone")] = Pair(entry.optBoolean("online", false), lastSeen)
            }
            result
        } catch (e: Exception) {
            null
        }
    }

    /** Call off the main thread. Returns the new message's id on success, or
     *  null on any failure (offline, recipient not registered, etc.). */
    fun sendMessage(context: Context, phone: String, deviceId: String, toPhone: String, body: String): Long? {
        return try {
            val (responseCode, json) = postJson(
                "$BASE_URL/send-message.php",
                JSONObject().put("phone", phone).put("device_id", deviceId)
                    .put("to_phone", toPhone).put("body", body)
            )
            if (responseCode in 200..299 && json?.optString("status") == "ok") json.optLong("id") else null
        } catch (e: Exception) {
            null
        }
    }

    /** Call off the main thread. Returns null on failure (callers should just
     *  retry on the next poll rather than treating it as "no new messages"). */
    fun fetchMessages(context: Context, phone: String, deviceId: String, sinceId: Long): List<OnlineMessage>? {
        return try {
            val (responseCode, json) = postJson(
                "$BASE_URL/fetch-messages.php",
                JSONObject().put("phone", phone).put("device_id", deviceId).put("since_id", sinceId)
            )
            if (responseCode !in 200..299 || json?.optString("status") != "ok") return null
            val arr = json.optJSONArray("messages") ?: org.json.JSONArray()
            val result = mutableListOf<OnlineMessage>()
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                val fromPhone = m.getString("from_phone")
                result += OnlineMessage(
                    id = m.getLong("id"),
                    otherPartyPhone = fromPhone,
                    otherPartyName = m.optString("from_name", "").ifEmpty { null },
                    body = m.getString("body"),
                    ts = System.currentTimeMillis(),
                    mine = false
                )
            }
            result
        } catch (e: Exception) {
            null
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
