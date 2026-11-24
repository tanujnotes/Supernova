package com.supernova.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class IconOriginTest {

    @Test
    fun centresTheIconOnItsPoint() {
        assertEquals(400, iconOrigin(0.5f, 1000, 200))
        // Contacts on the Galaxy: 0.7917 down a 1462px canvas, 203px tall.
        assertEquals(1056, iconOrigin(0.7916667f, 1462, 203))
    }

    @Test
    fun keepsTheIconInsideTheCanvas() {
        assertEquals(0, iconOrigin(0f, 1000, 200))
        assertEquals(800, iconOrigin(1f, 1000, 200))
        assertEquals(800, iconOrigin(0.95f, 1000, 200))
        assertEquals(0, iconOrigin(-0.5f, 1000, 200))
        assertEquals(800, iconOrigin(1.5f, 1000, 200))
    }

    // A two-line label hangs below: the item centres on its icon and first
    // line, so its top matches a one-line neighbour's (#346).
    @Test
    fun extraLabelLinesHangBelow() {
        val oneLine = iconOrigin(0.5f, 1000, 200)
        assertEquals(oneLine, iconOrigin(0.5f, 1000, 240, anchor = 200))
        // Still kept inside the canvas by its full height.
        assertEquals(760, iconOrigin(1f, 1000, 240, anchor = 200))
    }

    // An icon bigger than the canvas pins to the origin rather than going
    // negative.
    @Test
    fun oversizedIconPinsToTheOrigin() {
        assertEquals(0, iconOrigin(0.5f, 100, 200))
    }
}
