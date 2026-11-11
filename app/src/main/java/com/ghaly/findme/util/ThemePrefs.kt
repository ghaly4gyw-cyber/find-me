package com.ghaly.findme.util

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.ghaly.findme.R

/** The app's dark/light theme choice. Default is "follow the phone's own
 *  system setting" — exactly what AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
 *  already does automatically, picking between values/ and values-night/
 *  resources with zero extra work. The one thing that needs to persist here
 *  is a manual override, for anyone who wants this app specifically to
 *  always be light or always be dark regardless of the rest of their phone.
 *
 *  Calling AppCompatDelegate.setDefaultNightMode() on an AppCompatActivity
 *  automatically recreates it with the new resource set applied — so
 *  flipping the mode from a button inside the app takes effect immediately,
 *  no manual recreate() call needed. */
object ThemePrefs {
    private const val PREFS_NAME = "theme_prefs"
    private const val KEY_MODE = "mode"

    const val MODE_SYSTEM = 0
    const val MODE_LIGHT = 1
    const val MODE_DARK = 2

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun currentMode(context: Context): Int {
        return prefs(context).getInt(KEY_MODE, MODE_SYSTEM)
    }

    /** Call once, as early as possible (FindMeApplication.onCreate), so the
     *  very first Activity that's created already uses the right mode. */
    fun applyStored(context: Context) {
        AppCompatDelegate.setDefaultNightMode(toNightMode(currentMode(context)))
    }

    /** Cycles System → Light → Dark → System, persists the choice, and
     *  applies it immediately (recreating the calling Activity). Returns the
     *  new mode so the caller can show a confirmation of what it became. */
    fun cycle(context: Context): Int {
        val next = when (currentMode(context)) {
            MODE_SYSTEM -> MODE_LIGHT
            MODE_LIGHT -> MODE_DARK
            else -> MODE_SYSTEM
        }
        prefs(context).edit().putInt(KEY_MODE, next).apply()
        AppCompatDelegate.setDefaultNightMode(toNightMode(next))
        return next
    }

    fun labelFor(context: Context, mode: Int): String = when (mode) {
        MODE_LIGHT -> context.getString(R.string.theme_mode_light)
        MODE_DARK -> context.getString(R.string.theme_mode_dark)
        else -> context.getString(R.string.theme_mode_system)
    }

    private fun toNightMode(mode: Int): Int = when (mode) {
        MODE_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
        MODE_DARK -> AppCompatDelegate.MODE_NIGHT_YES
        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }
}
