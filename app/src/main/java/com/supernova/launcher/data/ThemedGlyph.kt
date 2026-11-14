package com.supernova.launcher.data

// The glyph a generated themed icon draws for an icon that ships no
// monochrome layer, read off its picture: ARGB ints, row by row, covering
// the icon's whole 108dp canvas. The result is one alpha (0-255) per pixel.
object ThemedGlyph {

    // An app's: its plain silhouette when that reads as a glyph, else the
    // ink of a solid tile (a white logo on an orange square, whose silhouette
    // is the whole square), else null. A photo, a gradient tile or a block
    // of colours (a stack of books) has no glyph to take, and the app gets
    // a tinted copy instead.
    fun forApp(pixels: IntArray, width: Int, height: Int): IntArray? {
        val plain = plainAlpha(pixels)
        if (reads(plain, width, height) && !isBlock(plain, width, height)) return plain
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

    // A picture with no glyph to take, redrawn in the plate and glyph
    // colours: each pixel's lightness, stretched over the picture's own
    // range, runs from the darker colour to the lighter, so its detail stays
    // readable. A picture of one flat shade is drawn in the glyph colour, not
    // stretched (it would vanish into the plate). Alpha is kept; clear
    // pixels stay clear.
    fun tinted(pixels: IntArray, plate: Int, glyph: Int): IntArray {
        val (dark, light) = if (lightness(plate) <= lightness(glyph)) plate to glyph else glyph to plate
        var low = FULL
        var high = 0
        pixels.forEach {
            if (it ushr ALPHA_SHIFT > 0) {
                low = minOf(low, lightness(it))
                high = maxOf(high, lightness(it))
            }
        }
        val span = high - low
        return IntArray(pixels.size) { i ->
            val p = pixels[i]
            val alpha = p ushr ALPHA_SHIFT
            when {
                alpha == 0 -> 0
                span < MIN_SPAN -> (alpha shl ALPHA_SHIFT) or (glyph and RGB_BITS)
                else -> {
                    val t = ((lightness(p) - low) * FULL / span).coerceIn(0, FULL)
                    (alpha shl ALPHA_SHIFT) or mix(dark, light, t)
                }
            }
        }
    }

    // Whether a layer is one plain colour: every drawn pixel near its
    // commonest colour, bar a few stray ones (an anti-aliased rim).
    fun isPlain(pixels: IntArray): Boolean {
        val drawn = pixels.filter { it ushr ALPHA_SHIFT > 0 }
        val common = drawn.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: return true
        val off = drawn.count { !Pixels.near(it, common, PLAIN_TOLERANCE) }
        return off <= drawn.size * PLAIN_STRAYS
    }

    // A silhouette that fills the ellipse inscribed in its own bounds - a
    // square, a circle, a squircle - is a block: its shape says nothing of
    // the logo drawn inside it.
    private fun isBlock(alpha: IntArray, width: Int, height: Int): Boolean {
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        for (i in alpha.indices) {
            if (alpha[i] < HALF) continue
            val (x, y) = i % width to i / width
            left = minOf(left, x)
            right = maxOf(right, x)
            top = minOf(top, y)
            bottom = maxOf(bottom, y)
        }
        if (right < left) return false
        val (rx, ry) = (right - left + 1) / 2f to (bottom - top + 1) / 2f
        val (cx, cy) = left + rx to top + ry
        var inside = 0
        var filled = 0
        for (y in top..bottom) {
            for (x in left..right) {
                val (dx, dy) = (x + CENTRE - cx) / rx to (y + CENTRE - cy) / ry
                if (dx * dx + dy * dy > 1f) continue
                inside++
                if (alpha[y * width + x] >= HALF) filled++
            }
        }
        return inside > 0 && filled >= inside * BLOCK_FILL
    }

    // 0-255, weighted the way the eye sees the three channels.
    private fun lightness(p: Int): Int {
        val red = Pixels.channel(p, RED_SHIFT) * RED_WEIGHT
        val green = Pixels.channel(p, GREEN_SHIFT) * GREEN_WEIGHT
        return (red + green + Pixels.channel(p, 0) * BLUE_WEIGHT) / WEIGHTS
    }

    // `a` blended toward `b` by t/255, as an RGB int with no alpha.
    private fun mix(a: Int, b: Int, t: Int): Int =
        intArrayOf(RED_SHIFT, GREEN_SHIFT, 0).fold(0) { rgb, shift ->
            val c = Pixels.channel(a, shift) + (Pixels.channel(b, shift) - Pixels.channel(a, shift)) * t / FULL
            rgb or (c shl shift)
        }

    private fun reads(alpha: IntArray, width: Int, height: Int): Boolean =
        coverage(alpha, width, height) in MIN_COVERAGE..MAX_COVERAGE

    private fun plainAlpha(pixels: IntArray) = IntArray(pixels.size) { pixels[it] ushr ALPHA_SHIFT }

    private const val FULL = 255
    private const val HALF = 128
    private const val CENTRE = 0.5f
    private const val ALPHA_SHIFT = 24
    private const val RGB_BITS = 0xFFFFFF
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
    private const val RED_WEIGHT = 299
    private const val GREEN_WEIGHT = 587
    private const val BLUE_WEIGHT = 114
    private const val WEIGHTS = 1000

    // 18dp of the 108dp canvas on each side lies outside every icon shape.
    private const val VISIBLE_INSET = 6

    // A glyph covering almost nothing is no glyph; one covering more than
    // half the shape is a tile or a picture, which tints to a blank plate.
    private const val MIN_COVERAGE = 0.01f
    private const val MAX_COVERAGE = 0.5f

    // How much of its inscribed ellipse a block's silhouette fills.
    private const val BLOCK_FILL = 0.97f

    // Lightness range (0-255) under which a picture counts as one flat shade.
    private const val MIN_SPAN = 32

    // A plain layer: how far a channel may stray, and what share of pixels may.
    private const val PLAIN_TOLERANCE = 16
    private const val PLAIN_STRAYS = 0.01f
}
