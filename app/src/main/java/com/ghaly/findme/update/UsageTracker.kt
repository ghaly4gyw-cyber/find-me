package com.ghaly.findme.update

import android.content.Context
import org.json.JSONObject

/**
 * Local, on-device usage analytics — which screens get opened, how often,
 * and how much foreground time each one gets — so the admin dashboard can
 * aggregate which features people actually rely on. Entirely local until
 * [UsageUploader] opportunistically syncs a snapshot; nothing here ever
 * blocks or interrupts anyone using the app, and nothing here is shown to
 * the person themselves.
 *
 * Deliberately coarse: this counts screen opens and time-on-screen per
 * screen, never the content inside a feature. A note's text, a ledger
 * entry's numbers, a chat message, a voice recording — none of that is ever
 * read or touched by this.
 */
object UsageTracker {
    private const val PREFS = "usage_tracker"
    private const val KEY_DATA = "data" // JSON: {app_opens, total_ms, features: {key: {opens, ms}}}
    private const val KEY_CURRENT_SCREEN = "current_screen"
    private const val KEY_SEGMENT_START = "segment_start_at"

    // A single segment is clamped to at most an hour — guards against a
    // clock change or a process left frozen in the background from ever
    // crediting an absurd amount of "time on screen".
    private const val MAX_SEGMENT_MS = 60 * 60 * 1000L

    @Synchronized
    private fun load(context: Context): JSONObject {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_DATA, null)
        return try {
            if (raw != null) JSONObject(raw) else JSONObject()
        } catch (e: Exception) {
            JSONObject()
        }
    }

    @Synchronized
    private fun save(context: Context, data: JSONObject) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_DATA, data.toString())
            .apply()
    }

    /** Call once per cold start of the process (not per Activity recreation)
     *  — approximates "how many times has this phone opened the app". */
    fun recordAppOpen(context: Context) {
        val data = load(context)
        data.put("app_opens", data.optLong("app_opens", 0L) + 1)
        save(context, data)
    }

    /** Call whenever the visible screen changes (every tab switch and every
     *  drill-in). Closes out the running segment for whatever was open
     *  before, credits it, then starts a fresh segment for [key] and bumps
     *  its open count. */
    @Synchronized
    fun onScreenShown(context: Context, key: String) {
        flushCurrentSegment(context, keepRunning = false)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_CURRENT_SCREEN, key)
            .putLong(KEY_SEGMENT_START, System.currentTimeMillis())
            .apply()

        val data = load(context)
        val features = data.optJSONObject("features") ?: JSONObject().also { data.put("features", it) }
        val entry = features.optJSONObject(key) ?: JSONObject().also { features.put(key, it) }
        entry.put("opens", entry.optLong("opens", 0L) + 1)
        save(context, data)
    }

    /** Call when the app leaves the foreground (Activity onPause) — stops
     *  the clock without forgetting which screen was open, so background
     *  time is never counted as time spent using a feature. */
    fun onAppBackgrounded(context: Context) {
        flushCurrentSegment(context, keepRunning = false)
    }

    /** Call when the app returns to the foreground (Activity onResume) —
     *  restarts the clock for whatever screen was showing when it left. */
    fun onAppForegrounded(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_CURRENT_SCREEN, null) != null) {
            prefs.edit().putLong(KEY_SEGMENT_START, System.currentTimeMillis()).apply()
        }
    }

    /** Credits the elapsed time of the running segment (if any) to that
     *  screen's totals, then either restarts the segment from now
     *  ([keepRunning]) or clears the start marker so no further time
     *  accrues until something restarts it. */
    @Synchronized
    private fun flushCurrentSegment(context: Context, keepRunning: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val currentScreen = prefs.getString(KEY_CURRENT_SCREEN, null)
        val startAt = prefs.getLong(KEY_SEGMENT_START, 0L)
        if (currentScreen != null && startAt > 0L) {
            val elapsed = (System.currentTimeMillis() - startAt).coerceIn(0L, MAX_SEGMENT_MS)
            if (elapsed > 0L) {
                val data = load(context)
                data.put("total_ms", data.optLong("total_ms", 0L) + elapsed)
                val features = data.optJSONObject("features") ?: JSONObject().also { data.put("features", it) }
                val entry = features.optJSONObject(currentScreen) ?: JSONObject().also { features.put(currentScreen, it) }
                entry.put("ms", entry.optLong("ms", 0L) + elapsed)
                save(context, data)
            }
        }
        val editor = prefs.edit()
        if (keepRunning) editor.putLong(KEY_SEGMENT_START, System.currentTimeMillis())
        else editor.remove(KEY_SEGMENT_START)
        editor.apply()
    }

    /** Builds the full cumulative snapshot to upload, flushing the running
     *  segment first so the number is accurate as of right now — then keeps
     *  the clock running, since the person is typically still on that
     *  screen while this runs on a background thread. */
    @Synchronized
    fun snapshotForUpload(context: Context): JSONObject {
        flushCurrentSegment(context, keepRunning = true)
        return load(context)
    }
}
