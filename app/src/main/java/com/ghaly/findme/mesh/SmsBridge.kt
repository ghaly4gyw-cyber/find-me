package com.ghaly.findme.mesh

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.ghaly.findme.repo.InactivityRepository
import com.ghaly.findme.repo.PeerRepository
import com.ghaly.findme.repo.SosRepository
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Opportunistic bridge to the outside world. The Nearby Connections mesh has
 * no internet and never will on this budget, but a phone that wanders back
 * into ordinary cell coverage can usually still send an SMS even with zero
 * mobile data — SMS rides the control channel, not the data channel. So
 * when that happens, this fires a short text digest of the group's
 * last-known positions and any active SOS / inactivity alerts to one
 * pre-set contact (a relative, a cooperative office, anyone who can act on
 * it or call for help). That's the one thing the mesh itself can never do:
 * reach someone outside physical Bluetooth/Wi-Fi range.
 *
 * Deliberately simple and best-effort: there's no delivery confirmation,
 * and a send attempt with no signal at all just fails quietly — Android's
 * SmsManager doesn't crash or block waiting for coverage. No backend is
 * needed for this; it's the whole feature, not a stand-in for one.
 */
object SmsBridge {
    private const val PREFS = "find_me_sms_bridge"
    private const val KEY_CONTACT = "contact_phone"
    private const val KEY_AUTO = "auto_send_on_sos"
    private const val KEY_LAST_SENT = "last_sent_at"
    private val AUTO_THROTTLE_MS = TimeUnit.MINUTES.toMillis(10)

    fun contact(context: Context): String? =
        prefs(context).getString(KEY_CONTACT, null)?.ifEmpty { null }

    fun setContact(context: Context, phone: String?) {
        prefs(context).edit().putString(KEY_CONTACT, phone?.trim().orEmpty()).apply()
    }

    fun autoSendEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO, true)

    fun setAutoSendEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO, enabled).apply()
    }

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Called from the background watchdog on every tick: only actually sends if
     *  there's a contact configured, auto-send is on, something in the mesh is
     *  actually worth reporting (an active SOS or inactivity alert, mine or
     *  anyone else's), and we haven't just tried a few minutes ago — so a phone
     *  that briefly touches a weak signal doesn't fire off a dozen texts. */
    fun maybeAutoSend(context: Context) {
        if (!autoSendEnabled(context)) return
        val hasUrgent = SosRepository.alerts.value.values.any { it.active } ||
            InactivityRepository.alerts.value.values.any { it.active }
        if (!hasUrgent) return
        sendNow(context, bypassThrottle = false)
    }

    /** Attempts to send right now. [bypassThrottle] = true for a user-initiated tap
     *  or a just-raised SOS, where we want the attempt regardless of the last try.
     *  Returns false immediately (no attempt made) if nothing is configured, the
     *  permission isn't granted, or the throttle hasn't elapsed. */
    fun sendNow(context: Context, bypassThrottle: Boolean = true): Boolean {
        val phone = contact(context) ?: return false
        if (!hasPermission(context)) return false
        if (!bypassThrottle) {
            val lastSent = prefs(context).getLong(KEY_LAST_SENT, 0L)
            if (System.currentTimeMillis() - lastSent < AUTO_THROTTLE_MS) return false
        }
        return try {
            val manager = smsManager(context)
            val text = buildDigest()
            val parts = manager.divideMessage(text)
            manager.sendMultipartTextMessage(phone, null, parts, null, null)
            prefs(context).edit().putLong(KEY_LAST_SENT, System.currentTimeMillis()).apply()
            true
        } catch (e: Exception) {
            // No signal, airplane mode, no SIM, blocked by the OEM — any of these is
            // exactly the "opportunistic, best-effort" case this feature expects.
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun smsManager(context: Context): SmsManager =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            SmsManager.getDefault()
        }

    private fun buildDigest(): String {
        val sb = StringBuilder("Find Me status:")
        for (a in SosRepository.alerts.value.values.filter { it.active }.take(5)) {
            sb.append("\nSOS ").append(a.senderName).append(' ').append(coord(a.lat, a.lon))
        }
        for (a in InactivityRepository.alerts.value.values.filter { it.active }.take(5)) {
            sb.append("\nINACTIVE ").append(a.senderName).append(' ').append(coord(a.lat, a.lon))
        }
        val peers = PeerRepository.peers.value.values.sortedByDescending { it.ts }.take(6)
        for (p in peers) {
            sb.append("\n").append(p.name).append(' ').append(coord(p.lat, p.lon))
        }
        return sb.toString()
    }

    private fun coord(lat: Double, lon: Double): String =
        String.format(Locale.US, "%.4f,%.4f", lat, lon)
}
