package com.supernova.launcher.ui

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import android.os.Build
import androidx.core.graphics.ColorUtils

// "Automatic" status bar icons: dark icons when the system says dark
// text reads on the home wallpaper (WallpaperColors.HINT_SUPPORTS_DARK_TEXT,
// the hint the system's own launchers theme by), else light icons. Before
// Android 12 the hints are hidden API, read by reflection; if that is
// refused, the wallpaper's main colour decides.
// Null when the wallpaper gives no colours (some live wallpapers); the caller
// falls back to the theme.
object WallpaperStatusIcons {
    // Android's WallpaperColors.HINT_SUPPORTS_DARK_TEXT, public from API 31.
    private const val HINT_SUPPORTS_DARK_TEXT = 1

    fun darkIcons(context: Context): Boolean? {
        val colors = runCatching {
            WallpaperManager.getInstance(context).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
        }.getOrNull() ?: return null
        return darkIcons(colors)
    }

    private fun darkIcons(colors: WallpaperColors): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            darkIconsForHints(colors.colorHints)
        } else {
            hiddenHints(colors)?.let(::darkIconsForHints)
                ?: darkIconsForColor(colors.primaryColor.toArgb())
        }

    private fun hiddenHints(colors: WallpaperColors): Int? = runCatching {
        WallpaperColors::class.java.getMethod("getColorHints").invoke(colors) as Int
    }.getOrNull()

    fun darkIconsForHints(hints: Int): Boolean = hints and HINT_SUPPORTS_DARK_TEXT != 0

    // A light main colour wants dark icons.
    fun darkIconsForColor(argb: Int): Boolean = ColorUtils.calculateLuminance(argb) > DARK_TEXT_LUMINANCE

    private const val DARK_TEXT_LUMINANCE = 0.5
}
