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
                    // USAGE_MEDIA (not USAGE_VOICE_COMMUNICATION) so playback goes out the
                    // main loudspeaker on the media volume stream. VOICE_COMMUNICATION is
                    // meant for an active phone call and can get routed quietly to the
                    // earpiece, which made clips sound like they never arrived.
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            player.setVolume(1.0f, 1.0f)
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
