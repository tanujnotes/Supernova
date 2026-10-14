package com.supernova.launcher.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.min

// How a badge renders, in three styles: Dots (Android 8 style, the
// app icon's color unless a color is set), Numeric dots (the count), and
// Dynamic badges (the notification's own picture). The empty pref
// legacy-derives from badgeCounts.
enum class BadgeStyle {
    DOTS,
    NUMERIC,
    DYNAMIC;

    companion object {
        fun fromName(name: String): BadgeStyle? =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) }

        // Single resolution point for the badgeStyle/badgeCounts compat rule.
        fun effective(prefs: com.supernova.launcher.data.LauncherPrefs): BadgeStyle =
            fromName(prefs.badgeStyle) ?: if (prefs.badgeCounts) NUMERIC else DOTS
    }
}

enum class BadgePosition {
    TOP_RIGHT,
    TOP_LEFT,
    BOTTOM_RIGHT,
    BOTTOM_LEFT;

    companion object {
        fun fromName(name: String): BadgePosition =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: TOP_RIGHT
    }
}

// How a badge looks, read out of prefs by BadgeLook.
data class BadgeSpec(
    val style: BadgeStyle,
    val color: Int = BadgeDrawable.DEFAULT_BADGE_COLOR,
    val position: BadgePosition = BadgePosition.TOP_RIGHT,
    val scale: Float = 1f,
    val textColor: Int = BadgeDrawable.DEFAULT_TEXT_COLOR,
    // NUMERIC only: 0 = square corners, 1 = fully round.
    val cornerFraction: Float = 1f,
    val outline: Boolean = true
)

// Wraps an icon and stamps a notification badge on a corner, the way
// launchers render unread badges:
// - DOTS: a plain dot;
// - NUMERIC: the count, on a pill whose corners run from square to round;
// - DYNAMIC: the notification's own picture (see BadgeFace) in a circle,
//   or a dot when there is no picture yet.
class BadgeDrawable(
    private val icon: Drawable,
    private val count: Int,
    private val spec: BadgeSpec,
    private val density: Float,
    // DYNAMIC only: the square picture to show in the circle.
    face: Bitmap? = null
) : Drawable() {

    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = spec.color }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = OUTLINE_COLOR }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = spec.textColor
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val face: Bitmap? = face.takeIf { spec.style == BadgeStyle.DYNAMIC }
    private val faceMatrix = Matrix()
    private val faceShader: BitmapShader? = this.face?.let {
        BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    }
    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { shader = faceShader }

    // draw() runs per frame; the label and metrics holder never change.
    private val label = if (count > MAX_COUNT) "$MAX_COUNT+" else count.toString()
    private val metrics = Paint.FontMetrics()
    private val pill = RectF()

    private val onRight = spec.position == BadgePosition.TOP_RIGHT || spec.position == BadgePosition.BOTTOM_RIGHT
    private val onTop = spec.position == BadgePosition.TOP_RIGHT || spec.position == BadgePosition.TOP_LEFT

    override fun draw(canvas: Canvas) {
        icon.bounds = bounds
        icon.draw(canvas)
        if (count <= 0) return
        val ratio = when (spec.style) {
            BadgeStyle.DOTS -> BADGE_RADIUS_DOT
            BadgeStyle.NUMERIC -> BADGE_RADIUS_COUNT
            BadgeStyle.DYNAMIC -> if (face != null) BADGE_RADIUS_FACE else BADGE_RADIUS_DOT
        }
        val radius = min(bounds.width(), bounds.height()) * ratio * spec.scale
        val ring = if (spec.outline) OUTLINE_WIDTH_DP * density else 0f
        if (spec.style == BadgeStyle.NUMERIC) drawCount(canvas, radius, ring) else drawCircle(canvas, radius, ring)
    }

    // A dot, or the notification's picture cut to a circle; flush in its
    // corner.
    private fun drawCircle(canvas: Canvas, radius: Float, ring: Float) {
        val cx = if (onRight) bounds.right - radius else bounds.left + radius
        val cy = if (onTop) bounds.top + radius else bounds.bottom - radius
        if (ring > 0f) canvas.drawCircle(cx, cy, radius + ring, outlinePaint)
        val picture = face
        if (picture == null || faceShader == null) {
            canvas.drawCircle(cx, cy, radius, badgePaint)
            return
        }
        val side = radius * 2f
        faceMatrix.setScale(side / picture.width, side / picture.height)
        faceMatrix.postTranslate(cx - radius, cy - radius)
        faceShader.setLocalMatrix(faceMatrix)
        canvas.drawCircle(cx, cy, radius, facePaint)
    }

    // A pill as tall as the old count circle, widening for 2+ digits, its
    // corners from square to round; it sits flush in its corner.
    private fun drawCount(canvas: Canvas, radius: Float, ring: Float) {
        textPaint.textSize = radius * TEXT_SIZE_RATIO
        val height = radius * 2f
        val width = maxOf(height, textPaint.measureText(label) + radius)
        val left = if (onRight) bounds.right - width else bounds.left.toFloat()
        val top = if (onTop) bounds.top.toFloat() else bounds.bottom - height
        pill.set(left, top, left + width, top + height)
        val corner = radius * spec.cornerFraction.coerceIn(0f, 1f)
        if (ring > 0f) {
            pill.inset(-ring, -ring)
            canvas.drawRoundRect(pill, corner + ring, corner + ring, outlinePaint)
            pill.inset(ring, ring)
        }
        canvas.drawRoundRect(pill, corner, corner, badgePaint)
        textPaint.getFontMetrics(metrics)
        canvas.drawText(label, pill.centerX(), pill.centerY() - (metrics.ascent + metrics.descent) / 2f, textPaint)
    }

    override fun getIntrinsicWidth(): Int = icon.intrinsicWidth

    override fun getIntrinsicHeight(): Int = icon.intrinsicHeight

    override fun setAlpha(alpha: Int) {
        icon.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        icon.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun onBoundsChange(bounds: Rect) {
        icon.bounds = bounds
    }

    companion object {
        const val DEFAULT_BADGE_COLOR = 0xFFE53935.toInt()
        const val DEFAULT_TEXT_COLOR = 0xFFFFFFFF.toInt()
        private const val OUTLINE_COLOR = 0xFF101418.toInt()
        private const val BADGE_RADIUS_DOT = 0.14f
        private const val BADGE_RADIUS_COUNT = 0.22f
        private const val BADGE_RADIUS_FACE = 0.2f
        private const val OUTLINE_WIDTH_DP = 1.5f
        private const val TEXT_SIZE_RATIO = 1.25f
        private const val MAX_COUNT = 99
    }
}
