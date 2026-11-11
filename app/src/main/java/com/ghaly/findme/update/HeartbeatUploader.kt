package com.ghaly.findme.update

import android.content.Context

/**
 * Throttled "I'm online right now" ping (see heartbeat.php / OnlineMessenger)
 * — same silent, best-effort, offline-safe shape as every other Uploader in
 * this package, just on a much shorter interval, since presence needs to
 * stay fresh for contacts-status.php's 3-minute online window to mean
 * anything. Wired into the same periodic tick as the other opportunistic
 * uploaders (see MainActivity.maybeSyncAccountSecurity).
 */
object HeartbeatUploader {
    private const val PREFS = "heartbeat_uploader"
    private const val KEY_LAST_SENT_AT = "last_sent_at"
    private const val MIN_INTERVAL_MS = 90 * 1000L

    private fun dueForHeartbeat(context: Context): Boolean {
        val last = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_SENT_AT, 0L)
        return System.currentTimeMillis() - last >= MIN_INTERVAL_MS
    }

    /** Call off the main thread. */
    fun sendIfDue(context: Context, phone: String, deviceId: String) {
        if (!dueForHeartbeat(context)) return
        OnlineMessenger.heartbeat(context, phone, deviceId)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAST_SENT_AT, System.currentTimeMillis())
            .apply()
    }
}
