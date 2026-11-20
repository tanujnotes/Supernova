package com.supernova.launcher.ui

import android.content.Context
import android.content.res.Configuration
import com.supernova.launcher.data.LauncherPrefs

// Which icon tint the home screen's system bars get. True means the
// light-bar appearance, i.e. dark icons (isAppearanceLight*Bars).
object SystemBarIcons {
    // AUTO follows the theme (light theme = light bar = dark icons). WALLPAPER
    // asks the wallpaper each time (a wallpaper change re-applies); an opaque
    // bar is drawn black whatever the wallpaper, and a wallpaper with no
    // colours (some live ones) falls back to the theme.
    fun lightBar(
        choice: StatusBarIcons,
        night: Boolean,
        transparent: Boolean,
        wallpaperDarkIcons: () -> Boolean?
    ): Boolean = when (choice) {
        StatusBarIcons.AUTO -> !night
        StatusBarIcons.WALLPAPER -> transparent && (wallpaperDarkIcons() ?: !night)
        StatusBarIcons.LIGHT -> true
        StatusBarIcons.DARK -> false
    }

    // The nav bar's own choice, or the status bar's choice applied to the
    // nav bar (its own transparency decides an opaque, black bar).
    fun navChoice(prefs: LauncherPrefs): StatusBarIcons = when (prefs.navBarButtons) {
        NavBarButtons.STATUS_BAR -> prefs.darkStatusIcons
        NavBarButtons.AUTO -> StatusBarIcons.AUTO
        NavBarButtons.WALLPAPER -> StatusBarIcons.WALLPAPER
        NavBarButtons.LIGHT -> StatusBarIcons.LIGHT
        NavBarButtons.DARK -> StatusBarIcons.DARK
    }

    fun lightStatusBar(context: Context, prefs: LauncherPrefs): Boolean =
        lightBar(prefs.darkStatusIcons, isNight(context), prefs.transparentStatus) {
            WallpaperStatusIcons.darkIcons(context)
        }

    fun lightNavBar(context: Context, prefs: LauncherPrefs): Boolean =
        lightBar(navChoice(prefs), isNight(context), prefs.transparentNav) {
            WallpaperStatusIcons.darkIcons(context)
        }

    private fun isNight(context: Context): Boolean =
        context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
}
