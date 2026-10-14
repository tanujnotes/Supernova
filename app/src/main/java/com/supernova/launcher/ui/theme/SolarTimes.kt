package com.supernova.launcher.ui.theme

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

// Sunrise and sunset for a day at a place, from NOAA's solar equations (the
// ones behind its solar calculator), good to a few minutes between the polar
// circles. Night mode's Auto runs dark from one to the other.
object SolarTimes {

    // Minutes since local midnight.
    data class Day(val sunrise: Int, val sunset: Int)

    // Null when the sun doesn't rise or doesn't set that day (polar day or
    // night); `polarNight` says which.
    fun of(date: LocalDate, latitude: Double, longitude: Double, zone: ZoneId): Day? {
        val (sunriseUtc, sunsetUtc) = utcMinutes(date, latitude) ?: return null
        return Day(
            sunrise = localMinute(date, sunriseUtc - 4.0 * longitude, zone),
            sunset = localMinute(date, sunsetUtc - 4.0 * longitude, zone)
        )
    }

    // With no sunrise or sunset that day: whether it is the dark case.
    fun polarNight(date: LocalDate, latitude: Double): Boolean {
        val declination = declination(fractionalYear(date))
        // The sun stays down when the pole it's over is the other one.
        return latitude * declination < 0
    }

    // Sunrise and sunset in UTC minutes at longitude 0, before the 4 minutes
    // a degree the caller takes off for its own longitude.
    private fun utcMinutes(date: LocalDate, latitude: Double): Pair<Double, Double>? {
        val gamma = fractionalYear(date)
        val eqTime = 229.18 * (
            0.000075 + 0.001868 * cos(gamma) - 0.032077 * sin(gamma) -
                0.014615 * cos(2 * gamma) - 0.040849 * sin(2 * gamma)
            )
        val decl = declination(gamma)
        val lat = Math.toRadians(latitude)
        val cosHa = cos(Math.toRadians(ZENITH_DEG)) / (cos(lat) * cos(decl)) - tan(lat) * tan(decl)
        if (cosHa < -1.0 || cosHa > 1.0) return null
        val ha = Math.toDegrees(acos(cosHa))
        return (720 - 4 * ha - eqTime) to (720 + 4 * ha - eqTime)
    }

    private fun fractionalYear(date: LocalDate): Double =
        2 * Math.PI / date.lengthOfYear() * (date.dayOfYear - 1)

    private fun declination(gamma: Double): Double =
        0.006918 - 0.399912 * cos(gamma) + 0.070257 * sin(gamma) - 0.006758 * cos(2 * gamma) +
            0.000907 * sin(2 * gamma) - 0.002697 * cos(3 * gamma) + 0.00148 * sin(3 * gamma)

    private fun localMinute(date: LocalDate, utcMinutes: Double, zone: ZoneId): Int {
        val instant: Instant = date.atStartOfDay().toInstant(ZoneOffset.UTC)
            .plusSeconds((utcMinutes * 60).toLong())
        val local = instant.atZone(zone).toLocalTime()
        return local.hour * 60 + local.minute
    }

    // The sun's centre 50' below the horizon: its radius plus refraction.
    private const val ZENITH_DEG = 90.833
}
