package com.ghaly.findme.update

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * A tiny, honest "remote control" channel for a safety app that is otherwise
 * deliberately offline: a plain JSON file (control.json), committed to the
 * project's own GitHub repo and published as a release asset exactly like
 * version.json, that the developer edits and pushes whenever something
 * needs to reach installs that are out in the world. No server, no account
 * system, no per-device tracking — every install that happens to have
 * internet when it checks reads the same file and reacts to what it
 * currently says.
 *
 * Deliberately limited to two things: showing a one-time message, and
 * flagging that a version is too old to keep using. It does not — and will
 * not — reach into the mesh, location, or any other safety feature. A
 * remote "off switch" for a device-finding tool is a safety hazard in
 * itself if this channel is ever wrong, delayed, spoofed, or just
 * unreachable (which, offline in the desert, it usually will be) — so
 * nothing here ever disables anything. At most it asks.
 */
object RemoteControl {

    private const val REPO = "ghaly4gyw-cyber/find-me"
    private const val RELEASE_API_URL = "https://api.github.com/repos/$REPO/releases/tags/latest"
    private const val CONTROL_ASSET_NAME = "control.json"
    private const val PREFS = "remote_control"
    private const val KEY_LAST_MESSAGE_ID = "last_message_id"

    data class ControlMessage(val id: String, val title: String, val body: String)

    data class ControlState(
        val message: ControlMessage?,
        val minSupportedVersionCode: Long,
        val minSupportedMessage: String?
    )

    /** Null on any failure (offline, GitHub unreachable, malformed file).
     *  Call off the main thread. Never throws. */
    fun check(context: Context): ControlState? {
        return try {
            val release = fetchJson(RELEASE_API_URL) ?: return null
            val assets = release.optJSONArray("assets") ?: return null
            var controlUrl: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.optString("name") == CONTROL_ASSET_NAME) {
                    controlUrl = asset.optString("browser_download_url")
                }
            }
            if (controlUrl == null) return null
            val json = fetchJson(controlUrl) ?: return null

            val messageObj = json.optJSONObject("message")
            val message = messageObj?.let {
                val id = it.optString("id", "")
                val title = it.optString("title", "")
                val body = it.optString("body", "")
                if (id.isNotEmpty() && (title.isNotEmpty() || body.isNotEmpty())) {
                    ControlMessage(id, title, body)
                } else {
                    null
                }
            }

            ControlState(
                message = message,
                minSupportedVersionCode = json.optLong("min_supported_version", 0L),
                minSupportedMessage = json.optString("min_supported_message", "").ifEmpty { null }
            )
        } catch (e: Exception) {
            null
        }
    }

    /** True the first time this message id is seen on this device — marks
     *  it seen as a side effect, so a repeat check never shows it again. */
    fun shouldShowMessage(context: Context, message: ControlMessage): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastShown = prefs.getString(KEY_LAST_MESSAGE_ID, null)
        if (lastShown == message.id) return false
        prefs.edit().putString(KEY_LAST_MESSAGE_ID, message.id).apply()
        return true
    }

    private fun fetchJson(url: String): JSONObject? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        connection.connect()
        if (connection.responseCode !in 200..299) return null
        val text = connection.inputStream.bufferedReader().use { it.readText() }
        return JSONObject(text)
    }
}
