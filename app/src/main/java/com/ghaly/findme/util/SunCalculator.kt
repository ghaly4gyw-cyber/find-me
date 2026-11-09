package com.ghaly.findme.util

import org.shredzone.commons.suncalc.SunTimes
import java.time.Duration
import java.time.ZonedDateTime

/** Answers one question for the map screen: how much daylight is left (or,
 *  at night, how long until sunrise) from the phone's current GPS fix.
 *
 *  This defers entirely to commons-suncalc — a tested implementation of
 *  NOAA's solar position algorithm — rather than hand-rolling the formula.
 *  A wrong "time until dark" answer is actively dangerous for someone in the
 *  desert, so when the library can't give a confident answer (it happens at
 *  extreme latitudes with permanent day or night — not a real case here, but
 *  handled honestly anyway) this returns [DaylightState.Unknown] instead of
 *  guessing. */
object SunCalculator {

    sealed class DaylightState {
        /** The sun is up right now; [remaining] is time left until it sets. */
        data class Daylight(val remaining: Duration) : DaylightState()
        /** The sun is down right now; [remaining] is time left until it rises. */
        data class Night(val remaining: Duration) : DaylightState()
        /** No confident rise/set time for this location and date. */
        object Unknown : DaylightState()
    }

    fun compute(
        lat: Double,
        lon: Double,
        now: ZonedDateTime = ZonedDateTime.now()
    ): DaylightState {
        return try {
            // Default search is forward-looking from `now`, so getRise()/getSet()
            // are always the *next* sunrise/sunset — today's if still ahead,
            // tomorrow's if it already happened.
            val times = SunTimes.compute().on(now).at(lat, lon).execute()
            val nextRise = times.rise
            val nextSet = times.set
            when {
                nextSet != null && (nextRise == null || nextSet.isBefore(nextRise)) ->
                    DaylightState.Daylight(Duration.between(now, nextSet))
                nextRise != null ->
                    DaylightState.Night(Duration.between(now, nextRise))
                else -> DaylightState.Unknown
            }
        } catch (e: Exception) {
            DaylightState.Unknown
        }
    }

    /** "2h 14m" / "48m" — never shows seconds, since that false precision
     *  isn't meaningful for a GPS-fix-driven estimate updated every 10s. */
    fun format(d: Duration): String {
        val totalMinutes = (d.toMinutes()).coerceAtLeast(0)
        val h = totalMinutes / 60
        val m = totalMinutes % 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }
}
