package com.ghaly.findme.mesh

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.ghaly.findme.R
import com.ghaly.findme.ui.MainActivity
import java.util.concurrent.TimeUnit

/**
 * A plain local timer nudging "drink water / rest in the shade" every so
 * often while the mesh service is running. Nothing to do with GPS, mesh, or
 * anyone else's phone — heat and dehydration are a real risk on their own in
 * the desert, independent of ever getting lost, and it's easy to lose track
 * of time while working. Off by default; the person chooses whether to turn
 * it on and how often, from the 💧 button in the top bar.
 */
object HydrationReminder {
    private const val PREFS = "find_me_hydration"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_INTERVAL_MIN = "interval_minutes"
    private const val KEY_LAST_AT = "last_reminder_at"
    private const val DEFAULT_INTERVAL_MIN = 60

    private const val CHANNEL_ID = "find_me_hydration"
    private const val NOTIFICATION_ID = 1003

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) {
            // Start the clock from now, not from whenever it was last left
            // enabled — otherwise turning it on could fire a reminder
            // immediately if the stored timestamp is old.
            prefs(context).edit().putLong(KEY_LAST_AT, System.currentTimeMillis()).apply()
        }
    }

    fun intervalMinutes(context: Context): Int = prefs(context).getInt(KEY_INTERVAL_MIN, DEFAULT_INTERVAL_MIN)

    fun setIntervalMinutes(context: Context, minutes: Int) {
        prefs(context).edit().putInt(KEY_INTERVAL_MIN, minutes).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Called from the foreground service's existing 60s watchdog tick —
     *  no separate timer/alarm infrastructure needed. */
    fun maybeRemind(context: Context) {
        if (!enabled(context)) return
        val intervalMs = TimeUnit.MINUTES.toMillis(intervalMinutes(context).toLong())
        val lastAt = prefs(context).getLong(KEY_LAST_AT, 0L)
        val now = System.currentTimeMillis()
        if (now - lastAt < intervalMs) return
        prefs(context).edit().putLong(KEY_LAST_AT, now).apply()
        notifyReminder(context)
    }

    private fun notifyReminder(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.hydration_notification_channel),
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
        val openAppIntent = PendingIntent.getActivity(
            context, 2, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.hydration_notification_title))
            .setContentText(context.getString(R.string.hydration_notification_text))
            .setContentIntent(openAppIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        try {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted — unlike the fall alert, there's no
            // mesh signal this is a backup for, so there's nothing else to fall
            // back to; the reminder is simply silent until it's granted.
        }
    }
}
