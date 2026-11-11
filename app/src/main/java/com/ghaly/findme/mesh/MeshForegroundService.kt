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
import android.location.Location
import android.os.BatteryManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
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
        private const val WEATHER_CHANNEL_ID = "find_me_weather"
        private const val SOS_RECEIVED_CHANNEL_ID = "find_me_sos_received"
        private const val LINK_CHANNEL_ID = "find_me_link"
        private const val NOTIFICATION_ID = 1001
        private const val WEATHER_NOTIFICATION_ID = 1004
        private const val SOS_RECEIVED_NOTIFICATION_ID = 1007
        private const val LINK_REQUEST_NOTIFICATION_ID = 1008
        private const val LINK_RESPONSE_NOTIFICATION_ID = 1009
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
    private lateinit var pressureWatcher: PressureWatcher
    private val ringSignal: PhysicalSignal by lazy { PhysicalSignal(applicationContext) }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob)
    private var watchdogJob: Job? = null
    private var ringStopJob: Job? = null

    private var currentIntervalMs = 30_000L
    private var inactiveAlertSent = false
    private var lowBatteryBeaconSent = false
    // Set while the map screen is actually visible — see onLiveLocationBoost above.
    private var liveLocationBoostActive = false

    // ---- stillness-aware beacon backoff ----
    // The GPS chip is what actually drains the battery here, and it's driven
    // entirely by how often we ask for a fix — not by anything else in this
    // service. Someone who's stopped moving (resting, working a spot, asleep)
    // doesn't need a fresh fix every 30-120s: their position hasn't changed,
    // so there's nothing new to tell anyone. This stretches the interval
    // further the longer consecutive fixes land within a small radius of each
    // other, and snaps straight back to the normal battery-based cadence the
    // moment a fix shows real movement — so responsiveness while actually
    // moving is unaffected, and nobody's "last known position" goes stale
    // while they're genuinely not moving (it's still accurate; it just isn't
    // re-confirmed as often).
    private var lastStillFixLat: Double? = null
    private var lastStillFixLon: Double? = null
    private var consecutiveStillFixes = 0
    private val STILL_RADIUS_M = 15f
    private val MAX_STILL_MULTIPLIER = 4L

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
        com.ghaly.findme.repo.LinkRepository.init(applicationContext)
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
        mesh.onLinkRequestReceived = { fromName -> notifyLinkRequest(fromName) }
        mesh.onLinkResponseReceived = { peerName, accepted -> if (accepted) notifyLinkAccepted(peerName) }
        mesh.onLiveLocationBoost = { active -> setLiveLocationBoost(active) }
        locationTracker = LocationTracker(applicationContext) { lat, lon, accuracyM ->
            mesh.sendPosition(lat, lon, currentBatteryPct())
            mesh.updateMyAccuracy(accuracyM)
            com.ghaly.findme.repo.TrailRepository.record(lat, lon)
            noteFixForStillness(lat, lon)
        }
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
        // The map screen has its own, faster cadence in effect right now —
        // don't let this periodic battery-based check fight it every minute.
        if (liveLocationBoostActive) return
        val desiredIntervalMs = batteryBasedIntervalMs() * stillnessMultiplier()
        if (desiredIntervalMs != currentIntervalMs) {
            currentIntervalMs = desiredIntervalMs
            locationTracker.stop()
            locationTracker.start(currentIntervalMs)
        }
    }

    private fun batteryBasedIntervalMs(): Long {
        // Battery Saver is the user explicitly telling the OS (and every app)
        // to conserve power right now — treat that the same as a low battery,
        // regardless of the actual percentage left.
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (powerManager?.isPowerSaveMode == true) return 120_000L
        val pct = currentBatteryPct()
        return when {
            pct >= 50 -> 30_000L
            pct >= 20 -> 60_000L
            else -> 120_000L
        }
    }

    /** 1 when the last fix showed movement, rising to [MAX_STILL_MULTIPLIER]
     *  the longer consecutive fixes land within [STILL_RADIUS_M] of each
     *  other — multiplies the battery-based interval above. */
    private fun stillnessMultiplier(): Long = (1L + consecutiveStillFixes).coerceAtMost(MAX_STILL_MULTIPLIER)

    private fun noteFixForStillness(lat: Double, lon: Double) {
        val prevLat = lastStillFixLat
        val prevLon = lastStillFixLon
        if (prevLat != null && prevLon != null) {
            val results = FloatArray(1)
            Location.distanceBetween(prevLat, prevLon, lat, lon, results)
            consecutiveStillFixes = if (results[0] <= STILL_RADIUS_M) {
                (consecutiveStillFixes + 1).coerceAtMost((MAX_STILL_MULTIPLIER - 1).toInt())
            } else {
                0
            }
        }
        lastStillFixLat = lat
        lastStillFixLon = lon
    }

    /** Resets the stillness counter so a just-ended live-location boost (or a
     *  jump from a cold GPS fix) doesn't get misread as "hasn't moved" — the
     *  next few fixes re-establish a real baseline before backing off again. */
    private fun resetStillness() {
        consecutiveStillFixes = 0
        lastStillFixLat = null
        lastStillFixLon = null
    }

    /** Called (via [MeshManager.onLiveLocationBoost]) whenever the map screen
     *  becomes visible or hidden. A short fixed interval while someone's
     *  actually looking at the distance/bearing readout makes it feel live;
     *  reverting the moment they leave keeps an idle phone on the normal
     *  battery-aware schedule instead of draining it for a screen no one's
     *  watching. */
    private fun setLiveLocationBoost(active: Boolean) {
        if (liveLocationBoostActive == active) return
        liveLocationBoostActive = active
        resetStillness()
        currentIntervalMs = if (active) 5_000L else batteryBasedIntervalMs()
        locationTracker.stop()
        locationTracker.start(currentIntervalMs)
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

    /** Someone else's phone just asked this one for a mutual location link —
     *  a lower-urgency notification than SOS (default importance, no alarm
     *  vibration pattern), but still a real notification rather than only a
     *  quiet row in the peer list, since the request sits waiting for a
     *  reply and the person may not have the app open at all. */
    private fun notifyLinkRequest(fromName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                LINK_CHANNEL_ID,
                getString(R.string.link_notification_channel),
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
        val openAppIntent = PendingIntent.getActivity(
            this, 7, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, LINK_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.link_request_notification_title))
            .setContentText(getString(R.string.link_request_notification_text_fmt, fromName))
            .setContentIntent(openAppIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(LINK_REQUEST_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted — the request itself still landed
            // (see Peers / the profile dialog), just without this nudge.
        }
    }

    /** A link request this phone sent earlier was just accepted. */
    private fun notifyLinkAccepted(peerName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                LINK_CHANNEL_ID,
                getString(R.string.link_notification_channel),
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
        val openAppIntent = PendingIntent.getActivity(
            this, 8, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, LINK_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.link_accepted_notification_title))
            .setContentText(getString(R.string.link_accepted_notification_text_fmt, peerName))
            .setContentIntent(openAppIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(LINK_RESPONSE_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Same as above — the linked state itself is already saved.
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
