package com.supernova.launcher.ui

import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.widget.TextView
import com.supernova.launcher.R
import com.supernova.launcher.data.LauncherPrefs
import com.supernova.launcher.ui.theme.Styles
import com.supernova.launcher.ui.theme.SurfaceId
import java.util.EnumMap

enum class LabelFont {
    DEFAULT,
    CONDENSED,
    SERIF,
    MONOSPACE;

    companion object {
        fun fromName(name: String): LabelFont =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: DEFAULT
    }
}

// Applies the label-appearance prefs (size, color, shadow, font) to any icon
// label. Visibility stays a per-surface decision at the call site.
object LabelStyler {

    // Typeface.create walks font families; resolve each font exactly once.
    private val typefaces = EnumMap<LabelFont, Typeface>(LabelFont::class.java)

    private fun typefaceFor(font: LabelFont): Typeface = typefaces.getOrPut(font) {
        when (font) {
            LabelFont.DEFAULT -> Typeface.SANS_SERIF
            LabelFont.CONDENSED -> Typeface.create("sans-serif-condensed", Typeface.NORMAL)
            LabelFont.SERIF -> Typeface.SERIF
            LabelFont.MONOSPACE -> Typeface.MONOSPACE
        }
    }

    // Labels drawn over the wallpaper stay light in both themes; labels on an
    // app surface read like the rest of that surface's text, its style's
    // text color and minimum contrast included, which unstyled is the theme's
    // (the dock's, light like the wallpaper's). The dock counts as a surface
    // only while it draws a background. With no user color set, each surface
    // needs its own default, so the caller says which it is. The context
    // resolves per-surface overrides (labelConfig pref); every unset field
    // inherits the desktop label prefs.
    fun apply(
        view: TextView,
        prefs: LauncherPrefs,
        onWallpaper: Boolean,
        context: LabelContext = LabelContext.DESKTOP
    ) {
        val override = LabelConfig.decode(prefs.labelConfig).of(context)
        val surface = when (context) {
            LabelContext.DRAWER -> SurfaceId.DRAWER
            LabelContext.FOLDER -> SurfaceId.FOLDER
            LabelContext.DOCK ->
                SurfaceId.DOCK.takeIf { DockBgStyle.fromName(prefs.dockBgStyle) != DockBgStyle.NONE }
            LabelContext.DESKTOP -> null
        }
        val fallback = when {
            surface != null -> Styles.resolve(view.context, prefs).of(surface).text
            onWallpaper -> view.context.getColor(R.color.onWallpaper)
            else -> view.context.getColor(R.color.textPrimary)
        }
        val size = (override.size ?: prefs.labelSize).coerceIn(9, 18)
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size.toFloat())
        view.setTextColor(parseColor(prefs.labelColor, fallback))
        view.typeface = typefaceFor(LabelFont.fromName(override.font ?: prefs.labelFont))
        // Each surface declares its own maxLines in XML; remember that value
        // on the view so single-line can be undone on a recycled bind.
        val surfaceMax = view.getTag(R.id.tag_default_max_lines) as? Int
            ?: view.maxLines.also { view.setTag(R.id.tag_default_max_lines, it) }
        val targetMax = if (singleLineFor(context, prefs)) 1 else surfaceMax
        if (view.maxLines != targetMax) view.maxLines = targetMax
        if (view.ellipsize != TextUtils.TruncateAt.END) view.ellipsize = TextUtils.TruncateAt.END
        if (override.shadow ?: prefs.labelShadow) {
            view.setShadowLayer(3f, 0f, 1f, 0xB3000000.toInt())
        } else {
            view.setShadowLayer(0f, 0f, 0f, 0)
        }
    }

    // Whether a surface draws its labels on one line. The dock is a row of
    // narrow slots sized to fit however many icons the dockCount pref asks
    // for, so a wrapped label there reads as a broken word ("Chro" over "me")
    // and doubles the dock's height; it defaults to one ellipsized line while
    // every other surface follows the global labelSingleLine pref. An explicit
    // per-context override still wins over both, so the two-line dock is a
    // switch away. Settings reads the same function, so the toggle it shows is
    // the value the dock actually renders with.
    fun singleLineFor(context: LabelContext, prefs: LauncherPrefs): Boolean =
        LabelConfig.decode(prefs.labelConfig).of(context).singleLine
            ?: (context == LabelContext.DOCK || prefs.labelSingleLine)

    fun parseColor(raw: String, fallback: Int = Color.WHITE): Int =
        if (raw.isEmpty()) fallback else runCatching { Color.parseColor(raw) }.getOrDefault(fallback)
}
