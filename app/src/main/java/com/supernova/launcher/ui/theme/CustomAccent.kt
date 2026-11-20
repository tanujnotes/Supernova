package com.supernova.launcher.ui.theme

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.view.View
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.ColorResourcesOverride
import com.google.android.material.color.MaterialColors
import com.supernova.launcher.R
import com.supernova.launcher.data.LauncherPrefs

// Any accent colour, not just the curated ten (#233): the Settings style's
// Accent recolours the settings screens through ThemeOverlay.Supernova.Custom.
object CustomAccent {

    // The accent a view's theme carries (the curated or the custom one).
    fun of(view: View): Int = MaterialColors.getColor(view, androidx.appcompat.R.attr.colorAccent)

    // The Settings style's Accent as an opaque colour, or null when unset.
    fun settingsAccent(context: Context, prefs: LauncherPrefs): Int? {
        val spec = StyleConfig.decode(prefs.styleConfig).styleFor(SurfaceId.SETTINGS).accent
        if (spec.isDefault) return null
        return Styles.resolve(context, prefs).of(SurfaceId.SETTINGS).accent or OPAQUE
    }

    // The Settings style's Text as an opaque colour, or null when unset (#281).
    fun settingsText(context: Context, prefs: LauncherPrefs): Int? {
        val spec = StyleConfig.decode(prefs.styleConfig).styleFor(SurfaceId.SETTINGS).text
        if (spec.isDefault) return null
        return Styles.resolve(context, prefs).of(SurfaceId.SETTINGS).text or OPAQUE
    }

    // The settings pages' own text colours (settingsText, its muted
    // settingsTextSecondary, hairlines and washes) for a styled Text, else the
    // theme's. Always written: an override outlives the activity's recreate,
    // so going back to Default has to put the theme's colours back. Dialogs
    // and sheets keep textPrimary: the Settings Background doesn't reach them.
    fun textColors(context: Context, text: Int?): Map<Int, Int> {
        fun tint(alpha: Int, fallback: Int) =
            if (text == null) context.getColor(fallback) else (alpha shl ALPHA_SHIFT) or (text and RGB)
        return mapOf(
            R.color.settingsText to (text ?: context.getColor(R.color.textPrimary)),
            R.color.settingsTextSecondary to tint(SECONDARY_ALPHA, R.color.textSecondary),
            R.color.settingsPageDivider to tint(DIVIDER_ALPHA, R.color.settingsDivider),
            R.color.settingsPageStepBg to tint(WASH_ALPHA, R.color.settingsStepBg),
            R.color.settingsPageHighlight to tint(WASH_ALPHA, R.color.rippleHighlight)
        )
    }

    // Rewrites the given colours in this activity's resources (Android 11+,
    // through Material's resources-loader override); false where that isn't
    // possible.
    @android.annotation.SuppressLint("RestrictedApi")
    fun override(activity: Activity, colors: Map<Int, Int>): Boolean {
        val override = ColorResourcesOverride.getInstance() ?: return false
        return runCatching { override.applyIfPossible(activity, colors) }.getOrDefault(false)
    }

    // The Custom overlay's colours for an accent; where they can't be
    // applied, the nearest curated accent stands in.
    fun accentColors(activity: Activity, accent: Int): Map<Int, Int> {
        val night = activity.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        // On dark raised surfaces the curated accents' text tints sit 40%
        // toward white (accentBlue #2E89FF -> #82B8FF); same here. On light
        // ones a pale accent darkens until it reads on white.
        val raised = if (night) ColorUtils.blendARGB(accent, Color.WHITE, RAISED_LIFT) else readableOnWhite(accent)
        // Text on an accent fill: white unless the accent is pale.
        val onAccent = if (ColorUtils.calculateContrast(Color.WHITE, accent) >=
            ColorUtils.calculateContrast(Color.BLACK, accent)
        ) {
            Color.WHITE
        } else {
            ON_LIGHT_ACCENT
        }
        return mapOf(
            R.color.accentCustom to accent,
            R.color.accentCustomText to raised,
            R.color.onAccentCustom to onAccent
        )
    }

    private fun readableOnWhite(accent: Int): Int {
        var shade = accent
        var step = 0
        while (ColorUtils.calculateContrast(shade, Color.WHITE) < TEXT_CONTRAST && step < DARKEN_STEPS) {
            step++
            shade = ColorUtils.blendARGB(accent, Color.BLACK, step / DARKEN_STEPS.toFloat())
        }
        return shade
    }

    // The curated accent closest in hue (greys go to Blue, the default).
    fun nearest(context: Context, accent: Int): AccentColor {
        val hsv = FloatArray(HSV_SIZE)
        Color.colorToHSV(accent, hsv)
        if (hsv[1] < GREY_SATURATION) return AccentColor.BLUE
        return AccentColor.entries.minBy { entry ->
            val other = FloatArray(HSV_SIZE)
            Color.colorToHSV(context.getColor(entry.colorRes), other)
            val gap = kotlin.math.abs(other[0] - hsv[0])
            minOf(gap, FULL_TURN - gap)
        }
    }

    private const val OPAQUE = 0xFF000000.toInt()
    private const val RGB = 0x00FFFFFF
    private const val ALPHA_SHIFT = 24
    // textSecondary's alpha (B3 in dark, B0 in light).
    private const val SECONDARY_ALPHA = 0xB3
    private const val DIVIDER_ALPHA = 0x1F
    private const val WASH_ALPHA = 0x1A
    private const val RAISED_LIFT = 0.4f
    private const val GREY_SATURATION = 0.12f
    private const val TEXT_CONTRAST = 3.0
    private const val DARKEN_STEPS = 10
    private const val HSV_SIZE = 3
    private const val FULL_TURN = 360f
    private const val ON_LIGHT_ACCENT = 0xFF11161D.toInt()
}
