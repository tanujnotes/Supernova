package com.supernova.launcher.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ContrastTest {

    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    @Test
    fun toneIsCielabLightness() {
        assertEquals(100.0, Contrast.tone(white), 0.01)
        assertEquals(0.0, Contrast.tone(black), 0.01)
        // Material's tone-50 gray.
        assertEquals(50.0, Contrast.tone(0xFF777777.toInt()), 0.2)
    }

    @Test
    fun textThatAlreadyStandsClearIsLeftAlone() {
        assertEquals(white, Contrast.ensure(white, black, 40))
        assertEquals(0xFF1A1C1E.toInt(), Contrast.ensure(0xFF1A1C1E.toInt(), 0xFFF2F4F7.toInt(), 40))
    }

    @Test
    fun offLeavesEvenUnreadableTextAlone() {
        assertEquals(white, Contrast.ensure(white, white, null))
        assertEquals(white, Contrast.ensure(white, white, 0))
    }

    @Test
    fun textTooCloseMovesJustFarEnough() {
        val surface = 0xFFE0E0E0.toInt()
        val adjusted = Contrast.ensure(white, surface, 40)
        val gap = Contrast.tone(surface) - Contrast.tone(adjusted)
        assertTrue("gap $gap", gap >= 40.0 && gap < 41.5)
    }

    @Test
    fun textKeepsItsHueWhileItChangesShade() {
        val lightBlue = 0xFF90CAF9.toInt()
        val adjusted = Contrast.ensure(lightBlue, white, 45)
        assertTrue(Contrast.tone(white) - Contrast.tone(adjusted) >= 45.0)
        val before = ToneRamp.rgbToHsl(lightBlue)[0]
        val after = ToneRamp.rgbToHsl(adjusted)[0]
        assertTrue("hue $before -> $after", abs(before - after) < 3f)
    }

    @Test
    fun textSwitchesSidesWhenItsOwnSideHasNoRoom() {
        // Dark text on a dark surface cannot go 40 darker, so it goes light.
        val surface = 0xFF303030.toInt()
        val adjusted = Contrast.ensure(0xFF202020.toInt(), surface, 40)
        assertTrue(Contrast.tone(adjusted) - Contrast.tone(surface) >= 40.0)
    }

    @Test
    fun withNoRoomOnEitherSideTheRoomierExtremeWins() {
        // Tone ~40: 60 tones of room above, 40 below; 70 fits on neither.
        val surface = 0xFF5E5E5E.toInt()
        assertEquals(white, Contrast.ensure(0xFF606060.toInt(), surface, 70))
    }

    @Test
    fun alphaIsKept() {
        val adjusted = Contrast.ensure(0x80FFFFFF.toInt(), white, 40)
        assertEquals(0x80, adjusted ushr 24)
    }
}
