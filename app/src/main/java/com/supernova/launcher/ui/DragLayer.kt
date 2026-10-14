package com.supernova.launcher.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView

// The launcher's own drag layer, the way Launcher3 does it: a
// full-window overlay that carries a picture of the picked-up item under the
// finger. The activity feeds it the touch stream (dispatchTouchEvent swallows
// the gesture once a drag is live), reads back where the picture sits for its
// cell maths, and tells it where to settle on release. It replaces
// View.startDragAndDrop, whose cross-window shadow could neither be animated
// on pickup and drop nor let the pager flip pages under it.
class DragLayer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private var dragView: ImageView? = null
    private var bitmapWidth = 0
    private var bitmapHeight = 0
    // Where the finger holds the picture, in picture pixels: the picture keeps
    // that point under the finger, so nothing jumps at pickup.
    private var grabX = 0f
    private var grabY = 0f
    private var liveScale = 1f
    private var settling = false
    private val origin = IntArray(2)

    // True from pickup until release; false while the picture settles.
    val isDragging: Boolean get() = dragView != null && !settling

    init {
        // Touches go through to whatever is underneath: the activity routes
        // the drag gesture here itself, and the layer draws only.
        isClickable = false
        isFocusable = false
        clipChildren = false
        clipToPadding = false
    }

    // Picks up `bitmap` at the finger. `source` is the screen rect the bitmap
    // was drawn from (the icon image, the widget frame), so the picture starts
    // exactly over it; null centres the picture on the finger. `scale` is the
    // enlargement while dragging, reached over `durationMs`.
    fun begin(bitmap: Bitmap, fingerRawX: Float, fingerRawY: Float, source: Rect?, scale: Float, durationMs: Long) {
        cancel()
        getLocationOnScreen(origin)
        bitmapWidth = bitmap.width
        bitmapHeight = bitmap.height
        liveScale = scale
        val fingerX = fingerRawX - origin[0]
        val fingerY = fingerRawY - origin[1]
        if (source != null && source.width() > 0 && source.height() > 0) {
            // The bitmap may not be the source's size (a drawer row's icon is
            // drawn at workspace size); hold the same relative point.
            grabX = (fingerRawX - source.left) / source.width() * bitmap.width
            grabY = (fingerRawY - source.top) / source.height() * bitmap.height
        } else {
            grabX = bitmap.width / 2f
            grabY = bitmap.height / 2f
        }
        grabX = grabX.coerceIn(0f, bitmap.width.toFloat())
        grabY = grabY.coerceIn(0f, bitmap.height.toFloat())
        // A picture drawn larger than what it was lifted from (a search pill's
        // small icon, drawn at desktop size) starts at the source's size, over
        // it, and grows with the pickup.
        val startScale = if (source != null && source.width() > 0 && source.width() < bitmap.width) {
            source.width() / bitmap.width.toFloat()
        } else {
            1f
        }

        val view = ImageView(context).apply {
            setImageBitmap(bitmap)
            elevation = SHADOW_DP * resources.displayMetrics.density
            pivotX = grabX
            pivotY = grabY
            x = fingerX - grabX
            y = fingerY - grabY
        }
        addView(view, LayoutParams(bitmap.width, bitmap.height))
        dragView = view
        if (durationMs > 0) {
            view.scaleX = startScale
            view.scaleY = startScale
            view.animate().scaleX(scale).scaleY(scale)
                .setDuration(durationMs).setInterpolator(DecelerateInterpolator()).start()
        } else {
            view.scaleX = scale
            view.scaleY = scale
        }
    }

    fun moveTo(fingerRawX: Float, fingerRawY: Float) {
        val view = dragView ?: return
        if (settling) return
        view.x = fingerRawX - origin[0] - grabX
        view.y = fingerRawY - origin[1] - grabY
    }

    // The picture's on-screen rect, scale included. Cell maths works from this
    // rather than from the finger, so an item lands where it is seen.
    fun visualRect(): RectF? {
        val view = dragView ?: return null
        val scale = view.scaleX
        val left = origin[0] + view.x + grabX * (1f - scale)
        val top = origin[1] + view.y + grabY * (1f - scale)
        return RectF(left, top, left + bitmapWidth * scale, top + bitmapHeight * scale)
    }

    // Flies the picture to `target` (screen coordinates), then removes it.
    fun settle(target: RectF, durationMs: Long, onEnd: () -> Unit) {
        val view = dragView ?: return onEnd()
        settling = true
        view.animate().cancel()
        // Re-pivot at the corner so the target is a plain position + scale.
        val current = visualRect() ?: RectF()
        view.pivotX = 0f
        view.pivotY = 0f
        view.x = current.left - origin[0]
        view.y = current.top - origin[1]
        val scaleNow = if (bitmapWidth > 0) current.width() / bitmapWidth else 1f
        view.scaleX = scaleNow
        view.scaleY = scaleNow
        if (durationMs <= 0) return finish(onEnd)
        view.animate()
            .x(target.left - origin[0]).y(target.top - origin[1])
            .scaleX(if (bitmapWidth > 0) target.width() / bitmapWidth else 1f)
            .scaleY(if (bitmapHeight > 0) target.height() / bitmapHeight else 1f)
            .setDuration(durationMs).setInterpolator(DecelerateInterpolator(1.5f))
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = finish(onEnd)
            })
            .start()
    }

    // Shrinks the picture into a point (a drop zone's centre) and removes it.
    fun consume(centerX: Float, centerY: Float, durationMs: Long, onEnd: () -> Unit) {
        val view = dragView ?: return onEnd()
        settling = true
        view.animate().cancel()
        if (durationMs <= 0) return finish(onEnd)
        view.pivotX = grabX
        view.pivotY = grabY
        view.animate()
            .x(centerX - origin[0] - grabX).y(centerY - origin[1] - grabY)
            .scaleX(CONSUME_SCALE).scaleY(CONSUME_SCALE).alpha(0f)
            .setDuration(durationMs).setInterpolator(DecelerateInterpolator())
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = finish(onEnd)
            })
            .start()
    }

    // Drops the picture at once.
    fun cancel() {
        val view = dragView ?: return
        view.animate().cancel()
        removeView(view)
        dragView = null
        settling = false
    }

    private fun finish(onEnd: () -> Unit) {
        dragView?.let { it.animate().setListener(null); removeView(it) }
        dragView = null
        settling = false
        onEnd()
    }

    private companion object {
        const val SHADOW_DP = 10f
        const val CONSUME_SCALE = 0.25f
    }
}
