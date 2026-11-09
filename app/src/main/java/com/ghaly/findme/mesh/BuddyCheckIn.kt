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
 * The 20-minute no-touch watchdog (see MeshForegroundService.checkInactivity)
 * only catches someone who has stopped touching their phone entirely. It
 * can't tell "resting in the shade, phone face-down" from "actually in
 * trouble" — both look identical to a touch sensor. A buddy check-in asks
 * instead of guessing: on a chosen interval it posts a plain "You OK?"
 * notification with a one-tap "I'm OK" action. A tap resets the clock and
 * nothing happens. No tap within the grace window raises the exact same
 * mesh alert the no-touch watchdog would — reusing that one proven signal
 * path rather than inventing a second kind of alert for teammates to learn.
 *
 * Off by default; the person chooses whether to turn it on and how often,
 * the same pattern as the hydration reminder.
 */
object BuddyCheckIn {
    private const val PREFS = "find_me_buddy_checkin"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_INTERVAL_MIN = "interval_minutes"
    private const val KEY_LAST_PROMPT_AT = "last_prompt_at"
    private const val KEY_PENDING_SINCE = "pending_since"
    private const val DEFAULT_INTERVAL_MIN = 90

    /** How long someone has to tap "I'm OK" after a prompt before it's
     *  treated the same as the no-touch watchdog firing. Fixed, not a
     *  setting — it only has to be clearly shorter than the prompt interval
     *  itself, and a shared, well-understood number is easier for a team to
     *  reason about than a second tunable. */
    private const val GRACE_MS = 10 * 60_000L

    const val CHANNEL_ID = "find_me_buddy_checkin"
    const val NOTIFICATION_ID = 1006
    const val ACTION_ACK = "com.ghaly.findme.ACTION_CHECKIN_ACK"

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) {
            // Start the clock from now, same reasoning as hydration: an old
            // stored timestamp from the last time this was on shouldn't fire
            // a prompt the moment it's switched back on.
            prefs(context).edit()
                .putLong(KEY_LAST_PROMPT_AT, System.currentTimeMillis())
                .putLong(KEY_PENDING_SINCE, 0L)
                .apply()
        } else {
            prefs(context).edit().putLong(KEY_PENDING_SINCE, 0L).apply()
            cancelNotification(context)
        }
    }

    fun intervalMinutes(context: Context): Int = prefs(context).getInt(KEY_INTERVAL_MIN, DEFAULT_INTERVAL_MIN)

    fun setIntervalMinutes(context: Context, minutes: Int) {
        prefs(context).edit().putInt(KEY_INTERVAL_MIN, minutes).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Called from the foreground service's existing 60s watchdog tick — same
     *  wiring as the hydration reminder, no separate alarm infrastructure.
     *  [onOverdue] is how the caller raises the real mesh alert; this object
     *  only decides timing, it never touches the mesh directly. */
    fun tick(context: Context, onOverdue: () -> Unit) {
        if (!enabled(context)) return
        val now = System.currentTimeMillis()
        val pendingSince = prefs(context).getLong(KEY_PENDING_SINCE, 0L)
        if (pendingSince > 0L) {
            if (now - pendingSince >= GRACE_MS) {
                // Missed it. Fold straight back into "waiting for the next
                // interval" rather than re-firing onOverdue every tick —
                // the mesh alert itself is already a standing, visible
                // state (same InactivityRepository entry the no-touch
                // watchdog uses), so there's nothing more to escalate here.
                prefs(context).edit()
                    .putLong(KEY_PENDING_SINCE, 0L)
                    .putLong(KEY_LAST_PROMPT_AT, now)
                    .apply()
                cancelNotification(context)
                onOverdue()
            }
            return
        }
        val lastAt = prefs(context).getLong(KEY_LAST_PROMPT_AT, now)
        val intervalMs = TimeUnit.MINUTES.toMillis(intervalMinutes(context).toLong())
        if (now - lastAt >= intervalMs) {
            prefs(context).edit().putLong(KEY_PENDING_SINCE, now).apply()
            notifyPrompt(context)
        }
    }

    /** Called when the "I'm OK" notification action fires. Any touch already
     *  pokes [ActivityWatcher] app-wide, but a notification-action tap never
     *  reaches the app's own UI, so it has to be poked explicitly here too —
     *  otherwise the no-touch watchdog could still fire later even though
     *  the person just actively confirmed they're fine. */
    fun acknowledge(context: Context) {
        prefs(context).edit()
            .putLong(KEY_PENDING_SINCE, 0L)
            .putLong(KEY_LAST_PROMPT_AT, System.currentTimeMillis())
            .apply()
        ActivityWatcher.poke()
        cancelNotification(context)
    }

    private fun cancelNotification(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager?.cancel(NOTIFICATION_ID)
    }

    private fun notifyPrompt(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.buddy_checkin_notification_channel),
                NotificationManager.IMPORTANCE_HIGH
            )
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
        val openAppIntent = PendingIntent.getActivity(
            context, 4, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val ackIntent = PendingIntent.getBroadcast(
            context, 5, Intent(ACTION_ACK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.buddy_checkin_notification_title))
            .setContentText(context.getString(R.string.buddy_checkin_notification_text))
            .setContentIntent(openAppIntent)
            .addAction(0, context.getString(R.string.buddy_checkin_im_ok), ackIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        try {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted. The mesh alert still fires if
            // nothing happens within the grace window — the person just
            // won't see the prompt that would have let them head it off.
        }
    }
}
