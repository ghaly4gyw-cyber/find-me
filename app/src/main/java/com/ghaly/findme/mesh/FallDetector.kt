package com.ghaly.findme.mesh

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * Very simple phone-only fall detector — no wearable, no dedicated fall-detection
 * chip, just the accelerometer every phone already has. Heuristic (the same
 * basic shape smartwatches used before their more elaborate ML models): a brief
 * near-weightless dip in total acceleration (the phone free-falling) followed
 * within a short window by a sharp impact spike.
 *
 * Deliberately a little conservative about firing: a false alert from a phone
 * being set down hard, or tossed onto a car seat, is an annoyance that erodes
 * trust in the app; a missed real fall is worse. This is meant to catch an
 * actual fall-and-impact, not every jolt. It is NOT a clinically validated
 * fall-detection algorithm — there's no budget here for dedicated hardware or
 * real-device testing, just a reasonable, well-known heuristic layered on top
 * of the existing dead-man's-switch (which still catches the slower case: no
 * fall at all, just someone who stops moving).
 */
class FallDetector(
    context: Context,
    private val onFallDetected: () -> Unit
) : SensorEventListener {

    companion object {
        private const val FREE_FALL_THRESHOLD = 2.5f // m/s^2, near weightlessness
        private const val IMPACT_THRESHOLD = 22f // m/s^2, a hard knock
        private const val IMPACT_WINDOW_MS = 1200L // impact must follow the dip within this long
        private const val RETRIGGER_COOLDOWN_MS = 60_000L // don't fire again for a minute
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var freeFallAtMs: Long = 0L
    private var lastTriggerMs: Long = 0L

    fun start() {
        val sensor = accelerometer ?: return
        sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val magnitude = sqrt(x * x + y * y + z * z)
        val now = System.currentTimeMillis()

        if (magnitude < FREE_FALL_THRESHOLD) {
            freeFallAtMs = now
            return
        }

        if (magnitude > IMPACT_THRESHOLD &&
            freeFallAtMs != 0L &&
            now - freeFallAtMs <= IMPACT_WINDOW_MS &&
            now - lastTriggerMs > RETRIGGER_COOLDOWN_MS
        ) {
            lastTriggerMs = now
            freeFallAtMs = 0L
            onFallDetected()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
