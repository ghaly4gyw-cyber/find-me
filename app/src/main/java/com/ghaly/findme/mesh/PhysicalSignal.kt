package com.ghaly.findme.mesh

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.ToneGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Last-resort, fully offline distress signal: no mesh, no phone network, no
 * backend — just the torch and speaker every phone already has, blinking and
 * beeping the international Morse SOS pattern (··· --- ···) on a loop. For
 * when someone might be close enough to be SEEN or HEARD but is out of
 * Bluetooth/Wi-Fi range, doesn't have the app, or the mesh itself has failed
 * — a search vehicle scanning the dunes at night, or anyone within shouting
 * distance.
 *
 * Torch control via Camera2's setTorchMode does NOT require the CAMERA
 * runtime permission on modern Android — it's a separate, lower-privilege
 * API specifically meant for flashlight toggling, so this needs nothing new
 * declared in the manifest.
 */
class PhysicalSignal(private val context: Context) {

    companion object {
        private const val UNIT_MS = 220L // one Morse "dot" length

        /** true = light/sound on, paired with how many [UNIT_MS] units it lasts. */
        private val PATTERN: List<Pair<Boolean, Int>> = buildSosPattern()

        private fun buildSosPattern(): List<Pair<Boolean, Int>> {
            val seq = mutableListOf<Pair<Boolean, Int>>()
            fun dot() {
                seq += true to 1
                seq += false to 1
            }
            fun dash() {
                seq += true to 3
                seq += false to 1
            }
            fun extendGapToLetterBreak() {
                seq += false to 2 // trailing 1-unit gap + 2 more = a 3-unit letter gap
            }
            repeat(3) { dot() }
            extendGapToLetterBreak()
            repeat(3) { dash() }
            extendGapToLetterBreak()
            repeat(3) { dot() }
            seq += false to 7 // pause before the whole SOS repeats
            return seq
        }
    }

    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
    private val torchCameraId: String? = try {
        cameraManager?.cameraIdList?.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    } catch (e: Exception) {
        null // no camera2 support, or reading characteristics failed — sound-only still works
    }

    val running: Boolean get() = job?.isActive == true

    fun start() {
        if (running) return
        val tone = try {
            ToneGenerator(AudioManager.STREAM_ALARM, 100)
        } catch (e: Exception) {
            null // no audio output available — light-only still works
        }
        job = scope.launch {
            try {
                while (isActive) {
                    for ((on, units) in PATTERN) {
                        val durationMs = UNIT_MS * units
                        setTorch(on)
                        if (on) tone?.startTone(ToneGenerator.TONE_CDMA_PIP, durationMs.toInt())
                        delay(durationMs)
                        if (!isActive) break
                    }
                }
            } finally {
                setTorch(false)
                tone?.release()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        setTorch(false)
    }

    private fun setTorch(on: Boolean) {
        val id = torchCameraId ?: return
        try {
            cameraManager?.setTorchMode(id, on)
        } catch (e: Exception) {
            // torch busy (camera app open elsewhere) or momentarily unavailable —
            // the audible beeping keeps going regardless
        }
    }
}
