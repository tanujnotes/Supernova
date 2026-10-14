package com.supernova.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelConfigTest {

    @Test
    fun emptyEncodesToBlankAndDecodesBack() {
        assertEquals("", LabelConfig().encode())
        assertEquals(LabelConfig.EMPTY, LabelConfig.decode(""))
        assertTrue(LabelConfig.decode("").of(LabelContext.DOCK).isEmpty())
    }

    @Test
    fun roundTripsEveryField() {
        val config = LabelConfig()
            .with(LabelContext.DOCK, LabelOverride(size = 10, font = "SERIF"))
            .with(LabelContext.DRAWER, LabelOverride(shadow = true))
            .with(LabelContext.FOLDER, LabelOverride(singleLine = false))
        val decoded = LabelConfig.decode(config.encode())
        assertEquals(config, decoded)
        assertEquals(10, decoded.of(LabelContext.DOCK).size)
        assertEquals("SERIF", decoded.of(LabelContext.DOCK).font)
        assertEquals(true, decoded.of(LabelContext.DRAWER).shadow)
        assertEquals(false, decoded.of(LabelContext.FOLDER).singleLine)
    }

    @Test
    fun desktopContextNeverOverrides() {
        val config = LabelConfig()
            .with(LabelContext.DESKTOP, LabelOverride(size = 18))
        assertTrue(config.of(LabelContext.DESKTOP).isEmpty())
        assertEquals("", config.encode())
    }

    @Test
    fun clearingTheLastOverrideEncodesBlankAgain() {
        val config = LabelConfig()
            .with(LabelContext.FOLDER, LabelOverride(size = 14))
            .with(LabelContext.FOLDER, LabelOverride())
        assertEquals("", config.encode())
    }

    @Test
    fun garbageAndUnknownKeysDecodeSafely() {
        assertEquals(LabelConfig.EMPTY, LabelConfig.decode("not json"))
        val decoded = LabelConfig.decode("""{"dock":{"size":11,"future":"x"},"future":{}}""")
        assertEquals(11, decoded.of(LabelContext.DOCK).size)
    }
}
