package com.supernova.launcher.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import kotlin.math.roundToInt

// How raw legacy (non-adaptive, unthemed) icons are made to match the rest
// (reshapeLegacy pref). AUTO rescales their visible content to a uniform
// fraction of the canvas (the old iconNormalize=true behavior); ON also bakes
// a neutral plate behind them so they read like adaptive icons.
enum class ReshapeLegacy {
    OFF,
    AUTO,
    ON;

    companion object {
        // "" derives from the legacy iconNormalize flag.
        fun fromPrefs(prefs: LauncherPrefs): ReshapeLegacy =
            entries.firstOrNull { it.name.equals(prefs.reshapeLegacy, ignoreCase = true) }
                ?: if (prefs.iconNormalize) AUTO else OFF
    }
}

// Flattens any launcher icon (adaptive, legacy, or icon-pack themed) into a
// fixed-size bitmap. Icon-pack back/mask/upon layers are composited here, which
// is also where user-selectable icon shapes plug in.
class IconRenderer(context: Context) {

    private val resources = context.resources
    // How much larger than the base icon the biggest icon on screen is
    // drawn (the icon size settings go to 160%), so a big icon is rendered
    // at its own size rather than a 48dp bitmap stretched soft.
    var resolutionScale: Float = 1f

    // Read each time, from the density now: Display size changes it under a
    // view model that outlives the activity, and the icons stayed at the
    // old resolution.
    val iconSizePx: Int
        get() = (ICON_SIZE_DP * resources.displayMetrics.density * resolutionScale).roundToInt()

    // reshapeLegacy pref: legacy icons get rescaled so their visible content
    // occupies a uniform fraction of the canvas (and, when ON, a plate baked
    // behind them).
    var reshapeLegacy: ReshapeLegacy = ReshapeLegacy.OFF

    // iconShadow pref: the flattened icon gets a soft drop shadow baked into
    // the cached bitmap, so binds stay free of per-frame shadow work. The
    // shadow falls outside the icon (ShadowedIconDrawable), which keeps its
    // full size.
    var shadow: Boolean = false

    fun render(
        source: Drawable,
        decoration: IconPackDecoration? = null,
        shape: IconShape = IconShape.SYSTEM,
        customShape: CustomShapeSpec? = null
    ): Drawable {
        val bitmap = Bitmap.createBitmap(iconSizePx, iconSizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // A user-chosen shape means every icon gets clipped to it, so legacy
        // icons must fill the frame rather than sit inset inside the mask.
        val shapePath = shape.pathFor(iconSizePx.toFloat(), customShape)

        // Only raw legacy icons need it: adaptive icons and pack plates are
        // already uniform, and shaped rendering deliberately overfills.
        val isRawLegacy = shapePath == null &&
            decoration?.back == null && source !is AdaptiveIconDrawable
        val shouldNormalize = reshapeLegacy != ReshapeLegacy.OFF && isRawLegacy

        decoration?.back?.let { back ->
            back.setBounds(0, 0, iconSizePx, iconSizePx)
            back.draw(canvas)
        }

        val scale = decoration?.scale?.takeIf { it > 0f } ?: 1f
        if (shapePath != null) {
            canvas.save()
            canvas.clipPath(shapePath)
            drawShapedBase(canvas, source, scale, hasBack = decoration?.back != null)
            canvas.restore()
        } else {
            drawBase(canvas, source, scale, hasBack = decoration?.back != null)
        }

        decoration?.mask?.let { mask ->
            val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
            }
            val maskBitmap = drawableToBitmap(mask)
            canvas.drawBitmap(maskBitmap, null, android.graphics.Rect(0, 0, iconSizePx, iconSizePx), maskPaint)
        }

        decoration?.upon?.let { upon ->
            upon.setBounds(0, 0, iconSizePx, iconSizePx)
            upon.draw(canvas)
        }

        var flat = if (shouldNormalize) normalized(bitmap) else bitmap
        // ON: bake a plate behind the (normalized) legacy icon so it reads
        // like an adaptive icon.
        if (reshapeLegacy == ReshapeLegacy.ON && isRawLegacy) flat = withPlate(flat)
        return if (shadow) withShadow(flat) else BitmapDrawable(resources, flat)
    }

    // A neutral rounded plate with the icon content inset onto it, matching
    // the footprint adaptive icons render at.
    private fun withPlate(bitmap: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(iconSizePx, iconSizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val platePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PLATE_COLOR }
        val radius = iconSizePx * PLATE_RADIUS_FRACTION
        canvas.drawRoundRect(
            android.graphics.RectF(0f, 0f, iconSizePx.toFloat(), iconSizePx.toFloat()),
            radius, radius, platePaint
        )
        val inset = (iconSizePx * (1f - PLATE_CONTENT) / 2f)
        canvas.drawBitmap(
            bitmap, null,
            android.graphics.RectF(inset, inset, iconSizePx - inset, iconSizePx - inset),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        )
        return output
    }

    // Stamps a blurred alpha silhouette under the icon: ~30% black, 2dp blur,
    // nudged 1dp down. Runs once per render, inside the cache, not per bind.
    // The shadow gets a margin of its own around the full-size icon, and the
    // drawable draws that margin past its bounds. It used to be carved out of
    // the icon, as Launcher3's icons give up a margin inside their box, which
    // drew every icon 6dp under the size it was set to.
    private fun withShadow(bitmap: Bitmap): Drawable {
        val size = iconSizePx
        val density = resources.displayMetrics.density * resolutionScale
        val margin = ((SHADOW_BLUR_DP + SHADOW_OFFSET_Y_DP) * density).roundToInt()
        val blurPaint = Paint().apply {
            maskFilter = android.graphics.BlurMaskFilter(
                SHADOW_BLUR_DP * density, android.graphics.BlurMaskFilter.Blur.NORMAL
            )
        }
        val offsets = IntArray(2)
        val silhouette = bitmap.extractAlpha(blurPaint, offsets)
        val output = Bitmap.createBitmap(size + 2 * margin, size + 2 * margin, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SHADOW_COLOR }
        canvas.drawBitmap(
            silhouette,
            (margin + offsets[0]).toFloat(),
            margin + offsets[1] + SHADOW_OFFSET_Y_DP * density,
            shadowPaint
        )
        silhouette.recycle()
        canvas.drawBitmap(bitmap, margin.toFloat(), margin.toFloat(), null)
        return ShadowedIconDrawable(output, margin)
    }

    // Finds the opaque bounding box (sampled every other pixel) and rescales
    // so its longer edge covers NORMALIZED_CONTENT of the canvas.
    private fun normalized(bitmap: Bitmap): Bitmap {
        val pixels = IntArray(iconSizePx * iconSizePx)
        bitmap.getPixels(pixels, 0, iconSizePx, 0, 0, iconSizePx, iconSizePx)
        var minX = iconSizePx
        var minY = iconSizePx
        var maxX = -1
        var maxY = -1
        var y = 0
        while (y < iconSizePx) {
            var x = 0
            val rowBase = y * iconSizePx
            while (x < iconSizePx) {
                if ((pixels[rowBase + x] ushr 24) > ALPHA_THRESHOLD) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
                x += 2
            }
            y += 2
        }
        if (maxX < 0) return bitmap

        val contentSize = maxOf(maxX - minX + 1, maxY - minY + 1).toFloat()
        val scale = (iconSizePx * NORMALIZED_CONTENT) / contentSize
        if (scale in 0.95f..1.05f) return bitmap

        val output = Bitmap.createBitmap(iconSizePx, iconSizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val contentCx = (minX + maxX + 1) / 2f
        val contentCy = (minY + maxY + 1) / 2f
        canvas.translate(iconSizePx / 2f - contentCx * scale, iconSizePx / 2f - contentCy * scale)
        canvas.scale(scale, scale)
        canvas.drawBitmap(bitmap, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        return output
    }

    private fun drawBase(canvas: Canvas, source: Drawable, packScale: Float, hasBack: Boolean) {
        // Icon packs expect the app icon shrunk to sit inside their background
        // plate; adaptive icons already carry their own mask.
        val fraction = when {
            hasBack -> packScale.coerceIn(0.3f, 1f)
            source is AdaptiveIconDrawable -> 1f
            else -> 1f - (LEGACY_INSET_FRACTION * 2)
        }
        val drawnSize = (iconSizePx * fraction).roundToInt()
        val offset = (iconSizePx - drawnSize) / 2
        source.setBounds(offset, offset, offset + drawnSize, offset + drawnSize)
        source.draw(canvas)
    }

    private fun drawShapedBase(canvas: Canvas, source: Drawable, packScale: Float, hasBack: Boolean) {
        if (source is AdaptiveIconDrawable) {
            // Draw the adaptive layers directly so our shape replaces the
            // platform mask instead of compounding with it.
            val bleed = (iconSizePx * ADAPTIVE_BLEED).roundToInt()
            val bounds = android.graphics.Rect(-bleed, -bleed, iconSizePx + bleed, iconSizePx + bleed)
            source.background?.let { it.bounds = bounds; it.draw(canvas) }
            source.foreground?.let { it.bounds = bounds; it.draw(canvas) }
            return
        }
        if (hasBack) {
            drawBase(canvas, source, packScale, hasBack = true)
            return
        }
        // Legacy icons rarely fill their canvas; scale up so the shape reads as
        // a solid tile rather than a masked postage stamp.
        val drawnSize = (iconSizePx * LEGACY_SHAPE_FILL).roundToInt()
        val offset = (iconSizePx - drawnSize) / 2
        source.setBounds(offset, offset, offset + drawnSize, offset + drawnSize)
        source.draw(canvas)
    }

    // The pack ships one mask used by every icon; rasterize it once instead
    // of on each render.
    private var maskMemo: Pair<Drawable, Bitmap>? = null

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        (drawable as? BitmapDrawable)?.bitmap?.let { return it }
        maskMemo?.let { (memoDrawable, memoBitmap) ->
            if (memoDrawable === drawable) return memoBitmap
        }
        val bitmap = Bitmap.createBitmap(iconSizePx, iconSizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, iconSizePx, iconSizePx)
        drawable.draw(canvas)
        maskMemo = drawable to bitmap
        return bitmap
    }

    companion object {
        // The pixels behind a rendered icon, for code that reads them (cache
        // accounting, the dominant color); null for anything else.
        fun bitmapOf(icon: Drawable): Bitmap? =
            (icon as? BitmapDrawable)?.bitmap ?: (icon as? ShadowedIconDrawable)?.bitmap

        private const val ICON_SIZE_DP = 48f
        private const val LEGACY_INSET_FRACTION = 0.04f
        private const val LEGACY_SHAPE_FILL = 1.18f
        private const val ADAPTIVE_BLEED = 0.12f
        private const val NORMALIZED_CONTENT = 0.72f
        private const val ALPHA_THRESHOLD = 40
        // White like the platform's default adaptive plate; content sits at
        // roughly the adaptive foreground safe-zone fraction.
        private const val PLATE_COLOR = 0xFFF3F3F3.toInt()
        private const val PLATE_RADIUS_FRACTION = 0.25f
        private const val PLATE_CONTENT = 0.66f
        private const val SHADOW_BLUR_DP = 2f
        private const val SHADOW_OFFSET_Y_DP = 1f
        private const val SHADOW_COLOR = 0x4D000000
    }
}
