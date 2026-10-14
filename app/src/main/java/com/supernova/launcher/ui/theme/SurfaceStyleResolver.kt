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
    val cornerRadiiDp: FloatArray?,
    // Per-corner cut styles, same order; null = all round.
    val cornerCuts: List<com.supernova.launcher.data.CornerCut>? = null
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

    @Synchronized
    fun resolve(context: Context, prefs: LauncherPrefs): ResolvedStyles {
        val night = context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val key = Key(
            prefs.styleConfig, prefs.paletteSource, prefs.paletteSeed, night,
            prefs.accentColor, prefs.drawerBgColor, prefs.folderBgColor,
            prefs.drawerBgOpacity, prefs.dockBgOpacity, wallpaperGeneration
        )
        cachedStyles?.let { if (key == cachedKey) return it }

        val palette = paletteFor(context, prefs, key)
        val config = StyleConfig.decode(prefs.styleConfig)
        val accent = ThemeEngine.accentColor(context, AccentColor.fromName(prefs.accentColor))
        val themeText = context.getColor(R.color.textPrimary)
        val onWallpaper = context.getColor(R.color.onWallpaper)
        val themeSurface = context.getColor(R.color.surface)

        fun resolved(
            id: SurfaceId,
            defaultSurface: Int,
            defaultText: Int,
            // null keeps defaultSurface's own alpha channel.
            defaultAlpha: Float?
        ): ResolvedSurface {
            val style = config.styleFor(id)
            val surfaceBase = colorFor(style.surface, palette) ?: defaultSurface
            val alpha = style.alpha ?: defaultAlpha
            val surface = if (alpha != null) {
                ((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24) or (surfaceBase and 0x00FFFFFF)
            } else {
                surfaceBase
            }
            return ResolvedSurface(
                surface = surface,
                text = Contrast.ensure(
                    colorFor(style.text, palette) ?: defaultText, surfaceBase, style.minContrast
                ),
                accent = colorFor(style.accent, palette) ?: accent,
                cornerRadiiDp = style.cornerRadii?.let { radii ->
                    FloatArray(4) { radii.getOrElse(it) { 0f } }
                },
                cornerCuts = style.cornerCuts?.let { cuts ->
                    List(4) { com.supernova.launcher.data.CornerCut.fromName(cuts.getOrNull(it)) }
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

        val styles = ResolvedStyles(
            mapOf(
                SurfaceId.DRAWER to resolved(
                    SurfaceId.DRAWER, drawerLegacy, themeText, prefs.drawerBgOpacity
                ),
                SurfaceId.DOCK to resolved(
                    SurfaceId.DOCK, (0xFF shl 24) or DOCK_BASE_RGB, onWallpaper, prefs.dockBgOpacity
                ),
                SurfaceId.FOLDER to resolved(
                    SurfaceId.FOLDER, folderLegacy, themeText, null
                ),
                SurfaceId.POPUP to resolved(
                    SurfaceId.POPUP, themeSurface, themeText, null
                ),
                SurfaceId.SEARCH_BAR to resolved(
                    SurfaceId.SEARCH_BAR, themeSurface, themeText, null
                ),
                SurfaceId.SETTINGS to resolved(
                    SurfaceId.SETTINGS, context.getColor(R.color.background), themeText, null
                )
            )
        )
        cachedKey = key
        cachedStyles = styles
        return styles
    }

    private fun colorFor(spec: ColorSpec, palette: TonalPalette): Int? = when {
        spec.fixed != null -> runCatching { android.graphics.Color.parseColor(spec.fixed) }.getOrNull()
        spec.role != null && spec.tone != null -> palette.tone(spec.role, spec.tone)
        else -> null
    }

    private fun paletteFor(context: Context, prefs: LauncherPrefs, key: Key): TonalPalette {
        val paletteKey = key.copy(config = "", drawerBgColor = "", folderBgColor = "", drawerAlpha = 0f, dockAlpha = 0f)
        cachedPalette?.let { if (paletteKey == cachedPaletteKey) return it }
        val fallback = ThemeEngine.accentColor(context, AccentColor.fromName(prefs.accentColor))
        val palette = PaletteFactory.resolve(
            context, PaletteSourceMode.fromName(prefs.paletteSource), prefs.paletteSeed, fallback
        )
        cachedPaletteKey = paletteKey
        cachedPalette = palette
        return palette
    }
}
