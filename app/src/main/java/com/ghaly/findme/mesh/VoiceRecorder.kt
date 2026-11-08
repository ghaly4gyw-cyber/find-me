package com.ghaly.findme.mesh

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * Push-to-talk recorder: start() on button-down, stop() on button-up returns the
 * recorded clip's bytes (small AAC/3GP file — a few seconds of talk is only a few
 * tens of KB, cheap to send over a direct Bluetooth/Wi-Fi link).
 */
class VoiceRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    @SuppressLint("MissingPermission") // caller must have already checked RECORD_AUDIO
    fun start(): Boolean {
        if (recorder != null) return false
        val file = File(context.cacheDir, "ptt_${System.currentTimeMillis()}.3gp")
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            outputFile = file
            true
        } catch (e: Exception) {
            r.release()
            false
        }
    }

    /** Returns the recorded clip's bytes, or null if nothing was recording / it was too short. */
    fun stop(): ByteArray? {
        val r = recorder ?: return null
        recorder = null
        val file = outputFile
        outputFile = null
        return try {
            r.stop()
            r.release()
            val bytes = file?.readBytes()
            file?.delete()
            if (bytes != null && bytes.size > 200) bytes else null
        } catch (e: Exception) {
            r.release()
            null
        }
    }

    fun cancel() {
        val r = recorder ?: return
        recorder = null
        try {
            r.stop()
        } catch (e: Exception) {
            // ignore — happens if stopped too soon after start
        }
        r.release()
        outputFile?.delete()
        outputFile = null
    }
}
