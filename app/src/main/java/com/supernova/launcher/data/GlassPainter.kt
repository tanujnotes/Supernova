package com.supernova.launcher.data

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withClip
import kotlin.math.roundToInt

// Paints IconGlass panes: IconRenderer's icons, and the home search bar's
// pill (GlassPillDrawable). A pane fills width x height from the origin.
class GlassPainter {

    private val legacy = LegacyGlass(this)

    // The platform's own mask at the render size, for the System shape.
    fun systemMask(icon: AdaptiveIconDrawable, size: Int): Path {
        icon.setBounds(0, 0, size, size)
        return Path(icon.iconMask)
    }

    // The icon on glass, over a pane in its backing's colour, then the rim.
    // An adaptive icon's layers reach bleedFraction of the size past each
    // side, as the plain render's do.
    fun drawIcon(canvas: Canvas, source: Drawable, mask: Path, glass: IconGlass, bleedFraction: Float) {
        val size = canvas.width
        if (source is AdaptiveIconDrawable) {
            drawLayers(canvas, source, mask, glass, bleedFraction)
        } else {
            legacy.draw(canvas, source, mask, glass)
        }
        drawEdge(canvas, mask, glass, size, size)
    }

    // Each layer read on its own, so the foreground's outline never takes
    // the background's colour into it. The background loses its backing
    // and keeps any detail on it (Camera's body); the foreground is drawn as
    // the app made it, less any copy of that backing over cleared background
    // (Maps' white tile). A foreground that carries a tile of its own across
    // the icon (Fender, Play Store) supplies the backing instead, and the
    // background layer under it, which never shows, is left out.
    private fun drawLayers(
        canvas: Canvas,
        icon: AdaptiveIconDrawable,
        mask: Path,
        glass: IconGlass,
        bleedFraction: Float
    ) {
        val size = canvas.width
        val bleed = (size * bleedFraction).roundToInt()
        val bounds = Rect(-bleed, -bleed, size + bleed, size + bleed)
        val back = icon.background?.let { layerPixels(it, bounds, mask, size) }
        var front = icon.foreground?.let { layerPixels(it, bounds, mask, size) }
        val whole = intArrayOf(0, 0, size - 1, size - 1)
        val slack = (size * BakedBacking.TILE_SLACK).roundToInt()
        val frontTile = front?.copyOf()?.let { copy ->
            BakedBacking.clear(copy, size)?.takeIf { BakedBacking.spans(it, whole, slack) }?.let { copy to it }
        }
        val tint: Int
        if (frontTile != null) {
            front = frontTile.first
            tint = frontTile.second.color
        } else {
            val cleared = back?.let { BakedBacking.clear(it, size) }
            tint = cleared?.color ?: Color.WHITE
            front?.let { if (cleared != null) BakedBacking.clearMatching(it, size, cleared.backing, cleared.taken) }
        }
        canvas.withClip(mask) {
            drawPane(this, glass, tint, size, size)
            if (frontTile == null) back?.let { drawPixels(this, it, size) }
            front?.let { drawPixels(this, it, size) }
        }
    }

    // One layer as it shows inside the mask, read back.
    private fun layerPixels(layer: Drawable, bounds: Rect, mask: Path, size: Int): IntArray {
        val bitmap = createBitmap(size, size)
        Canvas(bitmap).withClip(mask) {
            layer.bounds = bounds
            layer.draw(this)
        }
        val pixels = IntArray(size * size)
        bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
        bitmap.recycle()
        return pixels
    }

    // Fills the clip with the pane: the tint, the grain, then the light.
    fun drawPane(canvas: Canvas, glass: IconGlass, color: Int, width: Int, height: Int) {
        val right = width.toFloat()
        val bottom = height.toFloat()
        val base = if (glass == IconGlass.SMOKED) IconGlass.SMOKE_COLOR else color
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = ColorUtils.setAlphaComponent(
            ColorUtils.blendARGB(base, Color.WHITE, glass.haze),
            (glass.tintAlpha * OPAQUE).roundToInt()
        )
        canvas.drawRect(0f, 0f, right, bottom, paint)
        // Shaders draw at the paint's alpha: keep it opaque from here.
        paint.color = Color.BLACK
        if (glass.grain) {
            paint.shader = BitmapShader(grainTile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            canvas.drawRect(0f, 0f, right, bottom, paint)
        }
        val sheenEnd = bottom * SHEEN_DEPTH
        paint.shader = LinearGradient(
            0f, 0f, 0f, sheenEnd, white(glass.sheen), Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, right, sheenEnd, paint)
        val glowStart = bottom * (1f - GLOW_DEPTH)
        paint.shader = LinearGradient(
            0f, bottom, 0f, glowStart, white(glass.glow), Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, glowStart, right, bottom, paint)
    }

    // The rim: brightest top left, dim along the sides, catching light again
    // bottom right. Stroked twice its width inside the clip, so all of it
    // falls on the pane. Its width follows the shorter side.
    fun drawEdge(canvas: Canvas, mask: Path, glass: IconGlass, width: Int, height: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = minOf(width, height) * EDGE_WIDTH_FRACTION * 2f
            shader = LinearGradient(
                0f, 0f, width * EDGE_SLANT, height.toFloat(),
                intArrayOf(white(glass.edge), white(glass.edge * EDGE_MID), white(glass.edge * EDGE_END)),
                floatArrayOf(0f, EDGE_MID_STOP, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.withClip(mask) { drawPath(mask, paint) }
    }

    private fun white(alpha: Float): Int =
        ColorUtils.setAlphaComponent(Color.WHITE, (alpha.coerceIn(0f, 1f) * OPAQUE).roundToInt())

    // Light and dark specks at a few percent: a frosted pane's texture. One
    // tile, made once and repeated.
    private val grainTile: Bitmap by lazy {
        val random = java.util.Random(GRAIN_SEED)
        val pixels = IntArray(GRAIN_TILE_PX * GRAIN_TILE_PX) {
            val alpha = random.nextInt(GRAIN_MAX_ALPHA)
            if (random.nextBoolean()) Color.argb(alpha, OPAQUE, OPAQUE, OPAQUE) else Color.argb(alpha, 0, 0, 0)
        }
        Bitmap.createBitmap(pixels, GRAIN_TILE_PX, GRAIN_TILE_PX, Bitmap.Config.ARGB_8888)
    }

    private companion object {
        const val OPAQUE = 255
        // The sheen covers the top 55%, the glow the bottom 35%.
        const val SHEEN_DEPTH = 0.55f
        const val GLOW_DEPTH = 0.35f
        // The rim is about 1dp at the base icon size; its gradient runs to
        // the bottom, a little right of centre, fading to 18% midway and
        // back to 70%.
        const val EDGE_WIDTH_FRACTION = 0.022f
        const val EDGE_SLANT = 0.55f
        const val EDGE_MID = 0.18f
        const val EDGE_MID_STOP = 0.5f
        const val EDGE_END = 0.7f
        const val GRAIN_TILE_PX = 64
        const val GRAIN_MAX_ALPHA = 14
        const val GRAIN_SEED = 0x5EEDL
    }
}

// Draws pixels (size square, ARGB) at the canvas origin.
internal fun drawPixels(canvas: Canvas, pixels: IntArray, size: Int) {
    val bitmap = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    canvas.drawBitmap(bitmap, 0f, 0f, null)
    bitmap.recycle()
}
