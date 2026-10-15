package com.supernova.launcher.data

import com.supernova.launcher.data.Pixels.ALPHA_SHIFT
import com.supernova.launcher.data.Pixels.GREEN_SHIFT
import com.supernova.launcher.data.Pixels.RED_SHIFT
import com.supernova.launcher.data.Pixels.RGB_MASK
import com.supernova.launcher.data.Pixels.SHIFTS
import com.supernova.launcher.data.Pixels.alpha
import com.supernova.launcher.data.Pixels.channel
import com.supernova.launcher.data.Pixels.distance
import com.supernova.launcher.data.Pixels.near
import com.supernova.launcher.data.Pixels.neighbours
import com.supernova.launcher.data.Pixels.rgb

// An icon's backing - the tile behind its logo - found in its pixels, so
// glass can stand in for it. clear() takes a picture's backing from its edge
// (or a gap in it) inward, following a gradient a pixel at a time until the
// logo's own colours stop it, so a detail inside the logo in the same colour
// (the hole in Maps' pin) stays. clearMatching() takes a given colour from
// a foreground that repeats its background's (Maps' white tile), only where
// that background was cleared. Both then unmix the outline: a pixel there is
// part logo, part backing (anti-aliasing), and is left as the logo's colour
// at that share of opacity rather than as a pale rim.
//
// Plain ARGB ints (Bitmap.getPixels order, unpremultiplied), so it runs in
// unit tests without android.graphics.
internal object BakedBacking {

    // What was cleared: its mean colour, for the pane's tint; the backing
    // colour it started from; which pixels it took; and the box they span
    // (LEFT, TOP, RIGHT, BOTTOM), which tells a tile (it spans the icon)
    // from an object.
    class Cleared(val color: Int, val backing: Int, val taken: BooleanArray, val box: IntArray)

    // How far each channel may sit from the backing colour and still be it:
    // wide enough to take a tile's faint shading.
    private const val TOLERANCE = 40
    // How far a pixel may differ from the cleared neighbour it is reached
    // from: a gradient changes this slowly, a logo's edge does not.
    private const val STEP = 10
    // A neighbour below this alpha is a gap the backing shows through to;
    // a seed must be at least this solid.
    private const val GAP_ALPHA = 32
    private const val SEED_ALPHA = 128
    // Less backing than this share of the picture is no tile at all (busy
    // art all the way to the edge), and the picture is left whole.
    private const val MIN_SHARE = 0.15f
    // How many pixels deep into the logo the outline is unmixed.
    private const val FRINGE_DEPTH = 2
    // Unmixed below this opacity, a pixel is all backing and goes; from
    // this share of logo up it is solid logo.
    private const val MIN_ALPHA = 8
    private const val SOLID_SHARE = 0.95f

    // Clears the backing in place; null, with the pixels untouched, when
    // there is none worth the name.
    fun clear(pixels: IntArray, size: Int): Cleared? {
        val original = pixels.copyOf()
        val seeds = pixels.indices.filter {
            alpha(original[it]) >= SEED_ALPHA && Pixels.reachable(original, size, it, GAP_ALPHA)
        }
        val fill = seeds.takeIf { it.isNotEmpty() }?.let { found ->
            val backing = dominant(original, found)
            Fill(original, size, backing) { pixel, from ->
                near(pixel, backing, TOLERANCE) || near(pixel, from, STEP)
            }.apply {
                found.filter { near(original[it], backing, TOLERANCE) }.forEach(::add)
                run()
            }
        }
        val solid = original.count { alpha(it) >= SEED_ALPHA }
        return fill?.takeIf { it.count > 0 && it.count >= solid * MIN_SHARE }?.let {
            it.clearInto(pixels)
            it.result()
        }
    }

    // Clears backing-coloured pixels a foreground repeats over a background
    // that was cleared (allowed), reached from the layer's edge or a gap.
    // True when it took any.
    fun clearMatching(pixels: IntArray, size: Int, backing: Int, allowed: BooleanArray): Boolean {
        val original = pixels.copyOf()
        val fill = Fill(original, size, backing) { pixel, _ -> near(pixel, backing, TOLERANCE) }
        fill.allowed = allowed
        pixels.indices
            .filter { allowed[it] && alpha(original[it]) > 0 && near(original[it], backing, TOLERANCE) }
            .filter { Pixels.reachable(original, size, it, GAP_ALPHA) }
            .forEach(fill::add)
        fill.run()
        if (fill.count > 0) fill.clearInto(pixels)
        return fill.count > 0
    }

    // The box round the picture's solid pixels, as LEFT, TOP, RIGHT,
    // BOTTOM; null for an empty picture.
    fun contentBounds(pixels: IntArray, size: Int): IntArray? {
        var left = size
        var top = size
        var right = -1
        var bottom = -1
        for (index in pixels.indices) {
            if (alpha(pixels[index]) < SEED_ALPHA) continue
            val x = index % size
            val y = index / size
            if (x < left) left = x
            if (x > right) right = x
            if (y < top) top = y
            if (y > bottom) bottom = y
        }
        return if (right < 0) null else intArrayOf(left, top, right, bottom)
    }

    // Whether a cleared backing reaches every side of a box, within slack
    // pixels: a tile does, a free-standing object's body doesn't.
    fun spans(cleared: Cleared, content: IntArray, slack: Int): Boolean =
        cleared.box[LEFT] - content[LEFT] <= slack && cleared.box[TOP] - content[TOP] <= slack &&
            content[RIGHT] - cleared.box[RIGHT] <= slack && content[BOTTOM] - cleared.box[BOTTOM] <= slack

    // A tile's backing may stop this share of the size short of a side and
    // still count as spanning it.
    const val TILE_SLACK = 0.06f

    // contentBounds' order.
    const val LEFT = 0
    const val TOP = 1
    const val RIGHT = 2
    const val BOTTOM = 3

    // The commonest colour among the seeds, at 4 bits a channel, then the
    // mean of the seeds in that bucket.
    private fun dominant(pixels: IntArray, seeds: List<Int>): Int {
        val bucketOf = { pixel: Int ->
            (((pixel shr RED_SHIFT) and BUCKET_MASK) shl BUCKET_RED) or
                (((pixel shr GREEN_SHIFT) and BUCKET_MASK) shl BUCKET_GREEN) or
                ((pixel and BUCKET_MASK) shr BUCKET_BLUE)
        }
        val top = seeds.groupingBy { bucketOf(pixels[it]) }.eachCount().maxBy { it.value }.key
        val members = seeds.filter { bucketOf(pixels[it]) == top }
        val red = members.sumOf { channel(pixels[it], RED_SHIFT) } / members.size
        val green = members.sumOf { channel(pixels[it], GREEN_SHIFT) } / members.size
        val blue = members.sumOf { channel(pixels[it], 0) } / members.size
        return rgb(red, green, blue)
    }

    // The flood: what it has taken, the colour sums and the box.
    private class Fill(
        private val pixels: IntArray,
        private val size: Int,
        private val backing: Int,
        private val accepts: (pixel: Int, from: Int) -> Boolean
    ) {
        val taken = BooleanArray(pixels.size)
        var allowed: BooleanArray? = null
        private val queue = IntArray(pixels.size)
        var count = 0
            private set
        private var red = 0L
        private var green = 0L
        private var blue = 0L
        private var weight = 0L
        private var left = size
        private var top = size
        private var right = -1
        private var bottom = -1

        fun add(index: Int) {
            if (taken[index]) return
            taken[index] = true
            queue[count++] = index
            val pixel = pixels[index]
            val alpha = alpha(pixel).toLong()
            red += channel(pixel, RED_SHIFT) * alpha
            green += channel(pixel, GREEN_SHIFT) * alpha
            blue += channel(pixel, 0) * alpha
            weight += alpha
            val x = index % size
            val y = index / size
            if (x < left) left = x
            if (x > right) right = x
            if (y < top) top = y
            if (y > bottom) bottom = y
        }

        fun run() {
            var head = 0
            while (head < count) {
                val index = queue[head++]
                for (next in neighbours(index, size)) {
                    if (next >= 0 && takes(next, index)) add(next)
                }
            }
        }

        private fun takes(index: Int, from: Int): Boolean =
            !taken[index] && allowed?.get(index) != false && alpha(pixels[index]) > 0 &&
                accepts(pixels[index], pixels[from])

        // Clears what was taken, then unmixes the outline round it.
        fun clearInto(target: IntArray) {
            for (i in 0 until count) target[queue[i]] = 0
            Unmix(pixels, size, taken, target).run()
        }

        fun result(): Cleared {
            val color = if (weight == 0L) {
                backing
            } else {
                rgb((red / weight).toInt(), (green / weight).toInt(), (blue / weight).toInt())
            }
            return Cleared(color, backing, taken, intArrayOf(left, top, right, bottom))
        }
    }

    // The outline left round a cleared backing: each pixel there is read as
    // backing B mixed with logo L - B from the cleared neighbour it borders,
    // L from whichever of it and its uncleared neighbours is furthest from B
    // - and rewritten as L at the share of opacity the mix gives. A solid
    // logo pixel comes out unchanged. Works FRINGE_DEPTH pixels in.
    private class Unmix(
        private val original: IntArray,
        private val size: Int,
        private val taken: BooleanArray,
        private val target: IntArray
    ) {
        // The backing each pixel in the band borders.
        private val backingOf = IntArray(original.size)
        private val done = taken.copyOf()

        fun run() {
            var frontier = taken.indices.filter { taken[it] }
            for (index in frontier) backingOf[index] = original[index]
            repeat(FRINGE_DEPTH) {
                val next = ArrayList<Int>()
                for (from in frontier) {
                    for (index in neighbours(from, size)) {
                        if (index < 0 || done[index]) continue
                        done[index] = true
                        backingOf[index] = backingOf[from]
                        next.add(index)
                    }
                }
                // Only a mixed pixel leads further in: past solid logo is the
                // logo's inside, which keeps its colours (Maps' pin hole).
                frontier = next.filter(::soften)
            }
        }

        // Rewrites one outline pixel; true when it was a mix, not solid logo.
        private fun soften(index: Int): Boolean {
            val pixel = original[index]
            if (alpha(pixel) == 0) return false
            val backing = backingOf[index]
            val logo = logoNear(index, backing)
            val share = shareOf(pixel, backing, logo)
            val alpha = (alpha(pixel) * share).toInt()
            target[index] = if (alpha < MIN_ALPHA) 0 else (alpha shl ALPHA_SHIFT) or (logo and RGB_MASK)
            return share < SOLID_SHARE
        }

        // Of the pixel and its neighbours outside the cleared backing, the
        // one furthest from the backing: the logo's own colour nearby.
        private fun logoNear(index: Int, backing: Int): Int {
            var best = original[index]
            var bestDistance = distance(best, backing)
            for (n in neighbours(index, size)) {
                if (n < 0 || taken[n] || alpha(original[n]) == 0) continue
                val d = distance(original[n], backing)
                if (d > bestDistance) {
                    best = original[n]
                    bestDistance = d
                }
            }
            return best
        }

        // How much of the pixel is logo: its offset from the backing along
        // the line to the logo's colour, 0..1. A logo too close to the
        // backing to tell apart reads as backing.
        private fun shareOf(pixel: Int, backing: Int, logo: Int): Float {
            val span = distance(logo, backing)
            if (span < MIN_SPAN) return 0f
            var dot = 0
            for (shift in SHIFTS) {
                val offset = channel(pixel, shift) - channel(backing, shift)
                dot += offset * (channel(logo, shift) - channel(backing, shift))
            }
            return (dot.toFloat() / span).coerceIn(0f, 1f)
        }
    }

    // A logo must sit this far (squared, channels summed) from the backing
    // to be told apart from it: about 30 levels in one channel.
    private const val MIN_SPAN = 900
    // 4-bit buckets: each channel's top nibble, packed 12 bits wide.
    private const val BUCKET_MASK = 0xF0
    private const val BUCKET_RED = 4
    private const val BUCKET_GREEN = 0
    private const val BUCKET_BLUE = 4
}
