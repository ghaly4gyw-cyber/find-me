package com.ghaly.findme.mesh

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import java.io.File

/** Plays a received push-to-talk clip immediately, like a walkie-talkie. */
object VoicePlayer {
    fun play(context: Context, bytes: ByteArray) {
        try {
            val file = File(context.cacheDir, "ptt_in_${System.currentTimeMillis()}.3gp")
            file.writeBytes(bytes)
            val player = MediaPlayer()
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            player.setDataSource(file.absolutePath)
            player.setOnCompletionListener {
                it.release()
                file.delete()
            }
            player.setOnErrorListener { mp, _, _ ->
                mp.release()
                file.delete()
                true
            }
            player.prepare()
            player.start()
        } catch (e: Exception) {
            // best-effort — a failed playback just means that one clip is lost
        }
    }
}
