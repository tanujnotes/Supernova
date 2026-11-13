package com.supernova.launcher.onboarding

import android.graphics.Color
import com.supernova.launcher.R
import com.supernova.launcher.data.DeviceProfile
import com.supernova.launcher.data.IconGlass
import com.supernova.launcher.data.IconShape
import com.supernova.launcher.data.LauncherPrefs
import com.supernova.launcher.data.Pref
import com.supernova.launcher.data.PrefWrite
import com.supernova.launcher.ui.LabelFont
import com.supernova.launcher.ui.theme.AccentColor
import com.supernova.launcher.ui.theme.PaletteSourceMode
import kotlin.math.abs
import kotlin.random.Random

// What each first-run step offers, and the settings each choice writes. Every
// choice is a bundle of settings Supernova already has, so Settings shows and
// changes all of it afterwards.
internal object OnboardingChoices {

    // The shapes the Icon shape step offers, in its order: all of them but
    // Crop (a square that only trims) and Custom (built in Settings).
    val SHAPES = listOf(
        IconShape.SYSTEM, IconShape.CIRCLE, IconShape.SQUIRCLE, IconShape.ROUNDED_SQUARE, IconShape.SQUARE,
        IconShape.TEARDROP, IconShape.PEBBLE, IconShape.VESSEL, IconShape.FLOWER, IconShape.PLATFORM,
        IconShape.HEXAGON, IconShape.HEXAGON_ROUNDED, IconShape.PENTAGON, IconShape.HEPTAGON, IconShape.OCTAGON
    )

    fun shapeWrites(shape: IconShape): List<PrefWrite<*>> = listOf(PrefWrite(Pref.iconShape, shape))

    // The icon finish: themed icons, or one of the glass styles.
    enum class Finish(val labelRes: Int, val themed: Boolean, val glass: IconGlass) {
        ORIGINAL(R.string.onboarding_finish_original, themed = false, glass = IconGlass.OFF),
        TINTED(R.string.onboarding_finish_tinted, themed = true, glass = IconGlass.OFF),
        FROSTED(R.string.icon_glass_frosted, themed = false, glass = IconGlass.FROSTED),
        CLEAR(R.string.icon_glass_clear, themed = false, glass = IconGlass.CLEAR),
        SMOKED(R.string.icon_glass_smoked, themed = false, glass = IconGlass.SMOKED);

        fun writes(): List<PrefWrite<*>> = listOf(
            PrefWrite(Pref.themedIcons, themed),
            PrefWrite(Pref.iconGlass, glass)
        )

        companion object {
            fun of(prefs: LauncherPrefs): Finish? =
                entries.firstOrNull { it.glass == prefs.iconGlass && it.themed == prefs.themedIcons }
        }
    }

    // How much a page holds: the screen's own grid, one row and column
    // fewer with bigger icons, or one more of each.
    enum class Density(val labelRes: Int, val step: Int, val iconScale: Float) {
        ROOMY(R.string.onboarding_density_roomy, step = -1, iconScale = ROOMY_ICON_SCALE),
        BALANCED(R.string.onboarding_density_balanced, step = 0, iconScale = 1f),
        PACKED(R.string.onboarding_density_packed, step = 1, iconScale = 1f);

        fun cols(profile: DeviceProfile): Int = profile.gridCols + step

        fun rows(profile: DeviceProfile): Int = profile.gridRows + step

        fun writes(profile: DeviceProfile): List<PrefWrite<*>> {
            val scale = profile.iconScale * iconScale
            return listOf(
                PrefWrite(Pref.gridCols, cols(profile)),
                PrefWrite(Pref.gridRows, rows(profile)),
                PrefWrite(Pref.iconScale, scale),
                PrefWrite(Pref.dockIconScale, scale)
            )
        }

        companion object {
            fun of(prefs: LauncherPrefs, profile: DeviceProfile): Density? =
                entries.firstOrNull { prefs.gridCols == it.cols(profile) && prefs.gridRows == it.rows(profile) }
        }
    }

    // The names under the icons: a lettering, or none.
    enum class Names(val labelRes: Int, val font: LabelFont?) {
        STANDARD(R.string.onboarding_names_standard, LabelFont.DEFAULT),
        NARROW(R.string.onboarding_names_narrow, LabelFont.CONDENSED),
        SERIF(R.string.onboarding_names_serif, LabelFont.SERIF),
        MONO(R.string.onboarding_names_mono, LabelFont.MONOSPACE),
        HIDDEN(R.string.onboarding_names_hidden, null);

        fun writes(): List<PrefWrite<*>> = if (font == null) {
            listOf(PrefWrite(Pref.showLabels, false))
        } else {
            listOf(PrefWrite(Pref.showLabels, true), PrefWrite(Pref.labelFont, font))
        }

        companion object {
            fun of(prefs: LauncherPrefs): Names =
                if (!prefs.showLabels) HIDDEN else entries.first { it.font == prefs.labelFont }
        }
    }

    // A starting point: one tap that sets every later step. None of them
    // moves the search bar or gives the dock a background: both stay as
    // they are (on a fresh install, the bar under the dock and no
    // background).
    enum class Look(
        val labelRes: Int,
        val shape: IconShape,
        val finish: Finish,
        val density: Density,
        val names: Names
    ) {
        CLASSIC(
            R.string.onboarding_look_classic, IconShape.SYSTEM, Finish.ORIGINAL, Density.BALANCED,
            Names.STANDARD
        ),
        GLASS(
            R.string.onboarding_look_glass, IconShape.ROUNDED_SQUARE, Finish.CLEAR, Density.BALANCED,
            Names.STANDARD
        ),
        QUIET(
            R.string.onboarding_look_quiet, IconShape.CIRCLE, Finish.TINTED, Density.ROOMY,
            Names.HIDDEN
        ),
        BOLD(
            R.string.onboarding_look_bold, IconShape.TEARDROP, Finish.FROSTED, Density.ROOMY,
            Names.SERIF
        );

        fun writes(profile: DeviceProfile): List<PrefWrite<*>> = compose(profile, shape, finish, density, names)

        fun matches(prefs: LauncherPrefs, profile: DeviceProfile): Boolean =
            prefs.iconShape == shape && Finish.of(prefs) == finish && Density.of(prefs, profile) == density &&
                Names.of(prefs) == names

        companion object {
            fun of(prefs: LauncherPrefs, profile: DeviceProfile): Look? =
                entries.firstOrNull { it.matches(prefs, profile) }
        }
    }

    // A look nobody picked: any shape but the system's, and any of each
    // other step's choices, colour included.
    fun shuffleWrites(profile: DeviceProfile, accents: Map<AccentColor, Int>, random: Random): List<PrefWrite<*>> {
        val accent = AccentColor.entries.random(random)
        return compose(
            profile,
            SHAPES.drop(1).random(random),
            Finish.entries.random(random),
            Density.entries.random(random),
            Names.entries.random(random)
        ) + accentWrites(accent, accents.getValue(accent))
    }

    private fun compose(
        profile: DeviceProfile,
        shape: IconShape,
        finish: Finish,
        density: Density,
        names: Names
    ): List<PrefWrite<*>> = shapeWrites(shape) + finish.writes() + density.writes(profile) + names.writes()

    // A colour: the accent, and the palette the launcher's surfaces and
    // tinted icons are drawn from, seeded with it.
    fun accentWrites(accent: AccentColor, color: Int): List<PrefWrite<*>> = listOf(
        PrefWrite(Pref.accentColor, accent),
        PrefWrite(Pref.paletteSource, PaletteSourceMode.CUSTOM),
        PrefWrite(Pref.paletteSeed, hex(color))
    )

    // The wallpaper's colours for the palette, and the accent closest to
    // them for the controls.
    fun wallpaperWrites(wallpaperColor: Int?, accents: Map<AccentColor, Int>): List<PrefWrite<*>> = listOf(
        PrefWrite(Pref.paletteSource, PaletteSourceMode.WALLPAPER),
        PrefWrite(Pref.accentColor, wallpaperColor?.let { nearestAccent(it, accents) } ?: AccentColor.BLUE)
    )

    // The colour step's pick: an accent when the palette is seeded with one
    // of them, else null, the wallpaper (the system palette follows it too).
    fun colourOf(prefs: LauncherPrefs, accents: Map<AccentColor, Int>): AccentColor? {
        if (prefs.paletteSource != PaletteSourceMode.CUSTOM) return null
        return accents.entries.firstOrNull { hex(it.value).equals(prefs.paletteSeed, ignoreCase = true) }?.key
    }

    // By hue, for a colourful wallpaper; a grey one keeps blue.
    fun nearestAccent(color: Int, accents: Map<AccentColor, Int>): AccentColor {
        val hsv = FloatArray(HSV)
        Color.colorToHSV(color, hsv)
        if (hsv[1] < GREY_SATURATION) return AccentColor.BLUE
        val hue = hsv[0]
        return accents.minBy { (_, value) ->
            val other = FloatArray(HSV)
            Color.colorToHSV(value, other)
            val apart = abs(other[0] - hue)
            minOf(apart, FULL_TURN - apart)
        }.key
    }

    private fun hex(color: Int): String = String.format(java.util.Locale.ROOT, "#%06X", color and RGB)

    private const val ROOMY_ICON_SCALE = 1.2f
    private const val HSV = 3
    private const val GREY_SATURATION = 0.15f
    private const val FULL_TURN = 360f
    private const val RGB = 0xFFFFFF
}
