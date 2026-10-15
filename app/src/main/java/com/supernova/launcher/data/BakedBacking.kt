package com.supernova.launcher.data

// Some apps paint their background colour into the foreground layer too -
// Play Store's whole layer is white but for the triangle, Maps puts a white
// tile behind its pin - and that copy covers a glass pane completely. It is
// the backing colour reaching the layer's edge or a transparent gap: clear
// it there and flood inward until the logo's own colours stop it, so a
// detail of the same colour inside the logo (the hole in Maps' pin) stays.
//
// Plain ARGB ints (Bitmap.getPixels order, unpremultiplied), so it runs in
// unit tests without android.graphics.
internal object BakedBacking {

    // How far each channel may sit from the backing colour and still be it:
    // wide enough to take the anti-aliased rim and faint shading of a plate.
    private const val TOLERANCE = 40
    // A neighbour below this alpha is a gap the backing shows through to.
    private const val GAP_ALPHA = 32

    // Clears the backing in place; true when it found any.
    fun clear(pixels: IntArray, size: Int, backing: Int): Boolean {
        val seen = BooleanArray(pixels.size)
        val queue = IntArray(pixels.size)
        var tail = 0
        for (index in pixels.indices) {
            if (matches(pixels[index], backing) && reachable(pixels, size, index)) {
                seen[index] = true
                queue[tail++] = index
            }
        }
        var head = 0
        while (head < tail) {
            val index = queue[head++]
            pixels[index] = 0
            for (next in neighbours(index, size)) {
                if (next >= 0 && !seen[next] && matches(pixels[next], backing)) {
                    seen[next] = true
                    queue[tail++] = next
                }
            }
        }
        return tail > 0
    }

    private fun matches(pixel: Int, backing: Int): Boolean =
        alpha(pixel) > 0 &&
            near(pixel shr RED_SHIFT, backing shr RED_SHIFT) &&
            near(pixel shr GREEN_SHIFT, backing shr GREEN_SHIFT) &&
            near(pixel, backing)

    private fun near(a: Int, b: Int): Boolean = kotlin.math.abs((a and CHANNEL) - (b and CHANNEL)) <= TOLERANCE

    // On the layer's edge, or beside a gap.
    private fun reachable(pixels: IntArray, size: Int, index: Int): Boolean =
        neighbours(index, size).any { it < 0 || alpha(pixels[it]) < GAP_ALPHA }

    // The four neighbours, -1 for each that falls off the layer.
    private fun neighbours(index: Int, size: Int): IntArray {
        val x = index % size
        val y = index / size
        return intArrayOf(
            if (x > 0) index - 1 else -1,
            if (x < size - 1) index + 1 else -1,
            if (y > 0) index - size else -1,
            if (y < size - 1) index + size else -1
        )
    }

    private fun alpha(pixel: Int): Int = pixel ushr ALPHA_SHIFT

    private const val ALPHA_SHIFT = 24
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
    private const val CHANNEL = 0xFF
}
