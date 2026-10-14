package com.supernova.launcher.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

// A rendered icon whose drop shadow falls outside it. It reports the icon's
// own size, so every view sizes and scales it as the icon, and draws `bitmap`
// - the icon with `margin` px of shadow all round - past its bounds by that
// margin, scaled with the icon. The item layouts leave padding for it and
// don't clip their children; a view that does clip keeps the icon at its
// size and loses only the shadow's outer edge.
//
// Not a BitmapDrawable on purpose: toBitmap() hands a BitmapDrawable's bitmap
// straight back, which would put the shadow inside the picture and shrink
// the icon in it (a drag picture, a work-profile badge).
class ShadowedIconDrawable(val bitmap: Bitmap, private val margin: Int) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val iconSize = bitmap.width - 2 * margin
    private val target = RectF()

    override fun getIntrinsicWidth(): Int = iconSize

    override fun getIntrinsicHeight(): Int = iconSize

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        val scaleX = bounds.width().toFloat() / iconSize
        val scaleY = bounds.height().toFloat() / iconSize
        target.set(
            bounds.left - margin * scaleX,
            bounds.top - margin * scaleY,
            bounds.right + margin * scaleX,
            bounds.bottom + margin * scaleY
        )
        canvas.drawBitmap(bitmap, null, target, paint)
    }

    override fun setAlpha(alpha: Int) {
        if (paint.alpha == alpha) return
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun getAlpha(): Int = paint.alpha

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getConstantState(): ConstantState = State(bitmap, margin)

    private class State(val bitmap: Bitmap, val margin: Int) : ConstantState() {
        override fun newDrawable(): Drawable = ShadowedIconDrawable(bitmap, margin)

        override fun getChangingConfigurations(): Int = 0
    }
}
