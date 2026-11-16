package com.supernova.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WallpaperStatusIconsTest {
    @Test
    fun darkTextHintMeansDarkIcons() {
        assertTrue(WallpaperStatusIcons.darkIconsForHints(1))
        assertTrue(WallpaperStatusIcons.darkIconsForHints(1 or 4))
        // Dark theme hint alone, or no hints: light icons.
        assertFalse(WallpaperStatusIcons.darkIconsForHints(2))
        assertFalse(WallpaperStatusIcons.darkIconsForHints(0))
    }

    @Test
    fun wallpaperChoiceReadsBackByName() {
        assertEquals(StatusBarIcons.WALLPAPER, StatusBarIcons.fromName("WALLPAPER"))
        assertEquals(StatusBarIcons.AUTO, StatusBarIcons.fromName("SOMETHING_ELSE"))
    }
}
