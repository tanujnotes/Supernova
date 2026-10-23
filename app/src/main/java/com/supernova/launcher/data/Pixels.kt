package com.supernova.launcher.data

// ARGB int arithmetic for BakedBacking: unpremultiplied colours in
// Bitmap.getPixels order, a picture size pixels square.
internal object Pixels {

    const val ALPHA_SHIFT = 24
    const val RED_SHIFT = 16
    const val GREEN_SHIFT = 8
    const val CHANNEL = 0xFF
    const val RGB_MASK = 0xFFFFFF
    val SHIFTS = intArrayOf(RED_SHIFT, GREEN_SHIFT, 0)
    private const val OPAQUE_ALPHA = 0xFF shl ALPHA_SHIFT

    fun alpha(pixel: Int): Int = pixel ushr ALPHA_SHIFT

    fun channel(pixel: Int, shift: Int): Int = (pixel shr shift) and CHANNEL

    fun rgb(red: Int, green: Int, blue: Int): Int =
        OPAQUE_ALPHA or (red shl RED_SHIFT) or (green shl GREEN_SHIFT) or blue

    // Squared distance between two colours.
    fun distance(a: Int, b: Int): Int {
        var sum = 0
        for (shift in SHIFTS) {
            val d = channel(a, shift) - channel(b, shift)
            sum += d * d
        }
        return sum
    }

    // Every channel within limit of the other colour's.
    fun near(a: Int, b: Int, limit: Int): Boolean =
        SHIFTS.all { kotlin.math.abs(channel(a, it) - channel(b, it)) <= limit }

    // On the picture's edge, or beside a pixel below gapAlpha.
    fun reachable(pixels: IntArray, size: Int, index: Int, gapAlpha: Int): Boolean =
        neighbours(index, size).any { it < 0 || alpha(pixels[it]) < gapAlpha }

    // The four neighbours, -1 for each that falls off the picture.
    fun neighbours(index: Int, size: Int): IntArray {
        val x = index % size
        val y = index / size
        return intArrayOf(
            if (x > 0) index - 1 else -1,
            if (x < size - 1) index + 1 else -1,
            if (y > 0) index - size else -1,
            if (y < size - 1) index + size else -1
        )
    }
}
