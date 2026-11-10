package com.ghaly.findme.util

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A plain, always-on, append-only log — not another targeted guess. Three
 * targeted diagnostics (a crash log, a freeze watchdog, catching exceptions
 * the instant a screen opens) and two targeted fixes aimed at specific
 * theories (a window-resize setting, a one-off window-size toast) have all
 * either come back clean or gone unseen while the reported "shrinking" kept
 * happening. Rather than pick a sixth theory to test in isolation, this
 * writes down everything as it happens — every screen change, every
 * activity lifecycle callback, every window-focus change, every periodic
 * tick's real window size — each with a timestamp, in the order it actually
 * occurred. Whatever the next report looks like, the sequence of events
 * leading up to it is already on record instead of needing to be guessed at
 * again.
 */
object AppLog {
    private const val FILE_NAME = "app_log.txt"
    private const val MAX_BYTES = 2_000_000L // trimmed to the most recent half past this, not left to grow forever
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile private var appContext: Context? = null

    fun install(context: Context) {
        appContext = context.applicationContext
        log("AppLog", "app process started — ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
    }

    fun log(tag: String, message: String) {
        val context = appContext ?: return
        try {
            val file = File(context.filesDir, FILE_NAME)
            if (file.exists() && file.length() > MAX_BYTES) {
                val text = file.readText()
                file.writeText(text.takeLast((MAX_BYTES / 2).toInt()))
            }
            file.appendText("${timeFormat.format(Date())}  [$tag]  $message\n")
        } catch (e: Exception) {
            // best-effort — a logging failure should never affect the app itself
        }
    }

    /** The file to attach when sharing the log (see LedgerExport.shareFile). */
    fun logFile(context: Context): File = File(context.filesDir, FILE_NAME)
}
