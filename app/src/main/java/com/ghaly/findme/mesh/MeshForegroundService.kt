package com.ghaly.findme.mesh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
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
        private const val NOTIFICATION_ID = 1001
        private const val WATCHDOG_INTERVAL_MS = 60_000L
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

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob)
    private var watchdogJob: Job? = null

    private var currentIntervalMs = 30_000L
    private var inactiveAlertSent = false

    override fun onCreate() {
        super.onCreate()
        com.ghaly.findme.repo.PeerRepository.init(applicationContext)
        com.ghaly.findme.repo.ProfileRepository.init(applicationContext)
        mesh = MeshManager(applicationContext)
        mesh.onVoiceClip = { bytes -> VoicePlayer.play(applicationContext, bytes) }
        locationTracker = LocationTracker(applicationContext) { lat, lon ->
            mesh.sendPosition(lat, lon)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        mesh.start()
        locationTracker.start(currentIntervalMs)
        startWatchdog()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        watchdogJob?.cancel()
        serviceJob.cancel()
        locationTracker.stop()
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
                SmsBridge.maybeAutoSend(applicationContext)
                delay(WATCHDOG_INTERVAL_MS)
            }
        }
    }

    private fun adjustBeaconFrequency() {
        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val pct = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
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
            if (!inactiveAlertSent) {
                inactiveAlertSent = true
                val loc = mesh.myLocation()
                mesh.sendInactive(loc?.first ?: 0.0, loc?.second ?: 0.0)
            }
        } else if (inactiveAlertSent) {
            inactiveAlertSent = false
            mesh.clearInactive()
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
