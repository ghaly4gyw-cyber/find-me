package com.ghaly.findme.mesh

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * A simple "weather is changing fast" watcher using the barometric pressure
 * sensor some phones already have — no internet, no forecast, just the raw
 * sensor reading. A rapid drop in atmospheric pressure over a short window
 * is a real, well-known early sign of an approaching storm front or strong
 * wind — the same basic idea ("the glass is falling") mariners and
 * meteorologists have relied on for a long time, well before any forecast
 * would catch up to it.
 *
 * Deliberately narrow in what it claims: this only ever reports "pressure
 * fell by this much over this long," never a prediction of what weather is
 * coming or when. On a phone with no barometer (common on budget devices)
 * [hasSensor] is simply false and this does nothing — same graceful
 * fallback already used for the compass's rotation-vector sensor.
 */
class PressureWatcher(
    context: Context,
    private val onRapidDrop: (dropHpa: Float, overMinutes: Int) -> Unit
) : SensorEventListener {

    companion object {
        /** Roughly how far back to keep comparing against. */
        private const val WINDOW_MS = 30 * 60_000L
        /** A fall of at least this many hPa within the window is the trigger.
         *  Meteorologists treat "3 hPa in 3 hours" as a rapid pressure fall;
         *  scaled down for this shorter, more responsive window that's still
         *  comfortably above normal day-to-day drift. */
        private const val DROP_THRESHOLD_HPA = 1.5f
        private const val RETRIGGER_COOLDOWN_MS = 60 * 60_000L // at most once an hour
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val pressureSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PRESSURE)

    private data class Sample(val ts: Long, val hPa: Float)
    private val samples = ArrayDeque<Sample>()
    private var lastTriggerMs = 0L

    /** True only if this phone actually reports having a barometer. */
    val hasSensor: Boolean get() = pressureSensor != null

    fun start() {
        val sensor = pressureSensor ?: return
        sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val now = System.currentTimeMillis()
        samples.addLast(Sample(now, event.values[0]))
        while (samples.isNotEmpty() && now - samples.first().ts > WINDOW_MS) {
            samples.removeFirst()
        }
        val oldest = samples.firstOrNull() ?: return
        val newest = samples.last()
        val drop = oldest.hPa - newest.hPa
        if (drop >= DROP_THRESHOLD_HPA && now - lastTriggerMs > RETRIGGER_COOLDOWN_MS) {
            lastTriggerMs = now
            val minutes = ((newest.ts - oldest.ts) / 60_000L).toInt().coerceAtLeast(1)
            onRapidDrop(drop, minutes)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
