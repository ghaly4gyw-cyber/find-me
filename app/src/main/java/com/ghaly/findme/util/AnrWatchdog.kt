package com.ghaly.findme.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * [CrashLog] only catches the app actually dying — a thrown exception. The
 * reported black screen never triggered that: no crash popup ever showed,
 * which the person confirmed directly. That rules out a normal crash and
 * points at the other failure this app can have — the main thread stuck
 * (an infinite loop, a deadlock, something blocking) so the screen just
 * stops updating without ever throwing anything. Android itself eventually
 * notices that and can show its own "isn't responding" dialog, but by then
 * there's no record of WHERE the main thread was stuck, and the Kotlin
 * trace is gone the moment the process dies.
 *
 * This watches for exactly that: a background thread pings the main thread
 * on a timer, and if the main thread doesn't acknowledge the ping within a
 * few seconds, something is blocking it — so this dumps that thread's
 * current stack (which only needs reading the thread's state, not its
 * cooperation, so it works even while truly stuck) to a file, the same way
 * [CrashLog] does for a real crash. The app isn't killed or restarted here;
 * it's purely a recorder, picked up by MainActivity the next time it opens.
 */
object AnrWatchdog {
    private const val FILE_NAME = "last_anr.txt"
    private const val PING_INTERVAL_MS = 3_000L
    private const val HANG_THRESHOLD_MS = 5_000L

    @Volatile private var ackedTick = 0
    @Volatile private var alreadyWrittenForThisHang = false

    fun install(context: Context) {
        val appContext = context.applicationContext
        val mainThread = Looper.getMainLooper().thread
        val mainHandler = Handler(Looper.getMainLooper())

        Thread({
            var tick = 0
            while (true) {
                try {
                    Thread.sleep(PING_INTERVAL_MS)
                } catch (e: InterruptedException) {
                    return@Thread
                }
                tick++
                val thisTick = tick
                mainHandler.post {
                    ackedTick = thisTick
                    alreadyWrittenForThisHang = false
                }
                try {
                    Thread.sleep(HANG_THRESHOLD_MS)
                } catch (e: InterruptedException) {
                    return@Thread
                }
                if (ackedTick < thisTick && !alreadyWrittenForThisHang) {
                    alreadyWrittenForThisHang = true
                    try {
                        writeHang(appContext, mainThread)
                    } catch (e: Exception) {
                        // best-effort diagnostic — nothing more to do if even this fails
                    }
                }
            }
        }, "find-me-anr-watchdog").apply { isDaemon = true }.start()
    }

    private fun writeHang(context: Context, mainThread: Thread) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        for (element in mainThread.stackTrace) {
            pw.println("    at $element")
        }
        val text = buildString {
            append("Find Me froze (main thread stopped responding)\n")
            append("Time: $timestamp\n")
            append("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (Android ${android.os.Build.VERSION.RELEASE}, SDK ${android.os.Build.VERSION.SDK_INT})\n")
            append("\n")
            append("What the main thread was doing at that moment:\n")
            append(sw.toString())
        }
        File(context.filesDir, FILE_NAME).writeText(text)
    }

    /** Reads and clears the last recorded freeze, if there is one — same
     *  one-shot pattern as [CrashLog.consumeLastCrash]. */
    fun consumeLastHang(context: Context): String? {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return null
        return try {
            val text = file.readText()
            file.delete()
            text
        } catch (e: Exception) {
            file.delete()
            null
        }
    }
}
