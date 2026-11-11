package com.ghaly.findme

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.ghaly.findme.util.AnrWatchdog
import com.ghaly.findme.util.AppLog
import com.ghaly.findme.util.CrashLog
import com.ghaly.findme.util.ThemePrefs

/** Most of the people who will ever install this app read Arabic first, so the
 *  app should open in Arabic out of the box — not whatever language the phone's
 *  system settings happen to be in (which is often English on a cheap/used
 *  device). Anyone can still switch languages from the 🌐 button in the header;
 *  this only decides what shows up the very first time, before they've chosen. */
class FindMeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // First thing, before anything else gets a chance to crash before
        // this is listening.
        CrashLog.install(this)
        // A reported freeze that never showed a crash popup isn't a normal
        // exception — it's the main thread stuck. This catches that case too.
        AnrWatchdog.install(this)
        // Targeted checks for a crash and a freeze haven't explained the
        // reported "shrinking" — this writes down everything, continuously,
        // so the next report is a real sequence of events instead of a guess.
        AppLog.install(this)
        // Follow-system by default; only matters here if someone already
        // chose a manual Light/Dark override — see ThemePrefs.
        ThemePrefs.applyStored(this)
        if (AppCompatDelegate.getApplicationLocales().isEmpty) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ar"))
        }
    }
}
