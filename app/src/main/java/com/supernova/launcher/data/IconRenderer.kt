package com.supernova.launcher.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.withClip
import androidx.core.graphics.withTranslation
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
    // drawn (the icon size settings go to 200%), so a big icon is rendered
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

    // iconGlass pref (IconGlass): adaptive icons, and the legacy plate, get a
    // glass pane in place of their background.
    var glass: IconGlass = IconGlass.OFF
    private val glassPainter = GlassPainter()

    fun render(
        source: Drawable,
        decoration: IconPackDecoration? = null,
        shape: IconShape = IconShape.SYSTEM,
        customShape: CustomShapeSpec? = null,
        glass: IconGlass = this.glass
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
        // The pane's outline, when there is one: the shadow keeps clear of it.
        val glassMask = drawSource(canvas, source, decoration, shapePath, glass)

        // Glass sizes and places a legacy icon itself.
        var flat = if (shouldNormalize && glassMask == null) normalized(bitmap) else bitmap
        // ON: bake a plate behind the (normalized) legacy icon so it reads
        // like an adaptive icon.
        if (reshapeLegacy == ReshapeLegacy.ON && isRawLegacy && glassMask == null) flat = withPlate(flat)
        return if (shadow) withShadow(flat, glassMask) else BitmapDrawable(resources, flat)
    }

    // A picture an app made for its shortcut (a contact's photo) that fills
    // its frame: drawn whole across the icon's shape - the chosen one, or
    // the platform's mask with System - enlarged only as far as a round
    // picture needs to cover it. Glass and Reshape legacy's resizing and
    // plate read each photo differently (one filled the icon, the next sat
    // small on a pane, another lost its background to glass), so they leave
    // it alone, as they do a picked picture. With System and Reshape legacy
    // Off it is drawn as it is, like any legacy icon then. Null for one that
    // doesn't fill its frame (a glyph), which is drawn as a legacy icon.
    fun renderPicture(picture: Bitmap, shape: IconShape, customShape: CustomShapeSpec?): Drawable? {
        val side = picture.width
        // One that isn't square stays clear here, so it is no picture.
        val pixels = IntArray(side * side)
        if (side == picture.height) picture.getPixels(pixels, 0, side, 0, 0, side, side)
        val box = PictureFrame.frame(pixels, side) ?: return null
        val size = iconSizePx
        val shapePath = shape.pathFor(size.toFloat(), customShape)
        return if (shapePath == null && reshapeLegacy == ReshapeLegacy.OFF) {
            render(picture.toDrawable(resources), glass = IconGlass.OFF)
        } else {
            val mask = shapePath
                ?: glassPainter.systemMask(AdaptiveIconDrawable(Color.BLACK.toDrawable(), null), size)
            val cover = if (PictureFrame.square(pixels, side, box)) {
                1f
            } else {
                PictureFrame.reach(maskPixels(mask, size), size)
            }
            val left = box[BakedBacking.LEFT]
            val top = box[BakedBacking.TOP]
            val right = box[BakedBacking.RIGHT]
            val bottom = box[BakedBacking.BOTTOM]
            val scale = size * cover / maxOf(right - left + 1, bottom - top + 1)
            val offsetX = size / 2f - (left + right + 1) / 2f * scale
            val offsetY = size / 2f - (top + bottom + 1) / 2f * scale
            val bitmap = createBitmap(size, size)
            Canvas(bitmap).withClip(mask) {
                withTranslation(offsetX, offsetY) {
                    scale(scale, scale)
                    drawBitmap(picture, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                }
            }
            if (shadow) withShadow(bitmap) else bitmap.toDrawable(resources)
        }
    }

    // A launcher action's tile, full-bleed and square as drawn: cut to the
    // chosen shape, or the platform's mask with System, as an adaptive icon
    // is. Glass and Reshape legacy have no plate of it to replace.
    fun renderTile(tile: Drawable, shape: IconShape, customShape: CustomShapeSpec?): Drawable {
        val size = iconSizePx
        val mask = shape.pathFor(size.toFloat(), customShape)
            ?: glassPainter.systemMask(AdaptiveIconDrawable(Color.BLACK.toDrawable(), null), size)
        val bitmap = createBitmap(size, size)
        Canvas(bitmap).withClip(mask) {
            tile.setBounds(0, 0, size, size)
            tile.draw(this)
        }
        return if (shadow) withShadow(bitmap) else bitmap.toDrawable(resources)
    }

    // The icon onto the canvas: a pack's back, the source - as glass, shaped
    // or plain - then the pack's mask and upon. The glass pane's outline
    // when it drew one.
    private fun drawSource(
        canvas: Canvas,
        source: Drawable,
        decoration: IconPackDecoration?,
        shapePath: Path?,
        glass: IconGlass
    ): Path? {
        decoration?.back?.let { back ->
            back.setBounds(0, 0, iconSizePx, iconSizePx)
            back.draw(canvas)
        }

        val scale = decoration?.scale?.takeIf { it > 0f } ?: 1f
        // A pack's back plate is the pack's look, so its icons keep it.
        val glassMask = if (glass != IconGlass.OFF && decoration?.back == null) {
            val mask = glassShape(source, shapePath)
            // The platform draws its layers at its own bleed; a chosen shape
            // overfills as drawShapedBase does.
            val bleed = if (shapePath != null) ADAPTIVE_BLEED else PLATFORM_BLEED
            glassPainter.drawIcon(canvas, source, mask, glass, bleed)
            mask
        } else {
            null
        }
        if (glassMask == null && shapePath != null) {
            canvas.save()
            canvas.clipPath(shapePath)
            drawShapedBase(canvas, source, scale, hasBack = decoration?.back != null)
            canvas.restore()
        } else if (glassMask == null) {
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
        return glassMask
    }

    // A neutral rounded plate with the icon content inset onto it, matching
    // the footprint adaptive icons render at.
    private fun withPlate(bitmap: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(iconSizePx, iconSizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val platePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PLATE_COLOR }
        val radius = iconSizePx * PLATE_RADIUS_FRACTION
        canvas.drawRoundRect(
            RectF(0f, 0f, iconSizePx.toFloat(), iconSizePx.toFloat()),
            radius, radius, platePaint
        )
        val inset = (iconSizePx * (1f - PLATE_CONTENT) / 2f)
        canvas.drawBitmap(
            bitmap, null,
            RectF(inset, inset, iconSizePx - inset, iconSizePx - inset),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        )
        return output
    }

    // The glass icon's outline: the chosen shape; with System, the
    // platform's mask, or for a legacy icon the rounded plate Reshape legacy
    // icons would give it.
    private fun glassShape(source: Drawable, shapePath: Path?): Path =
        shapePath ?: (source as? AdaptiveIconDrawable)?.let { glassPainter.systemMask(it, iconSizePx) }
            ?: platePath(iconSizePx, PLATE_RADIUS_FRACTION)

    // Stamps a blurred alpha silhouette under the icon: ~30% black, 2dp blur,
    // nudged 1dp down. Runs once per render, inside the cache, not per bind.
    // The shadow gets a margin of its own around the full-size icon, and the
    // drawable draws that margin past its bounds. It used to be carved out of
    // the icon, as Launcher3's icons give up a margin inside their box, which
    // drew every icon 6dp under the size it was set to.
    // A glass icon's shadow is cast by its outline, not its pixels - a pane
    // is mostly see-through - and only what falls outside the pane is kept,
    // since a shadow seen through glass just muddies it.
    private fun withShadow(bitmap: Bitmap, glassMask: Path? = null): Drawable {
        val size = iconSizePx
        val density = resources.displayMetrics.density * resolutionScale
        val margin = ((SHADOW_BLUR_DP + SHADOW_OFFSET_Y_DP) * density).roundToInt()
        val blurPaint = Paint().apply {
            maskFilter = android.graphics.BlurMaskFilter(
                SHADOW_BLUR_DP * density, android.graphics.BlurMaskFilter.Blur.NORMAL
            )
        }
        val offsets = IntArray(2)
        val solid = glassMask?.let { mask ->
            createBitmap(size, size).also {
                Canvas(it).drawPath(mask, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK })
            }
        }
        val silhouette = (solid ?: bitmap).extractAlpha(blurPaint, offsets)
        solid?.recycle()
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
        if (glassMask != null) {
            val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
            canvas.withTranslation(margin.toFloat(), margin.toFloat()) { drawPath(glassMask, clear) }
        }
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
        // AdaptiveIconDrawable's own: its layers reach a quarter past the icon.
        private const val PLATFORM_BLEED = 0.25f
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

// A mask drawn solid at the icon size, read back.
private fun maskPixels(mask: Path, size: Int): IntArray {
    val bitmap = createBitmap(size, size)
    Canvas(bitmap).drawPath(mask, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK })
    val pixels = IntArray(size * size)
    bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
    bitmap.recycle()
    return pixels
}

// The rounded plate Reshape legacy icons sit on, as an outline.
private fun platePath(size: Int, radiusFraction: Float): Path {
    val radius = size * radiusFraction
    return Path().apply {
        addRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), radius, radius, Path.Direction.CW)
    }
}
