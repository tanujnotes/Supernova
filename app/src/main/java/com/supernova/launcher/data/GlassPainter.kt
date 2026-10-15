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
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withClip
import kotlin.math.roundToInt

// Paints IconGlass panes: IconRenderer's icons, and the home search bar's
// pill (GlassPillDrawable). A pane fills width x height from the origin.
class GlassPainter {

    // The platform's own mask at the render size, for the System shape.
    fun systemMask(icon: AdaptiveIconDrawable, size: Int): Path {
        icon.setBounds(0, 0, size, size)
        return Path(icon.iconMask)
    }

    // The background layer gives way to a glass pane in its colour; the
    // foreground draws over it as the app made it. Its layers reach
    // bleedFraction of the size past each side, as the plain render's do.
    fun drawIcon(
        canvas: Canvas,
        icon: AdaptiveIconDrawable,
        mask: Path,
        glass: IconGlass,
        bleedFraction: Float
    ) {
        val size = canvas.width
        val bleed = (size * bleedFraction).roundToInt()
        val bounds = Rect(-bleed, -bleed, size + bleed, size + bleed)
        val backing = layerColor(icon.background, bounds, size)
        val foreground = icon.foreground?.let { foregroundOnGlass(it, bounds, size, backing) }
        canvas.withClip(mask) {
            drawPane(this, glass, backing, size, size)
            foreground?.let { drawBitmap(it, 0f, 0f, null) }
        }
        foreground?.recycle()
        drawEdge(canvas, mask, glass, size, size)
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

    // The foreground at the icon's size, less any copy of the background it
    // carries (BakedBacking), which would otherwise hide the pane.
    private fun foregroundOnGlass(layer: Drawable, bounds: Rect, size: Int, backing: Int): Bitmap {
        val bitmap = createBitmap(size, size)
        layer.bounds = bounds
        layer.draw(Canvas(bitmap))
        val pixels = IntArray(size * size)
        bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
        if (BakedBacking.clear(pixels, size, backing)) bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
        return bitmap
    }

    // A background layer's colour: a flat one's own, else the average of
    // what shows of it inside the icon (a gradient or a picture). No layer,
    // or nothing visible, reads as white.
    private fun layerColor(layer: Drawable?, bounds: Rect, size: Int): Int = when (layer) {
        null -> Color.WHITE
        is ColorDrawable -> ColorUtils.setAlphaComponent(layer.color, OPAQUE)
        else -> averageColor(layer, bounds, size) ?: Color.WHITE
    }

    // Alpha-weighted mean of the layer drawn small; null when it is all clear.
    private fun averageColor(layer: Drawable, bounds: Rect, size: Int): Int? {
        val sample = createBitmap(COLOR_SAMPLE_PX, COLOR_SAMPLE_PX)
        val canvas = Canvas(sample)
        canvas.scale(COLOR_SAMPLE_PX / size.toFloat(), COLOR_SAMPLE_PX / size.toFloat())
        layer.bounds = bounds
        layer.draw(canvas)
        val pixels = IntArray(COLOR_SAMPLE_PX * COLOR_SAMPLE_PX)
        sample.getPixels(pixels, 0, COLOR_SAMPLE_PX, 0, 0, COLOR_SAMPLE_PX, COLOR_SAMPLE_PX)
        sample.recycle()
        var red = 0L
        var green = 0L
        var blue = 0L
        var weight = 0L
        for (pixel in pixels) {
            val alpha = Color.alpha(pixel)
            red += Color.red(pixel) * alpha
            green += Color.green(pixel) * alpha
            blue += Color.blue(pixel) * alpha
            weight += alpha
        }
        return if (weight == 0L) {
            null
        } else {
            Color.rgb((red / weight).toInt(), (green / weight).toInt(), (blue / weight).toInt())
        }
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
        const val COLOR_SAMPLE_PX = 16
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
