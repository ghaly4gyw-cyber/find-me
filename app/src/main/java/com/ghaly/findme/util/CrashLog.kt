package com.ghaly.findme.util

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Two reported "black screen" fixes in a row (the Groups cache, then the
 * map's draw loop) turned out to be guesses — plausible bugs that were real,
 * but not necessarily THE bug behind what's actually happening on this one
 * phone, because nothing in this sandbox can see that phone's real crash
 * log. Guessing a third time isn't good enough. This writes down the exact
 * exception the next time the app dies, so whoever's using it next can read
 * it straight off the screen and report back the real cause — no computer,
 * no logcat, no translation through a vague screenshot required.
 */
object CrashLog {
    private const val FILE_NAME = "last_crash.txt"

    /** Call once, as the very first thing in Application.onCreate() — before
     *  anything else has a chance to crash before this is even listening. */
    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrash(app, thread, throwable)
            } catch (e: Exception) {
                // if even writing the crash log fails, there's nothing more to do —
                // fall through to the previous handler below regardless
            }
            // Still hand off to whatever Android's own default behavior was
            // (show "app isn't responding"/close it) — this only adds a
            // written record, it doesn't try to keep a broken app alive.
            previous?.uncaughtException(thread, throwable)
                ?: run {
                    android.os.Process.killProcess(android.os.Process.myPid())
                    kotlin.system.exitProcess(10)
                }
        }
    }

    private fun writeCrash(context: Context, thread: Thread, throwable: Throwable) {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val text = buildString {
            append("Find Me crashed\n")
            append("Time: $timestamp\n")
            append("Thread: ${thread.name}\n")
            append("Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT})\n")
            append("\n")
            append(sw.toString())
        }
        File(context.filesDir, FILE_NAME).writeText(text)
    }

    /** Reads and clears the last crash, if there is one — called once from
     *  MainActivity.onCreate() so it surfaces exactly once, right after the
     *  restart it caused, rather than nagging on every later launch too. */
    fun consumeLastCrash(context: Context): String? {
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
