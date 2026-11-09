package com.ghaly.findme.mesh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ghaly.findme.R
import com.ghaly.findme.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps the mesh (advertising/discovery/connections) and GPS tracking alive even
 * when the app isn't in the foreground — important for a device that's just
 * sitting in someone's pocket relaying other people's messages.
 */
class MeshForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "find_me_mesh"
        private const val FALL_CHANNEL_ID = "find_me_fall"
        private const val WEATHER_CHANNEL_ID = "find_me_weather"
        private const val SOS_RECEIVED_CHANNEL_ID = "find_me_sos_received"
        private const val NOTIFICATION_ID = 1001
        private const val FALL_NOTIFICATION_ID = 1002
        private const val WEATHER_NOTIFICATION_ID = 1004
        private const val SOS_RECEIVED_NOTIFICATION_ID = 1007
        private const val WATCHDOG_INTERVAL_MS = 60_000L
        private const val RING_DURATION_MS = 15_000L
        private const val INACTIVITY_THRESHOLD_MS = 20 * 60_000L // 20 minutes of no touches

        fun start(context: Context) {
            val intent = Intent(context, MeshForegroundService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MeshForegroundService::class.java))
        }
    }

    inner class LocalBinder : Binder() {
        fun getMesh(): MeshManager = mesh
    }

    private val binder = LocalBinder()
    lateinit var mesh: MeshManager
        private set
    private lateinit var locationTracker: LocationTracker
    private lateinit var fallDetector: FallDetector
    private lateinit var pressureWatcher: PressureWatcher
    private val ringSignal: PhysicalSignal by lazy { PhysicalSignal(applicationContext) }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob)
    private var watchdogJob: Job? = null
    private var ringStopJob: Job? = null

    private var currentIntervalMs = 30_000L
    private var inactiveAlertSent = false
    private var lowBatteryBeaconSent = false

    /** Catches the OS's own low-battery threshold (~15% on most Android
     *  builds) to push one extra, out-of-cycle position beacon right away —
     *  the freshest possible "last known good" fix before things get more
     *  critical — and resets once the phone is charged back up, so it can
     *  fire again on the next depletion. This is the real, honest version of
     *  a "dying battery beacon": it can't promise to catch the literal last
     *  moment before a phone dies (Android gives no reliable callback for
     *  that), but it does react to the one system signal that genuinely
     *  exists for this. */
    private val batteryStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_BATTERY_LOW -> {
                    if (lowBatteryBeaconSent) return
                    lowBatteryBeaconSent = true
                    val loc = mesh.myLocation() ?: return
                    mesh.sendPosition(loc.first, loc.second, currentBatteryPct())
                }
                Intent.ACTION_BATTERY_OKAY -> {
                    lowBatteryBeaconSent = false
                }
            }
        }
    }

    /** Handles the "I'm OK" action button on the buddy check-in prompt. A
     *  notification action tap never reaches an Activity, so this is the
     *  only way to hear about it. It's our own app-internal action, sent
     *  only by our own PendingIntent, so RECEIVER_NOT_EXPORTED — unlike the
     *  battery receiver above, this one isn't a system broadcast and needs
     *  the flag explicitly on API 33+. */
    private val checkInAckReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BuddyCheckIn.ACTION_ACK) {
                BuddyCheckIn.acknowledge(applicationContext)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        com.ghaly.findme.repo.PeerRepository.init(applicationContext)
        com.ghaly.findme.repo.ProfileRepository.init(applicationContext)
        com.ghaly.findme.repo.SavedPlaceRepository.init(applicationContext)
        com.ghaly.findme.repo.SosRepository.init(applicationContext)
        com.ghaly.findme.repo.InactivityRepository.init(applicationContext)
        com.ghaly.findme.repo.PostRepository.init(applicationContext)
        com.ghaly.findme.repo.MarketRepository.init(applicationContext)
        com.ghaly.findme.repo.TrailRepository.init(applicationContext)
        mesh = MeshManager(applicationContext)
        mesh.onVoiceClip = { bytes, senderName ->
            VoicePlayer.play(applicationContext, bytes)
            val fallback = applicationContext.getString(com.ghaly.findme.R.string.radio_unknown_sender)
            com.ghaly.findme.repo.RadioLogRepository.record(senderName ?: fallback, mine = false)
        }
        // Someone nearby wants this phone to make noise so it can be found —
        // run the same light+sound signal the SOS tab offers, but only for a
        // short burst, and restart the countdown on every re-trigger rather
        // than stopping early if a second ring arrives mid-burst.
        mesh.onRingReceived = {
            ringSignal.start()
            ringStopJob?.cancel()
            ringStopJob = serviceScope.launch {
                delay(RING_DURATION_MS)
                ringSignal.stop()
            }
        }
        mesh.onSosReceived = { senderName -> notifySosReceived(senderName) }
        locationTracker = LocationTracker(applicationContext) { lat, lon ->
            mesh.sendPosition(lat, lon, currentBatteryPct())
            com.ghaly.findme.repo.TrailRepository.record(lat, lon)
        }
        fallDetector = FallDetector(applicationContext) { onPossibleFallDetected() }
        pressureWatcher = PressureWatcher(applicationContext) { dropHpa, minutes ->
            notifyPressureDrop(dropHpa, minutes)
        }
        registerReceiver(
            batteryStateReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_BATTERY_LOW)
                addAction(Intent.ACTION_BATTERY_OKAY)
            }
        )
        ContextCompat.registerReceiver(
            this,
            checkInAckReceiver,
            IntentFilter(BuddyCheckIn.ACTION_ACK),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        mesh.start()
        locationTracker.start(currentIntervalMs)
        fallDetector.start()
        pressureWatcher.start()
        startWatchdog()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        watchdogJob?.cancel()
        ringStopJob?.cancel()
        serviceJob.cancel()
        locationTracker.stop()
        fallDetector.stop()
        pressureWatcher.stop()
        ringSignal.stop()
        try {
            unregisterReceiver(batteryStateReceiver)
        } catch (e: IllegalArgumentException) {
            // was never registered (e.g. onCreate didn't fully complete) — fine
        }
        try {
            unregisterReceiver(checkInAckReceiver)
        } catch (e: IllegalArgumentException) {
            // was never registered (e.g. onCreate didn't fully complete) — fine
        }
        mesh.stop()
        super.onDestroy()
    }

    /** One periodic loop: adjusts GPS beacon frequency to battery level, and runs the
     *  dead-man's-switch check (no user interaction for a long time -> alert the mesh). */
    private fun startWatchdog() {
        if (watchdogJob != null) return
        watchdogJob = serviceScope.launch {
            while (true) {
                adjustBeaconFrequency()
                checkInactivity()
                SmsBridge.maybeAutoSend(applicationContext, mesh.myLocation())
                BuddyCheckIn.tick(applicationContext) { raiseInactiveAlert() }
                delay(WATCHDOG_INTERVAL_MS)
            }
        }
    }

    private fun currentBatteryPct(): Int {
        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        return batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
    }

    private fun adjustBeaconFrequency() {
        val pct = currentBatteryPct()
        val desiredIntervalMs = when {
            pct >= 50 -> 30_000L
            pct >= 20 -> 60_000L
            else -> 120_000L
        }
        if (desiredIntervalMs != currentIntervalMs) {
            currentIntervalMs = desiredIntervalMs
            locationTracker.stop()
            locationTracker.start(currentIntervalMs)
        }
    }

    private fun checkInactivity() {
        val idleMs = ActivityWatcher.idleMs()
        if (idleMs >= INACTIVITY_THRESHOLD_MS) {
            raiseInactiveAlert()
        } else if (inactiveAlertSent) {
            inactiveAlertSent = false
            mesh.clearInactive()
        }
    }

    private fun raiseInactiveAlert() {
        if (inactiveAlertSent) return
        inactiveAlertSent = true
        val loc = mesh.myLocation()
        mesh.sendInactive(loc?.first ?: 0.0, loc?.second ?: 0.0)
    }

    /** The accelerometer heuristic thinks this phone just took a fall. Raise the
     *  same "may be incapacitated" alert the 20-minute idle timer would — no
     *  need to wait that long when there's already a strong physical signal —
     *  and post a one-off notification so the person can see why, and clear it
     *  with a single touch to the phone if they're actually fine. */
    private fun onPossibleFallDetected() {
        raiseInactiveAlert()
        notifyPossibleFall()
    }

    private fun notifyPossibleFall() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                FALL_CHANNEL_ID,
                getString(R.string.fall_notification_channel),
                NotificationManager.IMPORTANCE_HIGH
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
        val openAppIntent = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, FALL_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.fall_notification_title))
            .setContentText(getString(R.string.fall_notification_text))
            .setContentIntent(openAppIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(FALL_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted — the mesh alert itself still went
            // out to teammates, this notification is just a local nice-to-have.
        }
    }

    /** The loud, impossible-to-miss alert for someone ELSE's SOS — a quiet
     *  new row in the SOS tab's list isn't enough if nobody happens to have
     *  that tab open. High-priority channel with its own vibration pattern
     *  and alarm category, so it pops up and gets noticed even with the
     *  phone in someone's pocket, the same urgency the fall alert already
     *  gets for your own phone. */
    private fun notifySosReceived(senderName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                SOS_RECEIVED_CHANNEL_ID,
                getString(R.string.sos_received_notification_channel),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 200, 400, 200, 400)
                enableLights(true)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
        val openAppIntent = PendingIntent.getActivity(
            this, 6, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, SOS_RECEIVED_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.sos_received_notification_title))
            .setContentText(getString(R.string.sos_received_notification_text_fmt, senderName))
            .setContentIntent(openAppIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVibrate(longArrayOf(0, 400, 200, 400, 200, 400))
            .setAutoCancel(true)
            .build()
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(SOS_RECEIVED_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted — the SOS itself still landed in
            // the SOS tab, this notification is just what would have pushed it
            // in front of the person right away.
        }
    }

    /** The barometer (if this phone has one) just saw pressure fall faster
     *  than normal over a short window — a real, well-known early sign of a
     *  storm front or strong wind approaching, not a weather forecast. Kept
     *  deliberately hedged: it only ever reports what the sensor measured,
     *  never a prediction of what kind of weather is coming or when. */
    private fun notifyPressureDrop(dropHpa: Float, overMinutes: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                WEATHER_CHANNEL_ID,
                getString(R.string.weather_notification_channel),
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
        val openAppIntent = PendingIntent.getActivity(
            this, 3, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, WEATHER_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.weather_notification_title))
            .setContentText(getString(R.string.weather_notification_text_fmt, dropHpa, overMinutes))
            .setContentIntent(openAppIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(WEATHER_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted — nothing else to fall back to.
        }
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val openAppIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.mesh_status_on))
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
