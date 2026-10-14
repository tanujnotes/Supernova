package com.supernova.launcher.ui.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StyleEngineTest {

    @Test
    fun toneStopsMapToNearestIndex() {
        assertEquals(0, ToneRamp.indexOfTone(0))
        assertEquals(ToneRamp.TONES.size - 1, ToneRamp.indexOfTone(100))
        // 97 sits between 95 and 99; 95 wins on distance 2 vs 2? 95 first.
        assertEquals(ToneRamp.TONES.indexOf(95), ToneRamp.indexOfTone(96))
        assertEquals(ToneRamp.TONES.indexOf(50), ToneRamp.indexOfTone(52))
    }

    @Test
    fun rampAnchorsBlackAndWhite() {
        val ramp = ToneRamp.ramp(0xFF336699.toInt(), 1f)
        assertEquals(0xFF000000.toInt(), ramp.first())
        assertEquals(0xFFFFFFFF.toInt(), ramp.last())
        assertEquals(ToneRamp.TONES.size, ramp.size)
    }

    @Test
    fun rampGetsLighterAsTonesRise() {
        val ramp = ToneRamp.ramp(0xFF663399.toInt(), 1f)
        for (i in 1 until ramp.size) {
            val prev = ToneRamp.rgbToHsl(ramp[i - 1])[2]
            val next = ToneRamp.rgbToHsl(ramp[i])[2]
            assertTrue(next >= prev, "tone ${ToneRamp.TONES[i]} darker than ${ToneRamp.TONES[i - 1]}")
        }
    }

    @Test
    fun neutralRampIsDesaturated() {
        val seed = 0xFF2E89FF.toInt()
        val primaryMid = ToneRamp.rgbToHsl(ToneRamp.ramp(seed, 1f)[5])[1]
        val neutralMid = ToneRamp.rgbToHsl(ToneRamp.ramp(seed, 0.12f)[5])[1]
        assertTrue(neutralMid < primaryMid)
    }

    @Test
    fun hslRoundTripsPrimaries() {
        for (color in intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFF808080.toInt())) {
            val hsl = ToneRamp.rgbToHsl(color)
            assertEquals(color, ToneRamp.hslToRgb(hsl[0], hsl[1], hsl[2]))
        }
    }

    @Test
    fun styleConfigRoundTrips() {
        val config = StyleConfig(
            mapOf(
                SurfaceId.DRAWER to SurfaceStyle(
                    surface = ColorSpec(role = PaletteRole.NEUTRAL, tone = 20),
                    alpha = 0.85f,
                    cornerRadii = listOf(24f, 24f, 0f, 0f),
                    cornerCuts = listOf("SQUIRCLE", "SQUIRCLE", "ROUND", "CUT")
                ),
                SurfaceId.DOCK to SurfaceStyle(surface = ColorSpec(fixed = "#80112233")),
                SurfaceId.POPUP to SurfaceStyle(minContrast = 40)
            )
        )
        val decoded = StyleConfig.decode(config.encode())
        assertEquals(config, decoded)
    }

    @Test
    fun emptyConfigEncodesToEmptyStringAndDecodesFromJunk() {
        assertEquals("", StyleConfig.EMPTY.encode())
        assertEquals(StyleConfig.EMPTY, StyleConfig.decode(""))
        assertEquals(StyleConfig.EMPTY, StyleConfig.decode("not json at all"))
    }

    @Test
    fun withRemovesEmptiedSurfaces() {
        val config = StyleConfig.EMPTY.with(
            SurfaceId.FOLDER, SurfaceStyle(surface = ColorSpec(fixed = "#FF0000"))
        )
        assertEquals(1, config.surfaces.size)
        val cleared = config.with(SurfaceId.FOLDER, SurfaceStyle())
        assertEquals(StyleConfig.EMPTY, cleared)
        assertEquals("", cleared.encode())
        // A minimum contrast alone keeps its surface in the config.
        assertEquals(1, StyleConfig.EMPTY.with(SurfaceId.DOCK, SurfaceStyle(minContrast = 30)).surfaces.size)
    }

    @Test
    fun defaultSpecResolvesToNothing() {
        assertTrue(ColorSpec().isDefault)
        assertTrue(ColorSpec(role = PaletteRole.PRIMARY).isDefault)
        assertTrue(ColorSpec(tone = 40).isDefault)
    }
}
