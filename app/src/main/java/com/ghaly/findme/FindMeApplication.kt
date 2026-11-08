package com.ghaly.findme

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/** Most of the people who will ever install this app read Arabic first, so the
 *  app should open in Arabic out of the box — not whatever language the phone's
 *  system settings happen to be in (which is often English on a cheap/used
 *  device). Anyone can still switch languages from the 🌐 button in the header;
 *  this only decides what shows up the very first time, before they've chosen. */
class FindMeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (AppCompatDelegate.getApplicationLocales().isEmpty) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ar"))
        }
    }
}
