package com.supernova.launcher.ui

import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView
import com.supernova.launcher.R
import com.supernova.launcher.data.LauncherPrefs
import com.supernova.launcher.data.Pref
import com.supernova.launcher.data.PrefWrite
import com.supernova.launcher.data.SettingEnumSerializer
import com.supernova.launcher.ui.theme.Styles
import com.supernova.launcher.ui.theme.SurfaceId
import java.util.EnumMap

@kotlinx.serialization.Serializable(with = LabelFont.Names::class)
enum class LabelFont {
    DEFAULT,
    CONDENSED,
    SERIF,
    MONOSPACE;

    // Its name in the store and in a backup, read back leniently.
    object Names : SettingEnumSerializer<LabelFont>("LabelFont", entries, DEFAULT)

    companion object {
        fun fromName(name: String): LabelFont = Names.parse(name)
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
        context: LabelContext = LabelContext.DESKTOP,
        // A tile in a grid of rows (drawer, folder), not a list row.
        inGrid: Boolean = true
    ) {
        val override = LabelConfig.decode(prefs.labelConfig).of(context)
        val surface = when (context) {
            LabelContext.DRAWER -> SurfaceId.DRAWER
            LabelContext.FOLDER -> SurfaceId.FOLDER
            LabelContext.DOCK ->
                SurfaceId.DOCK.takeIf { prefs.dockBgStyle != DockBgStyle.NONE }
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
        view.typeface = typefaceFor(override.font?.let(LabelFont::fromName) ?: prefs.labelFont)
        applyLines(view, prefs, context, inGrid)
        if (override.shadow ?: prefs.labelShadow) {
            view.setShadowLayer(3f, 0f, 1f, 0xB3000000.toInt())
        } else {
            view.setShadowLayer(0f, 0f, 0f, 0)
        }
    }

    private fun applyLines(view: TextView, prefs: LauncherPrefs, context: LabelContext, inGrid: Boolean) {
        // Each surface declares its own maxLines in XML; remember that value
        // on the view so single-line can be undone on a recycled bind.
        val surfaceMax = view.getTag(R.id.tag_default_max_lines) as? Int
            ?: view.maxLines.also { view.setTag(R.id.tag_default_max_lines, it) }
        val targetMax = if (singleLineFor(context, prefs)) 1 else surfaceMax
        if (view.maxLines != targetMax) view.maxLines = targetMax
        reserveLines(view, if (inGrid && evenRows(context, prefs)) targetMax else 0)
    }

    // Whether a surface draws its labels on one line. The dock is a row of
    // narrow slots sized to fit however many icons the dockCount pref asks
    // for, so a wrapped label there reads as a broken word ("Chro" over "me")
    // and doubles the dock's height; it defaults to one line while
    // every other surface follows the global labelSingleLine pref. An explicit
    // per-context override still wins over both, so the two-line dock is a
    // switch away. Settings reads the same function, so the toggle it shows is
    // the value the dock actually renders with.
    fun singleLineFor(context: LabelContext, prefs: LauncherPrefs): Boolean =
        LabelConfig.decode(prefs.labelConfig).of(context).singleLine
            ?: (context == LabelContext.DOCK || prefs.labelSingleLine)

    // Whether a surface's grid rows keep room for every label line it allows
    // (#347): rows of short names otherwise sit closer together than rows
    // with a wrapped one. Only the drawer and folders lay labels out in rows
    // of varying height; the desktop and dock place icons by cell.
    // Each page has its own switch, under its labels.
    fun evenRows(context: LabelContext, prefs: LauncherPrefs): Boolean = when (context) {
        LabelContext.DRAWER -> prefs.drawerEvenRows || prefs.labelEvenRows
        LabelContext.FOLDER -> prefs.folderEvenRows || prefs.labelEvenRows
        else -> false
    }

    // One page's switch set to `on`. The old shared switch goes, and the
    // other page keeps what it showed.
    fun evenRowsWrites(context: LabelContext, on: Boolean, prefs: LauncherPrefs): List<PrefWrite<*>> {
        val drawer = if (context == LabelContext.DRAWER) on else evenRows(LabelContext.DRAWER, prefs)
        val folder = if (context == LabelContext.FOLDER) on else evenRows(LabelContext.FOLDER, prefs)
        return listOf(
            PrefWrite(Pref.labelEvenRows, false),
            PrefWrite(Pref.drawerEvenRows, drawer),
            PrefWrite(Pref.folderEvenRows, folder)
        )
    }

    // Holds a label at `lines` lines tall, 0 for its text's own height. A
    // reserved label keeps its text at the top, under the icon, so a short
    // name lines up with a wrapped neighbour's first line.
    // Only undoes a reserve this set, so a label's own minimum stays.
    private fun reserveLines(view: TextView, lines: Int) {
        val old = view.minLines
        if (old == lines || (lines <= 1 && old <= 1)) return
        view.minLines = lines
        val horizontal = view.gravity and Gravity.HORIZONTAL_GRAVITY_MASK
        view.gravity = horizontal or if (lines > 1) Gravity.TOP else Gravity.CENTER_VERTICAL
    }

    // Caps a label at `lines` lines, dropping any reserve above that: a
    // tight tile's single line must not keep a second line's room.
    fun capLines(view: TextView, lines: Int) {
        if (view.maxLines != lines) view.maxLines = lines
        if (view.minLines > lines) reserveLines(view, lines)
    }

    fun parseColor(raw: String, fallback: Int = Color.WHITE): Int =
        if (raw.isEmpty()) fallback else runCatching { Color.parseColor(raw) }.getOrDefault(fallback)
}
