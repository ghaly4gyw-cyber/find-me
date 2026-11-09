package com.ghaly.findme.mesh

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * Periodically gets a GPS fix and hands it to [onLocation]. Interval is deliberately
 * not too aggressive (30s) to save battery — the MVP plan calls for battery-aware
 * beacon frequency later, this is the simple fixed-interval version.
 */
class LocationTracker(
    private val context: Context,
    // accuracyM is the fix's horizontal accuracy radius in meters, straight from
    // Android (Location.getAccuracy()) — lets the UI show how much to trust a
    // fix instead of just plotting a dot with no sense of how fuzzy it is.
    private val onLocation: (lat: Double, lon: Double, accuracyM: Float) -> Unit
) {
    private val client: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private var callback: LocationCallback? = null

    @SuppressLint("MissingPermission") // caller must have already checked ACCESS_FINE_LOCATION
    fun start(intervalMs: Long = 30_000L) {
        if (callback != null) return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .build()
        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                onLocation(loc.latitude, loc.longitude, loc.accuracy)
            }
        }
        callback = cb
        client.requestLocationUpdates(request, cb, Looper.getMainLooper())
    }

    fun stop() {
        callback?.let { client.removeLocationUpdates(it) }
        callback = null
    }
}
