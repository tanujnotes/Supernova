package com.supernova.launcher.data

// Glass backgrounds (iconGlass pref): an adaptive icon's background layer -
// or the plate Reshape legacy icons bakes behind a legacy icon - becomes a
// translucent pane tinted with that layer's colour, and the foreground draws
// on it unchanged. A launcher can't read the wallpaper on Android 13+
// without all-files access, so nothing behind the pane is blurred: a style
// is a tint, a sheen across the top, a glow along the bottom and a lit rim.
enum class IconGlass(
    // How opaque the pane's tint is.
    val tintAlpha: Float,
    // Share of white mixed into the tint: frost reads milkier than clear glass.
    val haze: Float,
    // White at the top of the sheen and at the bottom of the glow.
    val sheen: Float,
    val glow: Float,
    // The rim at its brightest, top left.
    val edge: Float,
    // A fine grain across the pane, which is what reads as frost without a blur.
    val grain: Boolean
) {
    OFF(tintAlpha = 0f, haze = 0f, sheen = 0f, glow = 0f, edge = 0f, grain = false),
    FROSTED(tintAlpha = 0.55f, haze = 0.18f, sheen = 0.30f, glow = 0.10f, edge = 0.70f, grain = true),
    CLEAR(tintAlpha = 0.09f, haze = 0f, sheen = 0.30f, glow = 0.12f, edge = 0.95f, grain = false),
    // Dark glass whatever the app's colour.
    SMOKED(tintAlpha = 0.50f, haze = 0f, sheen = 0.14f, glow = 0.06f, edge = 0.38f, grain = false);

    companion object {
        fun fromName(name: String): IconGlass =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: OFF

        const val SMOKE_COLOR = 0xFF0C0E16.toInt()
    }
}
