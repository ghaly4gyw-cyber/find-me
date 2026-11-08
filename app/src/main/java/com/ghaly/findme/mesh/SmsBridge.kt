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
    private val URGENT_RESEND_MS = TimeUnit.MINUTES.toMillis(10)
    private val ROUTINE_RESEND_MS = TimeUnit.MINUTES.toMillis(60)

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

    /** Called from the background watchdog on every tick. This is deliberately
     *  NOT limited to emergencies: the whole point of this bridge is that
     *  someone's last-known position reaches someone outside the mesh the
     *  moment any signal shows up at all — not only after something has
     *  already gone wrong. So it fires a routine status text on a long
     *  interval regardless of alerts, and a much shorter one whenever an SOS
     *  or inactivity alert is active, so a phone that briefly touches a weak
     *  signal doesn't fire off a dozen texts but also never just stays quiet
     *  because nothing urgent happened yet. */
    fun maybeAutoSend(context: Context, myLocation: Pair<Double, Double>?) {
        if (!autoSendEnabled(context)) return
        if (contact(context) == null) return
        val hasUrgent = SosRepository.alerts.value.values.any { it.active } ||
            InactivityRepository.alerts.value.values.any { it.active }
        val resendInterval = if (hasUrgent) URGENT_RESEND_MS else ROUTINE_RESEND_MS
        val lastSent = prefs(context).getLong(KEY_LAST_SENT, 0L)
        if (System.currentTimeMillis() - lastSent < resendInterval) return
        sendNow(context, myLocation)
    }

    /** Attempts to send right now, with no throttle of its own — callers that
     *  want throttling (the background watchdog, via [maybeAutoSend]) do that
     *  check themselves first. A user tapping "Send status now" or hitting SOS
     *  should always get an attempt. Returns false immediately (no attempt
     *  made) if nothing is configured or the permission isn't granted.
     *
     *  [myLocation] is THIS phone's own last GPS fix. If this phone is the one
     *  that's isolated, its own position is the single most important line in
     *  the text — it must never be left out just because this device's
     *  broadcast of its own position never gets routed back into the peer
     *  roster (a device doesn't relay its own envelope to itself). */
    fun sendNow(context: Context, myLocation: Pair<Double, Double>? = null): Boolean {
        val phone = contact(context) ?: return false
        if (!hasPermission(context)) return false
        return try {
            val manager = smsManager(context)
            val text = buildDigest(com.ghaly.findme.mesh.DeviceIdentity.name(context), myLocation)
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

    private fun buildDigest(myName: String, myLocation: Pair<Double, Double>?): String {
        val sb = StringBuilder("Find Me status:")
        if (myLocation != null) {
            sb.append("\nME ").append(myName).append(' ').append(coord(myLocation.first, myLocation.second))
        }
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
