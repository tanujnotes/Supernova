package com.supernova.launcher.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

// Checked against published almanac times (timeanddate.com), within the few
// minutes NOAA's short equations give away.
class SolarTimesTest {

    private fun minute(h: Int, m: Int) = h * 60 + m

    private fun assertNear(expected: Int, actual: Int, tolerance: Int = 4) {
        assertTrue("expected ~$expected (${expected / 60}:${expected % 60}), got $actual (${actual / 60}:${actual % 60})",
            kotlin.math.abs(expected - actual) <= tolerance)
    }

    @Test
    fun newDelhiMidsummer() {
        val day = SolarTimes.of(LocalDate.of(2026, 6, 21), 28.6139, 77.2090, ZoneId.of("Asia/Kolkata"))
        assertNotNull(day)
        assertNear(minute(5, 24), day!!.sunrise)
        assertNear(minute(19, 22), day.sunset)
    }

    @Test
    fun londonMidwinter() {
        val day = SolarTimes.of(LocalDate.of(2026, 12, 21), 51.5074, -0.1278, ZoneId.of("Europe/London"))!!
        assertNear(minute(8, 4), day.sunrise)
        assertNear(minute(15, 54), day.sunset)
    }

    @Test
    fun newYorkMidsummerWithDaylightSaving() {
        val day = SolarTimes.of(LocalDate.of(2026, 6, 21), 40.7128, -74.0060, ZoneId.of("America/New_York"))!!
        assertNear(minute(5, 25), day.sunrise)
        assertNear(minute(20, 31), day.sunset)
    }

    @Test
    fun sydneyInTheSouthernWinter() {
        val day = SolarTimes.of(LocalDate.of(2026, 6, 21), -33.8688, 151.2093, ZoneId.of("Australia/Sydney"))!!
        assertNear(minute(7, 0), day.sunrise)
        assertNear(minute(16, 54), day.sunset)
    }

    @Test
    fun theArcticHasNoSunsetInJuneAndNoSunriseInDecember() {
        val june = LocalDate.of(2026, 6, 21)
        val december = LocalDate.of(2026, 12, 21)
        val zone = ZoneId.of("Europe/Oslo")
        assertNull(SolarTimes.of(june, 69.6492, 18.9553, zone))
        assertFalse(SolarTimes.polarNight(june, 69.6492))
        assertNull(SolarTimes.of(december, 69.6492, 18.9553, zone))
        assertTrue(SolarTimes.polarNight(december, 69.6492))
    }

    @Test
    fun theTimeZoneEstimateHasTheRightLongitudeAndHemisphere() {
        // Standard offsets, so daylight saving never moves the longitude.
        assertEquals(30.0 to 82.5, NightWindow.estimate(ZoneId.of("Asia/Kolkata")))
        assertEquals(30.0 to -75.0, NightWindow.estimate(ZoneId.of("America/New_York")))
        assertEquals(-30.0 to 150.0, NightWindow.estimate(ZoneId.of("Australia/Sydney")))
        assertEquals(-30.0 to -45.0, NightWindow.estimate(ZoneId.of("America/Sao_Paulo")))
    }
}
