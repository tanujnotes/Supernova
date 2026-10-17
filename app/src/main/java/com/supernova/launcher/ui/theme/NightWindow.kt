package com.supernova.launcher.ui.theme

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

// When Night mode's Auto is dark today: from sunset to sunrise where the phone
// is, estimated from the time zone, which puts sunset within about an hour of
// the real one.
object NightWindow {

    sealed interface Tonight {
        // Minutes since midnight; dark from sunset, past midnight, to sunrise.
        data class Window(val sunset: Int, val sunrise: Int) : Tonight

        // No sunset or no sunrise today (polar day or night).
        data class AllDay(val dark: Boolean) : Tonight
    }

    private class Cached(val date: LocalDate, val zone: ZoneId, val tonight: Tonight)

    @Volatile
    private var cached: Cached? = null

    // Called on every settings emission, so a day's answer is kept until the
    // date or the time zone changes.
    fun today(now: ZonedDateTime = ZonedDateTime.now()): Tonight {
        val date = now.toLocalDate()
        cached?.let { if (it.date == date && it.zone == now.zone) return it.tonight }
        val place = estimate(now.zone)
        val day = SolarTimes.of(date, place.first, place.second, now.zone)
        val tonight = if (day == null) {
            Tonight.AllDay(SolarTimes.polarNight(date, place.first))
        } else {
            Tonight.Window(day.sunset, day.sunrise)
        }
        cached = Cached(date, now.zone, tonight)
        return tonight
    }

    // The zone's standard offset gives the longitude, 15 degrees an hour.
    // For the latitude, 30 degrees in the zone's hemisphere, near where most
    // people live: it gets the seasons the right way round.
    fun estimate(zone: ZoneId): Pair<Double, Double> {
        val offsetHours = zone.rules.getStandardOffset(Instant.now()).totalSeconds / 3600.0
        val south = SOUTHERN_ZONES.any { zone.id.startsWith(it) }
        return (if (south) -ESTIMATE_LATITUDE else ESTIMATE_LATITUDE) to offsetHours * 15.0
    }

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
