package com.supernova.launcher.ui

import com.supernova.launcher.data.LauncherPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemBarIconsTest {
    @Test
    fun navButtonsCanFollowTheStatusBarChoice() {
        val prefs = LauncherPrefs(
            darkStatusIcons = StatusBarIcons.WALLPAPER,
            navBarButtons = NavBarButtons.STATUS_BAR
        )
        assertEquals(StatusBarIcons.WALLPAPER, SystemBarIcons.navChoice(prefs))
        assertEquals(StatusBarIcons.DARK, SystemBarIcons.navChoice(prefs.copy(navBarButtons = NavBarButtons.DARK)))
    }

    @Test
    fun navButtonsDefaultToTheTheme() {
        assertEquals(NavBarButtons.AUTO, LauncherPrefs().navBarButtons)
        assertEquals(NavBarButtons.AUTO, NavBarButtons.fromName("SOMETHING_ELSE"))
    }

    @Test
    fun wallpaperChoiceNeedsATransparentBar() {
        assertTrue(SystemBarIcons.lightBar(StatusBarIcons.WALLPAPER, night = true, transparent = true) { true })
        assertFalse(SystemBarIcons.lightBar(StatusBarIcons.WALLPAPER, night = false, transparent = false) { true })
        // No wallpaper colours: the theme decides.
        assertTrue(SystemBarIcons.lightBar(StatusBarIcons.WALLPAPER, night = false, transparent = true) { null })
        assertFalse(SystemBarIcons.lightBar(StatusBarIcons.AUTO, night = true, transparent = true) { true })
    }
}
