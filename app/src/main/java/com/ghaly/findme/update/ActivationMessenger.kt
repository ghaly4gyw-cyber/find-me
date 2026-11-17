package com.ghaly.findme.update

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** One pending activation request, as returned by
 *  activation-requests-list.php — only ever fetched by an admin/supplier
 *  device. */
data class PendingActivation(
    val id: Int,
    val phone: String,
    val name: String,
    val deviceId: String,
    val requestedAtRaw: String
)

/** One account a supplier has personally activated, as returned by
 *  supplier-info.php. */
data class SupplierActivatedUser(
    val name: String,
    val phone: String,
    val via: String, // "code" or "approval"
    val updatedAtRaw: String
)

/** supplier-info.php's full response — everything SupplierPanelFragment
 *  needs for the "own code + stats + people I activated" sections (the
 *  pending queue itself is fetched separately, shared with the admin
 *  screen, via [ActivationMessenger.fetchPendingRequests]). */
data class SupplierInfo(
    val name: String,
    val offlineCode: String?,
    val totalActivated: Int,
    val activatedByCode: Int,
    val activatedByApproval: Int,
    val activatedList: List<SupplierActivatedUser>
)

/** Outcome of asking the server to activate this account, from
 *  [ActivationMessenger.requestActivation]. */
sealed class ActivationRequestResult {
    /** The request was queued and is now waiting on an admin/supplier. */
    data object Queued : ActivationRequestResult()
    /** No need to wait — the server says this account is already
     *  activated (e.g. someone just activated it directly from the web
     *  panel). Caller should unlock the gate immediately rather than
     *  leaving the person to wait on the next background poll. */
    data object AlreadyActivated : ActivationRequestResult()
    /** Offline, server down, or some other failure — nothing changed. */
    data object Failed : ActivationRequestResult()
}

/**
 * The "request to admin" half of account activation — activate-request.php
 * (anyone), activation-requests-list.php / activation-decide.php (Osama/
 * Ghaly only). The other half, the offline code, never touches the network
 * at all (see ActivationCodes.kt) and has no endpoint here.
 */
object ActivationMessenger {
    private const val BASE_URL = "https://patrons.digital/findme-api"

    /** Call off the main thread. */
    fun requestActivation(context: Context, phone: String, deviceId: String): ActivationRequestResult {
        return try {
            val (responseCode, json) = postJson(
                "$BASE_URL/activate-request.php",
                JSONObject().put("phone", phone).put("device_id", deviceId)
            )
            if (responseCode !in 200..299 || json?.optString("status") != "ok") {
                return ActivationRequestResult.Failed
            }
            if (json.optBoolean("already_activated", false)) {
                ActivationRequestResult.AlreadyActivated
            } else {
                ActivationRequestResult.Queued
            }
        } catch (e: Exception) {
            ActivationRequestResult.Failed
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

    /** Call off the main thread. Null on any failure (offline, not a
     *  supplier, server down) — SupplierPanelFragment just shows an error
     *  state and lets the person retry. */
    fun fetchSupplierInfo(context: Context, phone: String, deviceId: String): SupplierInfo? {
        return try {
            val (responseCode, json) = postJson(
                "$BASE_URL/supplier-info.php",
                JSONObject().put("phone", phone).put("device_id", deviceId)
            )
            if (responseCode !in 200..299 || json?.optString("status") != "ok") return null
            val arr = json.optJSONArray("activated_list") ?: org.json.JSONArray()
            val list = mutableListOf<SupplierActivatedUser>()
            for (i in 0 until arr.length()) {
                val u = arr.getJSONObject(i)
                list += SupplierActivatedUser(
                    name = u.optString("name", ""),
                    phone = u.optString("phone", ""),
                    via = u.optString("activated_via", ""),
                    updatedAtRaw = u.optString("updated_at", "")
                )
            }
            SupplierInfo(
                name = json.optString("name", ""),
                offlineCode = if (json.isNull("offline_code")) null else json.optString("offline_code", "").ifEmpty { null },
                totalActivated = json.optInt("total_activated", 0),
                activatedByCode = json.optInt("activated_by_code", 0),
                activatedByApproval = json.optInt("activated_by_approval", 0),
                activatedList = list
            )
        } catch (e: Exception) {
            null
        }
    }

    /** Call off the main thread. Outcome, since "that code belongs to
     *  someone else" is a real, expected case the UI should explain, not
     *  just a generic failure. */
    sealed class ChangeCodeResult {
        data class Success(val newCode: String) : ChangeCodeResult()
        data object AlreadyInUse : ChangeCodeResult()
        data object Failed : ChangeCodeResult()
    }

    fun changeSupplierCode(context: Context, phone: String, deviceId: String, newCode: String): ChangeCodeResult {
        return try {
            val (responseCode, json) = postJson(
                "$BASE_URL/supplier-code-save.php",
                JSONObject().put("phone", phone).put("device_id", deviceId).put("code", newCode)
            )
            when {
                responseCode in 200..299 && json?.optString("status") == "ok" ->
                    ChangeCodeResult.Success(json.optString("offline_code", newCode))
                responseCode == 409 || json?.optString("reason") == "code_already_in_use" ->
                    ChangeCodeResult.AlreadyInUse
                else -> ChangeCodeResult.Failed
            }
        } catch (e: Exception) {
            ChangeCodeResult.Failed
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
