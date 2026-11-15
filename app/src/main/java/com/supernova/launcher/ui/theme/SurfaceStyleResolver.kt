package com.supernova.launcher.ui.theme

import android.content.Context
import android.content.res.Configuration
import com.supernova.launcher.R
import com.supernova.launcher.data.LauncherPrefs
import com.supernova.launcher.ui.LabelStyler

// Final ints for one surface; the surface color carries its effective alpha.
class ResolvedSurface(
    val surface: Int,
    val text: Int,
    val accent: Int,
    // [topLeft, topRight, bottomRight, bottomLeft] in dp, or null for the
    // surface's built-in shape.
    val cornerRadiiDp: FloatArray?
)

class ResolvedStyles(private val surfaces: Map<SurfaceId, ResolvedSurface>) {
    fun of(id: SurfaceId): ResolvedSurface = surfaces.getValue(id)
}

// Memoized (config, palette, night) -> ints resolution. Everything renders
// from plain ints through the existing guarded chrome paths, so resolution
// cost is paid once per config/palette/theme change, never per frame.
object Styles {

    // Bumped by the wallpaper-colors listener; forces re-resolution.
    @Volatile
    private var wallpaperGeneration = 0

    fun onWallpaperChanged() {
        wallpaperGeneration++
    }

    private data class Key(
        val config: String,
        val source: String,
        val seed: String,
        val night: Boolean,
        val accent: String,
        val drawerBgColor: String,
        val folderBgColor: String,
        val drawerAlpha: Float,
        val dockAlpha: Float,
        val generation: Int
    )

    private var cachedKey: Key? = null
    private var cachedStyles: ResolvedStyles? = null
    private var cachedPaletteKey: Key? = null
    private var cachedPalette: TonalPalette? = null

    const val DOCK_BASE_RGB = 0x1A2230
    private const val DRAWER_FIELD_ALPHA = 0x24
    // Below this opacity (above 70% transparency) the drawer's labels and
    // text default to the wallpaper's light ones.
    const val SEE_THROUGH_ALPHA = 0.3f
    // The light theme's textPrimary, the dark text a light drawer gets in
    // either theme.
    const val DARK_TEXT = 0xFF11161D.toInt()

    fun isSeeThrough(alpha: Float): Boolean = alpha < SEE_THROUGH_ALPHA

    // The drawer's text with no text color set: read off the drawer's own
    // color, dark on a light one and light on a dark one whatever the
    // theme, or the wallpaper's (`onWallpaper`) when the drawer barely
    // covers it. Before, it followed the theme alone, so a dark drawer
    // color in light mode drew dark labels on it.
    fun drawerText(surfaceBase: Int, alpha: Float, onWallpaper: Int): Int =
        if (isSeeThrough(alpha)) onWallpaper else Contrast.readableOn(surfaceBase, DARK_TEXT, onWallpaper)

    // A surface's default text from its color and effective alpha (`auto`),
    // when it has one and the style sets no text color; null otherwise. It
    // keeps the style's minimum contrast from the surface, except when it is
    // read on the wallpaper the surface barely covers.
    private fun autoText(
        styled: Int?,
        surfaceBase: Int,
        alpha: Float?,
        auto: ((surfaceBase: Int, alpha: Float) -> Int)?,
        minContrast: Int?
    ): Int? {
        if (styled != null || auto == null) return null
        val effective = alpha ?: (android.graphics.Color.alpha(surfaceBase) / MAX_ALPHA)
        val text = auto(surfaceBase, effective)
        return if (isSeeThrough(effective)) text else Contrast.ensure(text, surfaceBase, minContrast)
    }

    private const val MAX_ALPHA = 255f

    @Synchronized
    fun resolve(context: Context, prefs: LauncherPrefs): ResolvedStyles {
        val night = context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val key = Key(
            prefs.styleConfig, prefs.paletteSource.name, prefs.paletteSeed, night,
            prefs.accentColor.name, prefs.drawerBgColor, prefs.folderBgColor,
            prefs.drawerBgOpacity, prefs.dockBgOpacity, wallpaperGeneration
        )
        cachedStyles?.let { if (key == cachedKey) return it }

        val palette = paletteFor(context, prefs, key)
        val config = StyleConfig.decode(prefs.styleConfig)
        val accent = ThemeEngine.accentColor(context, prefs.accentColor)
        val themeText = context.getColor(R.color.textPrimary)
        val onWallpaper = context.getColor(R.color.onWallpaper)
        val themeSurface = context.getColor(R.color.surface)

        fun resolved(
            id: SurfaceId,
            defaultSurface: Int,
            defaultText: Int,
            // null keeps defaultSurface's own alpha channel.
            defaultAlpha: Float?,
            defaultAccent: Int = accent,
            // The default text for the surface's color and effective alpha,
            // in place of defaultText; null keeps defaultText.
            textFor: ((surfaceBase: Int, alpha: Float) -> Int)? = null
        ): ResolvedSurface {
            val style = config.styleFor(id)
            val surfaceBase = colorFor(style.surface, palette) ?: defaultSurface
            val alpha = style.alpha ?: defaultAlpha
            val surface = if (alpha != null) {
                ((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24) or (surfaceBase and 0x00FFFFFF)
            } else {
                surfaceBase
            }
            val styledText = colorFor(style.text, palette)
            return ResolvedSurface(
                surface = surface,
                text = autoText(styledText, surfaceBase, alpha, textFor, style.minContrast)
                    ?: Contrast.ensure(styledText ?: defaultText, surfaceBase, style.minContrast),
                accent = colorFor(style.accent, palette) ?: defaultAccent,
                // Only the folder window has a radius, on the Folders page. One
                // an older Style tab stored for another surface goes unread.
                cornerRadiiDp = style.cornerRadii?.takeIf { id == SurfaceId.FOLDER }?.let { radii ->
                    FloatArray(4) { radii.getOrElse(it) { 0f } }
                }
            )
        }

        val drawerLegacy = LabelStyler.parseColor(
            prefs.drawerBgColor, context.getColor(R.color.drawerBackground)
        )
        // The FOLDER surface colors the folder *window*, so its default is the
        // opaque theme surface; the translucent preview-plate default lives in
        // the plate drawable itself (folderMatchWindowColor pulls this color in
        // explicitly).
        val folderLegacy = LabelStyler.parseColor(
            prefs.folderBgColor, themeSurface
        )

        val drawer = resolved(
            SurfaceId.DRAWER, drawerLegacy, themeText, prefs.drawerBgOpacity,
            textFor = { base, alpha -> drawerText(base, alpha, onWallpaper) }
        )

        val popup = resolved(SurfaceId.POPUP, themeSurface, themeText, null)
        val cardsText = cardText(config, onWallpaper)
        val cards = resolved(SurfaceId.CARDS, popup.surface, popup.text, null, popup.accent, cardsText)

        val styles = ResolvedStyles(
            mapOf(
                SurfaceId.DRAWER to drawer,
                // Unstyled, the drawer's field is a tint of the drawer's text.
                SurfaceId.DRAWER_SEARCH_BAR to resolved(
                    SurfaceId.DRAWER_SEARCH_BAR,
                    (DRAWER_FIELD_ALPHA shl 24) or (drawer.text and 0x00FFFFFF),
                    drawer.text, null, drawer.accent
                ),
                SurfaceId.DOCK to resolved(
                    SurfaceId.DOCK, (0xFF shl 24) or DOCK_BASE_RGB, onWallpaper, prefs.dockBgOpacity
                ),
                SurfaceId.FOLDER to resolved(
                    SurfaceId.FOLDER, folderLegacy, themeText, null
                ),
                SurfaceId.POPUP to popup,
                SurfaceId.CARDS to cards,
                SurfaceId.SEARCH_BAR to resolved(
                    SurfaceId.SEARCH_BAR, themeSurface, themeText, null
                ),
                SurfaceId.SETTINGS to resolved(
                    SurfaceId.SETTINGS, context.getColor(R.color.background), themeText, null
                ),
                SurfaceId.HOME to resolved(
                    SurfaceId.HOME, android.graphics.Color.TRANSPARENT, onWallpaper, null
                )
            )
        )
        cachedKey = key
        cachedStyles = styles
        return styles
    }

    // Cards wear the popup's colours until styled themselves. Once their
    // color or opacity is set, the text follows the plate (dark on a light
    // one), and the wallpaper's once the plate barely covers it.
    private fun cardText(config: StyleConfig, onWallpaper: Int): ((Int, Float) -> Int)? =
        if (config.styleFor(SurfaceId.CARDS).let { it.surface.isDefault && it.alpha == null }) {
            null
        } else {
            { base, alpha -> drawerText(base, alpha, onWallpaper) }
        }

    private fun colorFor(spec: ColorSpec, palette: TonalPalette): Int? = when {
        spec.fixed != null -> runCatching { android.graphics.Color.parseColor(spec.fixed) }.getOrNull()
        spec.role != null && spec.tone != null -> palette.tone(spec.role, spec.tone)
        else -> null
    }

    private fun paletteFor(context: Context, prefs: LauncherPrefs, key: Key): TonalPalette {
        val paletteKey = key.copy(config = "", drawerBgColor = "", folderBgColor = "", drawerAlpha = 0f, dockAlpha = 0f)
        cachedPalette?.let { if (paletteKey == cachedPaletteKey) return it }
        val fallback = ThemeEngine.accentColor(context, prefs.accentColor)
        val palette = PaletteFactory.resolve(
            context, prefs.paletteSource, prefs.paletteSeed, fallback
        )
        cachedPaletteKey = paletteKey
        cachedPalette = palette
        return palette
    }
}
