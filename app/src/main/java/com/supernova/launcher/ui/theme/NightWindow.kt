package com.supernova.launcher.ui.theme

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

// When Night mode's Auto is dark today: from sunset to sunrise where the phone
// is. With Location enhancement on (and coarse location granted)
// the place is the phone's last known fix; otherwise it is estimated from the
// time zone, which puts sunset within about an hour of the real one.
object NightWindow {

    sealed interface Tonight {
        // Minutes since midnight; dark from sunset, past midnight, to sunrise.
        data class Window(val sunset: Int, val sunrise: Int) : Tonight

        // No sunset or no sunrise today (polar day or night).
        data class AllDay(val dark: Boolean) : Tonight
    }

    private class Cached(val date: LocalDate, val useLocation: Boolean, val at: Long, val tonight: Tonight)

    @Volatile
    private var cached: Cached? = null

    // Called on every settings emission, so a day's answer is kept for a while
    // rather than asking the location service each time.
    fun today(context: Context, useLocation: Boolean, now: ZonedDateTime = ZonedDateTime.now()): Tonight {
        val date = now.toLocalDate()
        val clock = System.currentTimeMillis()
        cached?.let {
            if (it.date == date && it.useLocation == useLocation && clock - it.at < CACHE_MS) return it.tonight
        }
        val place = (if (useLocation) lastKnown(context) else null) ?: estimate(now.zone)
        val day = SolarTimes.of(date, place.first, place.second, now.zone)
        val tonight = if (day == null) {
            Tonight.AllDay(SolarTimes.polarNight(date, place.first))
        } else {
            Tonight.Window(day.sunset, day.sunrise)
        }
        cached = Cached(date, useLocation, clock, tonight)
        return tonight
    }

    // Forget today's answer, once the place it came from may have changed.
    fun invalidate() {
        cached = null
    }

    fun hasLocationPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    // The freshest fix any provider holds. No new fix is asked for: a
    // sunset only needs the city, and the last fix is almost always there.
    // Lint can't see through hasLocationPermission(); a revoke racing it
    // throws inside the runCatching.
    @SuppressLint("MissingPermission")
    private fun lastKnown(context: Context): Pair<Double, Double>? {
        if (!hasLocationPermission(context)) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val fix = runCatching {
            manager.getProviders(true).mapNotNull { manager.getLastKnownLocation(it) }.maxByOrNull { it.time }
        }.getOrNull() ?: return null
        return fix.latitude to fix.longitude
    }

    // The zone's standard offset gives the longitude, 15 degrees an hour.
    // For the latitude, 30 degrees in the zone's hemisphere, near where most
    // people live: it gets the seasons the right way round.
    fun estimate(zone: ZoneId): Pair<Double, Double> {
        val offsetHours = zone.rules.getStandardOffset(Instant.now()).totalSeconds / 3600.0
        val south = SOUTHERN_ZONES.any { zone.id.startsWith(it) }
        return (if (south) -ESTIMATE_LATITUDE else ESTIMATE_LATITUDE) to offsetHours * 15.0
    }

    // Re-read at least this often, so a fix taken since shows up.
    private const val CACHE_MS = 30 * 60_000L
    private const val ESTIMATE_LATITUDE = 30.0

    // Zones well south of the equator, by prefix; everything else counts as
    // north. Near the equator the seasons barely move sunset either way.
    private val SOUTHERN_ZONES = listOf(
        "Australia/", "Antarctica/", "Pacific/Auckland", "Pacific/Chatham", "Pacific/Fiji",
        "Pacific/Tongatapu", "Pacific/Noumea", "Pacific/Tahiti",
        "America/Argentina", "America/Buenos_Aires", "America/Sao_Paulo", "America/Santiago",
        "America/Montevideo", "America/Asuncion", "America/La_Paz", "America/Punta_Arenas",
        "America/Campo_Grande", "America/Cuiaba",
        "Africa/Johannesburg", "Africa/Maputo", "Africa/Harare", "Africa/Lusaka", "Africa/Windhoek",
        "Africa/Gaborone", "Africa/Maseru", "Africa/Mbabane", "Africa/Blantyre",
        "Indian/Mauritius", "Indian/Reunion", "Indian/Antananarivo"
    )
}
