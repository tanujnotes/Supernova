package com.supernova.launcher.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

// The wallpaper crop screen's image: it fills the screen, which is the
// frame, and a drag moves it while a pinch zooms it. It always covers the
// whole frame, so what shows is what the wallpaper will be.
class WallpaperCropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var image: Bitmap? = null
    private val drawMatrix = Matrix()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var scale = 1f
    private var minScale = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    private val zoom = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoomBy(detector.scaleFactor, detector.focusX, detector.focusY)
            return true
        }
    })

    private val pan = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            moveBy(-distanceX, -distanceY)
            return true
        }
    })

    fun setImage(bitmap: Bitmap) {
        image = bitmap
        fit()
    }

    // The part of the image inside the frame, in the image's 0..1 terms.
    fun crop(): WallpaperCrop.Box? {
        val bitmap = image ?: return null
        return if (width == 0 || height == 0) null
        else WallpaperCrop.visible(bitmap.width, bitmap.height, scale, offsetX, offsetY, width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fit()
    }

    // Covering the frame, centred.
    private fun fit() {
        val bitmap = image ?: return
        if (width == 0 || height == 0) return
        minScale = WallpaperCrop.coverScale(bitmap.width, bitmap.height, width, height)
        scale = minScale
        offsetX = (width - bitmap.width * scale) / 2f
        offsetY = (height - bitmap.height * scale) / 2f
        invalidate()
    }

    private fun zoomBy(factor: Float, focusX: Float, focusY: Float) {
        val next = (scale * factor).coerceIn(minScale, minScale * MAX_ZOOM)
        val applied = next / scale
        offsetX = focusX - (focusX - offsetX) * applied
        offsetY = focusY - (focusY - offsetY) * applied
        scale = next
        holdInFrame()
    }

    private fun moveBy(dx: Float, dy: Float) {
        offsetX += dx
        offsetY += dy
        holdInFrame()
    }

    private fun holdInFrame() {
        val bitmap = image ?: return
        offsetX = WallpaperCrop.clampOffset(offsetX, bitmap.width * scale, width.toFloat())
        offsetY = WallpaperCrop.clampOffset(offsetY, bitmap.height * scale, height.toFloat())
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val bitmap = image ?: return
        drawMatrix.setScale(scale, scale)
        drawMatrix.postTranslate(offsetX, offsetY)
        canvas.drawBitmap(bitmap, drawMatrix, paint)
    }

    // Drags and pinches only: a tap does nothing here, so there is no click
    // to report.
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        zoom.onTouchEvent(event)
        pan.onTouchEvent(event)
        return true
    }

    private companion object {
        // How far past covering the frame a pinch may zoom in.
        const val MAX_ZOOM = 6f
    }
}
