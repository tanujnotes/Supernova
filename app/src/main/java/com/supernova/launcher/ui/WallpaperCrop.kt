package com.supernova.launcher.ui

import kotlin.math.max
import kotlin.math.min

// The arithmetic behind the wallpaper crop screen, kept apart from the view
// and the decoder so it can be tested.
//
// A crop is a box in the image's own 0..1 coordinates, so the same box
// fits the small copy shown on screen and the larger one decoded to set.
object WallpaperCrop {

    // How much wider than the framed area a wallpaper the home screen
    // scrolls across may be: twice the screen, as the system lets a
    // wallpaper run for parallax.
    const val PARALLAX_SPAN = 2f

    // Most pixels decoded at once to set a wallpaper: a 48 MB bitmap.
    const val MAX_DECODE_PIXELS = 12_000_000L

    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width get() = right - left
        val height get() = bottom - top
    }

    // The smallest scale at which an image covers the frame.
    fun coverScale(imageWidth: Int, imageHeight: Int, frameWidth: Int, frameHeight: Int): Float =
        max(frameWidth.toFloat() / imageWidth, frameHeight.toFloat() / imageHeight)

    // An image edge's offset in the frame, held where the image still
    // covers it: an image span wider than the frame can sit anywhere
    // between flush right and flush left.
    fun clampOffset(offset: Float, imageSpan: Float, frameSpan: Float): Float =
        if (imageSpan <= frameSpan) (frameSpan - imageSpan) / 2f
        else offset.coerceIn(frameSpan - imageSpan, 0f)

    // The part of the image the frame shows, for an image of this size drawn
    // at this scale with its top-left corner at (offsetX, offsetY).
    @Suppress("LongParameterList")
    fun visible(
        imageWidth: Int,
        imageHeight: Int,
        scale: Float,
        offsetX: Float,
        offsetY: Float,
        frameWidth: Int,
        frameHeight: Int,
    ): Box {
        val spanX = imageWidth * scale
        val spanY = imageHeight * scale
        return Box(
            (-offsetX / spanX).coerceIn(0f, 1f),
            (-offsetY / spanY).coerceIn(0f, 1f),
            ((frameWidth - offsetX) / spanX).coerceIn(0f, 1f),
            ((frameHeight - offsetY) / spanY).coerceIn(0f, 1f),
        )
    }

    // The framed area widened for a home screen that scrolls its wallpaper,
    // whose home page sits at homeOffset (0..1) across it: the home page
    // shows just what is framed, and the pages on either side scroll across
    // the image beyond it, up to PARALLAX_SPAN of the framed width in all.
    // The screen's left edge sits at homeOffset of the way across the extra
    // width, so that much of it goes on the left and the rest on the right,
    // as far as the image reaches.
    fun widenForScrolling(crop: Box, homeOffset: Float): Box {
        val at = homeOffset.coerceIn(0f, 1f)
        var extra = crop.width * (PARALLAX_SPAN - 1f)
        if (at > 0f) extra = min(extra, crop.left / at)
        if (at < 1f) extra = min(extra, (1f - crop.right) / (1f - at))
        return if (extra <= 0f) crop
        else crop.copy(
            left = (crop.left - extra * at).coerceAtLeast(0f),
            right = (crop.right + extra * (1f - at)).coerceAtMost(1f),
        )
    }

    // The power-of-two sample size to decode the whole image at: as coarse
    // as it can be while the framed rows still number at least the frame's
    // height, and never more than MAX_DECODE_PIXELS in all.
    fun sampleSize(imageWidth: Int, imageHeight: Int, frameRows: Float, frameHeight: Int): Int {
        var sample = 1
        while (frameRows / (sample * 2) >= frameHeight) sample *= 2
        val pixels = imageWidth.toLong() * imageHeight
        while (pixels / (sample.toLong() * sample) > MAX_DECODE_PIXELS) sample *= 2
        return sample
    }
}
