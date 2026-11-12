package com.ghaly.findme.update

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * A tiny, honest "remote control" channel for a safety app that is otherwise
 * deliberately offline: a single JSON endpoint on Find Me's own backend,
 * edited from the admin dashboard's "App control" form (no git, no GitHub
 * involved) — every install that happens to have internet when it checks
 * reads the same thing and reacts to what it currently says.
 *
 * Started out limited to two things: showing a one-time message, and
 * flagging that a version is too old to keep using — see
 * MainActivity.showForcedUpdateGate's own comment for why even that first
 * step was already a deliberate override of "nothing here ever disables
 * anything". killSwitch goes further still: a single global flag, settable
 * only by the dashboard's super-admin, that puts up the same kind of
 * full-screen gate for EVERY device, not just one behind on updates — see
 * MainActivity.showKillSwitchGate. Added at Ghaly's explicit, repeated
 * request, after being told plainly what it costs: a phone with no signal
 * at all (which, lost in the desert, it usually will be) never learns it's
 * on until it reconnects, and while it's on, SOS/mesh/location are all
 * behind the same gate, for everyone who does check in. It remains a
 * reversible toggle, not a one-way action — flipping it back off from the
 * dashboard restores every connected device immediately.
 */
object RemoteControl {

    private const val ENDPOINT_URL = "https://patrons.digital/findme-api/control.php"
    private const val PREFS = "remote_control"
    private const val KEY_LAST_MESSAGE_ID = "last_message_id"

    data class ControlMessage(val id: String, val title: String, val body: String)

    data class ControlState(
        val message: ControlMessage?,
        val minSupportedVersionCode: Long,
        val minSupportedMessage: String?,
        val killSwitch: Boolean,
        val killSwitchMessage: String?
    )

    /** Null on any failure (offline, server unreachable, malformed
     *  response). Call off the main thread. Never throws. */
    fun check(context: Context): ControlState? {
        return try {
            val connection = (URL(ENDPOINT_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            connection.connect()
            if (connection.responseCode !in 200..299) return null
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })

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
                minSupportedMessage = optNullableString(json, "min_supported_message"),
                killSwitch = json.optBoolean("kill_switch", false),
                killSwitchMessage = optNullableString(json, "kill_switch_message")
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

    /** Android's bundled org.json has a sharp edge: `optString(name, "")`
     *  does NOT return "" when the JSON value is an explicit `null` — it
     *  returns the literal four-character string "null", because
     *  JSONObject.NULL's own toString() gets hit first. The admin panel's
     *  "clear this field" path genuinely writes SQL NULL (not ""), and the
     *  server faithfully re-emits that as JSON null — so without this guard,
     *  clearing the min-supported-update or kill-switch message in the admin
     *  panel made every forced-update / kill-switch dialog show the word
     *  "null" instead of the intended default text. */
    private fun optNullableString(json: JSONObject, name: String): String? {
        if (!json.has(name) || json.isNull(name)) return null
        return json.optString(name, "").ifEmpty { null }
    }
}
