package com.supernova.launcher.data

// The glyph a generated themed icon draws for an icon that ships no
// monochrome layer, read off its picture: ARGB ints, row by row, covering
// the icon's whole 108dp canvas. The result is one alpha (0-255) per pixel.
object ThemedGlyph {

    // An app's: its plain silhouette when that reads as a glyph, else the
    // ink of a solid tile (a white logo on an orange square, whose silhouette
    // is the whole square), else null. A photo or a gradient tile has no
    // glyph to take, and the app keeps its own colours.
    fun forApp(pixels: IntArray, width: Int, height: Int): IntArray? {
        val plain = plainAlpha(pixels)
        if (reads(plain, width, height)) return plain
        return SilhouetteMask.of(pixels, width, height)?.takeIf { reads(it, width, height) }
    }

    // A shortcut's: the ink of a solid tile, else its silhouette.
    fun forShortcut(pixels: IntArray, width: Int, height: Int): IntArray =
        SilhouetteMask.of(pixels, width, height) ?: plainAlpha(pixels)

    // The share (0-1) of what an icon shape shows - the middle 72dp of the
    // 108dp canvas - that the glyph covers.
    fun coverage(alpha: IntArray, width: Int, height: Int): Float {
        val (left, top) = width / VISIBLE_INSET to height / VISIBLE_INSET
        val (right, bottom) = width - left to height - top
        var sum = 0L
        for (y in top until bottom) {
            for (x in left until right) sum += alpha[y * width + x]
        }
        val area = (right - left).toLong() * (bottom - top)
        return if (area == 0L) 0f else sum.toFloat() / (FULL * area)
    }

    private fun reads(alpha: IntArray, width: Int, height: Int): Boolean =
        coverage(alpha, width, height) in MIN_COVERAGE..MAX_COVERAGE

    private fun plainAlpha(pixels: IntArray) = IntArray(pixels.size) { pixels[it] ushr ALPHA_SHIFT }

    private const val FULL = 255
    private const val ALPHA_SHIFT = 24

    // 18dp of the 108dp canvas on each side lies outside every icon shape.
    private const val VISIBLE_INSET = 6

    // A glyph covering almost nothing is no glyph; one covering more than
    // half the shape is a tile or a picture, which tints to a blank plate.
    private const val MIN_COVERAGE = 0.01f
    private const val MAX_COVERAGE = 0.5f
}
