package com.supernova.launcher.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

// The drawer's text with no text color set: off the drawer's own color, or
// the wallpaper's when the drawer barely covers it.
class DrawerTextTest {

    private val white = 0xFFFFFFFF.toInt()
    private val dark = Styles.DARK_TEXT

    @Test
    fun lightDrawerGetsDarkText() {
        assertEquals(dark, Styles.drawerText(0xFFFFFFFF.toInt(), 1f, white))
        assertEquals(dark, Styles.drawerText(0xFFD6E3FF.toInt(), 0.4f, white))
    }

    @Test
    fun darkDrawerGetsLightTextInEitherTheme() {
        // A primary tone-30 blue, the color the light theme used to put
        // dark labels on.
        assertEquals(white, Styles.drawerText(0xFF2F4A6E.toInt(), 0.4f, white))
        assertEquals(white, Styles.drawerText(0xFF1E2735.toInt(), 1f, white))
    }

    @Test
    fun aDrawerAboveSeventyPercentTransparencyReadsOnTheWallpaper() {
        assertEquals(white, Styles.drawerText(0xFFFFFFFF.toInt(), 0.29f, white))
        assertEquals(dark, Styles.drawerText(0xFFFFFFFF.toInt(), 0.3f, white))
    }
}
