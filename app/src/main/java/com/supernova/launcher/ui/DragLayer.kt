package com.supernova.launcher.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.graphics.createBitmap
import kotlin.math.max
import kotlin.math.roundToInt

// An icon's picture for the drag layer, `width` x `height`, with room all
// round for what it draws outside itself: a launcher icon's shadow falls
// past its bounds (ShadowedIconDrawable), and toBitmap() at the icon's own
// size cut it off at the bottom. The room is the second value, for
// DragLayer.begin's `outset`.
internal fun iconPictureWithRoom(drawable: Drawable, width: Int, height: Int): Pair<Bitmap, Int> {
    val outset = (max(width, height) * ICON_PICTURE_ROOM_FRACTION).roundToInt()
    val bitmap = createBitmap(width + 2 * outset, height + 2 * outset)
    // A copy: the drawable on screen keeps its bounds.
    val copy = drawable.constantState?.newDrawable()?.mutate() ?: drawable
    val bounds = Rect(copy.bounds)
    copy.setBounds(outset, outset, outset + width, outset + height)
    copy.draw(Canvas(bitmap))
    if (copy === drawable) copy.bounds = bounds
    return bitmap to outset
}

// A tenth a side: a shadow is about 3dp past a 48dp icon.
internal const val ICON_PICTURE_ROOM_FRACTION = 0.1f

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
    // What the settle in flight runs once its picture has landed.
    private var onSettled: (() -> Unit)? = null
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
    // enlargement while dragging, reached over `durationMs`. `outset` is room
    // round the picture in `bitmap` (iconPictureWithRoom), drawn past its
    // edges: the picture is the bitmap less it, for every size and place.
    @Suppress("LongParameterList")
    fun begin(
        bitmap: Bitmap,
        fingerRawX: Float,
        fingerRawY: Float,
        source: Rect?,
        scale: Float,
        durationMs: Long,
        outset: Int = 0
    ) {
        cancel()
        getLocationOnScreen(origin)
        bitmapWidth = bitmap.width - 2 * outset
        bitmapHeight = bitmap.height - 2 * outset
        liveScale = scale
        val fingerX = fingerRawX - origin[0]
        val fingerY = fingerRawY - origin[1]
        if (source != null && source.width() > 0 && source.height() > 0) {
            // The bitmap may not be the source's size (a drawer row's icon is
            // drawn at workspace size); hold the same relative point.
            grabX = (fingerRawX - source.left) / source.width() * bitmapWidth
            grabY = (fingerRawY - source.top) / source.height() * bitmapHeight
        } else {
            grabX = bitmapWidth / 2f
            grabY = bitmapHeight / 2f
        }
        grabX = grabX.coerceIn(0f, bitmapWidth.toFloat())
        grabY = grabY.coerceIn(0f, bitmapHeight.toFloat())
        // A picture drawn larger than what it was lifted from (a search pill's
        // small icon, drawn at desktop size) starts at the source's size, over
        // it, and grows with the pickup. So does one taken over from a
        // folder's own drag, already lifted, whatever its size there.
        val startScale = if (source != null && source.width() > 0 && source.width() != bitmapWidth) {
            source.width() / bitmapWidth.toFloat()
        } else {
            1f
        }

        val view = ImageView(context).apply {
            if (outset > 0) setImageDrawable(OutsetPicture(bitmap, outset)) else setImageBitmap(bitmap)
            elevation = SHADOW_DP * resources.displayMetrics.density
            pivotX = grabX
            pivotY = grabY
            x = fingerX - grabX
            y = fingerY - grabY
        }
        addView(view, LayoutParams(bitmapWidth, bitmapHeight))
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
        onSettled = onEnd
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
        if (durationMs <= 0) return finish()
        view.animate()
            .x(target.left - origin[0]).y(target.top - origin[1])
            .scaleX(if (bitmapWidth > 0) target.width() / bitmapWidth else 1f)
            .scaleY(if (bitmapHeight > 0) target.height() / bitmapHeight else 1f)
            .setDuration(durationMs).setInterpolator(DecelerateInterpolator(1.5f))
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = finish()
            })
            .start()
    }

    // Shrinks the picture into a point (a drop zone's centre) and removes it.
    fun consume(centerX: Float, centerY: Float, durationMs: Long, onEnd: () -> Unit) {
        val view = dragView ?: return onEnd()
        settling = true
        onSettled = onEnd
        view.animate().cancel()
        if (durationMs <= 0) return finish()
        view.pivotX = grabX
        view.pivotY = grabY
        view.animate()
            .x(centerX - origin[0] - grabX).y(centerY - origin[1] - grabY)
            .scaleX(CONSUME_SCALE).scaleY(CONSUME_SCALE).alpha(0f)
            .setDuration(durationMs).setInterpolator(DecelerateInterpolator())
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = finish()
            })
            .start()
    }

    // Drops the picture at once. A settle in flight ends here as if it had
    // landed, its onEnd run once and before this returns: a pickup that
    // comes while the last drop is still settling has that drop finished
    // before it starts, rather than the drop's end landing on the new drag.
    fun cancel() {
        if (dragView == null) return
        finish()
    }

    private fun finish() {
        val onEnd = onSettled
        onSettled = null
        dragView?.let {
            it.animate().setListener(null)
            it.animate().cancel()
            removeView(it)
        }
        dragView = null
        settling = false
        onEnd?.invoke()
    }

    private companion object {
        const val SHADOW_DP = 10f
        const val CONSUME_SCALE = 0.25f
    }
}

// A picture with `outset` px of room round it in `bitmap` (iconPictureWithRoom):
// its size is the picture's, and it draws the room past its bounds, scaled
// with it. The drag layer doesn't clip its children, so a shadow there shows.
private class OutsetPicture(private val bitmap: Bitmap, private val outset: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val innerWidth = bitmap.width - 2 * outset
    private val innerHeight = bitmap.height - 2 * outset
    private val target = RectF()

    override fun getIntrinsicWidth(): Int = innerWidth

    override fun getIntrinsicHeight(): Int = innerHeight

    override fun draw(canvas: Canvas) {
        val scaleX = bounds.width().toFloat() / innerWidth
        val scaleY = bounds.height().toFloat() / innerHeight
        target.set(
            bounds.left - outset * scaleX, bounds.top - outset * scaleY,
            bounds.right + outset * scaleX, bounds.bottom + outset * scaleY
        )
        canvas.drawBitmap(bitmap, null, target, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
