package com.ghaly.findme.mesh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ghaly.findme.R
import com.ghaly.findme.ui.MainActivity

/**
 * Keeps the mesh (advertising/discovery/connections) and GPS tracking alive even
 * when the app isn't in the foreground — important for a device that's just
 * sitting in someone's pocket relaying other people's messages.
 */
class MeshForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "find_me_mesh"
        private const val NOTIFICATION_ID = 1001

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

    override fun onCreate() {
        super.onCreate()
        mesh = MeshManager(applicationContext)
        locationTracker = LocationTracker(applicationContext) { lat, lon ->
            mesh.sendPosition(lat, lon)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        mesh.start()
        locationTracker.start()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        locationTracker.stop()
        mesh.stop()
        super.onDestroy()
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
