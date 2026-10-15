package com.supernova.launcher.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.drawable.Drawable
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withClip
import androidx.core.graphics.withTranslation
import kotlin.math.roundToInt

// Glass for a legacy icon, which is one picture: a tile of its own (Paseo's
// blue square, FAST's white disc) is enlarged to fill the shape and cleared
// like an adaptive icon's background; an object standing free (a SIM card
// with a gear beside it) sits whole on a white pane.
internal class LegacyGlass(private val painter: GlassPainter) {

    // A legacy icon is one picture: its tile is cleared from that, with the
    // outline unmixed; one without a tile sits whole on a white pane.
    fun draw(canvas: Canvas, icon: Drawable, mask: Path, glass: IconGlass) {
        val size = canvas.width
        val art = tiled(icon, mask, size)
        if (art == null) {
            canvas.withClip(mask) { painter.drawPane(this, glass, Color.WHITE, size, size) }
            drawFreeStanding(canvas, icon, size)
            return
        }
        val pixels = IntArray(size * size)
        art.getPixels(pixels, 0, size, 0, 0, size, size)
        art.recycle()
        val cleared = BakedBacking.clear(pixels, size)
        canvas.withClip(mask) {
            painter.drawPane(this, glass, cleared?.color ?: Color.WHITE, size, size)
            drawPixels(this, pixels, size)
        }
    }

    // A legacy icon whose backing spans it - Paseo's blue tile, FAST's white
    // disc - enlarged so that tile reaches just past the mask; null for one
    // whose picture is an object (a SIM card with a gear beside it).
    private fun tiled(icon: Drawable, mask: Path, size: Int): Bitmap? {
        val pixels = picture(icon, size)
        val content = BakedBacking.contentBounds(pixels, size)
        val cleared = content?.let { BakedBacking.clear(pixels, size) }
        val isTile = content != null && cleared != null &&
            BakedBacking.spans(cleared, content, (size * BakedBacking.TILE_SLACK).roundToInt())
        return if (isTile && content != null) {
            createBitmap(size, size).also { bitmap ->
                Canvas(bitmap).withClip(mask) { drawFitted(this, icon, size, content, TILE_FILL) }
            }
        } else {
            null
        }
    }

    // An object standing free, centred on its pane at a logo's size.
    private fun drawFreeStanding(canvas: Canvas, icon: Drawable, size: Int) {
        val content = BakedBacking.contentBounds(picture(icon, size), size) ?: return
        drawFitted(canvas, icon, size, content, FREE_FILL)
    }

    // Draws the icon so its content box spans fill of the size, centred.
    private fun drawFitted(canvas: Canvas, icon: Drawable, size: Int, content: IntArray, fill: Float) {
        val left = content[BakedBacking.LEFT]
        val top = content[BakedBacking.TOP]
        val right = content[BakedBacking.RIGHT]
        val bottom = content[BakedBacking.BOTTOM]
        val extent = maxOf(right - left + 1, bottom - top + 1)
        val scale = size * fill / extent
        val centreX = (left + right + 1) / 2f
        val centreY = (top + bottom + 1) / 2f
        canvas.withTranslation(size / 2f - centreX * scale, size / 2f - centreY * scale) {
            scale(scale, scale)
            icon.setBounds(0, 0, size, size)
            icon.draw(this)
        }
    }

    // The icon drawn at the size, read back.
    private fun picture(icon: Drawable, size: Int): IntArray {
        val bitmap = createBitmap(size, size)
        icon.setBounds(0, 0, size, size)
        icon.draw(Canvas(bitmap))
        val pixels = IntArray(size * size)
        bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
        bitmap.recycle()
        return pixels
    }

    private companion object {
        // A tile is enlarged to reach just past the mask; an object standing
        // free fills about what an adaptive icon's logo does.
        const val TILE_FILL = 1.06f
        const val FREE_FILL = 0.62f
    }
}
