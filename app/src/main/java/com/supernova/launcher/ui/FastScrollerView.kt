package com.supernova.launcher.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.TextView
import androidx.core.animation.doOnEnd
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs
import kotlin.math.roundToInt

// The fast scrollbar, Launcher3's RecyclerViewFastScroller with its own
// sizes: a faint track down the drawer list's right edge and the
// accent-colored thumb on it, where the list is. Dragging the thumb widens
// it, carries the list along with it, and shows the letter there in a
// bubble up by the finger; let go and the thumb settles back to where the
// list landed. A touch that misses the thumb goes through to the list.
class FastScrollerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // The list's letter sections, asked for as a drag starts; empty for a
    // list not sorted by name, which the thumb then moves item by item.
    internal var sections: () -> List<FastScrollMath.Section> = { emptyList() }

    private val density = resources.displayMetrics.density
    // The track's resting and dragging widths, the thumb's padding and its
    // height.
    private val restWidth = 6f * density
    private val dragWidth = 8f * density
    private val thumbPadding = 1f * density
    private val thumbHeight = 52f * density
    // A 58dp scroller hanging 26dp past the list's edge puts the
    // track's centre line this far in from it.
    private val edgeInset = 3f * density
    // How far above or below the thumb a press may land and still take it.
    // Across, the whole 32dp strip takes it, as Launcher3's does: an edge
    // gesture app's own strip (Ogesture's is 16dp) can cover the thumb.
    private val thumbReach = 16f * density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var trackWidth = restWidth
    private var widthAnimator: ValueAnimator? = null

    private var list: RecyclerView? = null
    private var bubble: TextView? = null

    // The thumb's top, measured down the track from its top: where the list
    // is (null when it doesn't scroll), and where a drag holds it instead.
    private var listOffset: Float? = null
    private var heldOffset: Float? = null
    private var settleAnimator: ValueAnimator? = null

    private var downY = 0f
    private var grabOffset = 0f
    private var tracking = false
    private var dragging = false
    private var dragSections: List<FastScrollMath.Section> = emptyList()
    private var bubbleShown = false

    fun attach(recycler: RecyclerView, letterBubble: TextView) {
        list = recycler
        bubble = letterBubble
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = updateThumb()
        })
        // The list fills after it has its size - the drawer submits its apps
        // as it opens - so its own layout changes miss that; every layout
        // pass catches it.
        recycler.viewTreeObserver.addOnGlobalLayoutListener { updateThumb() }
    }

    fun setColors(thumb: Int, track: Int, bubbleText: Int) {
        thumbPaint.color = thumb
        trackPaint.color = track
        bubble?.backgroundTintList = ColorStateList.valueOf(thumb)
        bubble?.setTextColor(bubbleText)
        invalidate()
    }

    // Puts the thumb where the list is.
    fun updateThumb() {
        val recycler = list ?: return
        val scrollable = recycler.computeVerticalScrollRange() - recycler.computeVerticalScrollExtent()
        val offset = FastScrollMath.thumbOffset(recycler.computeVerticalScrollOffset(), scrollable, span())
        if (offset == listOffset) return
        listOffset = offset
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateThumb()
    }

    private fun trackTop(): Float = paddingTop.toFloat()

    private fun trackBottom(): Float = (height - paddingBottom).toFloat()

    // How far the thumb's top can travel.
    private fun span(): Float = trackBottom() - trackTop() - thumbHeight

    override fun onDraw(canvas: Canvas) {
        val offset = heldOffset ?: listOffset ?: return
        if (span() <= 0f) return
        val centre = width - edgeInset
        val half = trackWidth / 2f
        canvas.drawRoundRect(centre - half, trackTop(), centre + half, trackBottom(), half, half, trackPaint)
        val thumbHalf = half + thumbPadding
        val top = trackTop() + offset
        canvas.drawRoundRect(centre - thumbHalf, top, centre + thumbHalf, top + thumbHeight, thumbHalf, thumbHalf, thumbPaint)
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val offset = listOffset ?: return false
                val top = trackTop() + offset
                if (event.y < top - thumbReach || event.y > top + thumbHeight + thumbReach) return false
                tracking = true
                downY = event.y
                grabOffset = (event.y - top).coerceIn(0f, thumbHeight)
                settleAnimator?.cancel()
                // Neither the drawer sheet nor the home gestures get this drag.
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return false
                if (!dragging && abs(event.y - downY) > touchSlop) startDrag()
                if (dragging) dragTo(event.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!tracking) return false
                tracking = false
                if (dragging) endDrag()
                return true
            }
        }
        return false
    }

    private fun startDrag() {
        dragging = true
        // A fling still running would fight the thumb for the list.
        list?.stopScroll()
        dragSections = sections()
        heldOffset = listOffset
        animateWidth(dragWidth)
    }

    private fun dragTo(y: Float) {
        val offset = (y - grabOffset - trackTop()).coerceIn(0f, span().coerceAtLeast(0f))
        heldOffset = offset
        invalidate()
        val recycler = list ?: return
        val progress = if (span() > 0f) offset / span() else 0f
        // The list follows the thumb pixel for pixel. Snapped to the start
        // of the letter under it, the list stood still while the thumb
        // moved and then leapt a row or more: a slow drag stuttered.
        val scrollable = recycler.computeVerticalScrollRange() - recycler.computeVerticalScrollExtent()
        val target = (progress * scrollable.coerceAtLeast(0)).roundToInt()
        val delta = target - recycler.computeVerticalScrollOffset()
        val layout = recycler.layoutManager as? DrawerLayoutManager
        // A short way is scrolled, row by row; a long one, a quick drag down
        // the track, is jumped to rather than binding every row between.
        if (layout == null || abs(delta) <= recycler.height) {
            recycler.scrollBy(0, delta)
        } else {
            layout.scrollToOffset(target)
        }
        val count = recycler.adapter?.itemCount ?: 0
        val position = layout?.positionAt(target) ?: (progress * (count - 1)).roundToInt()
        showBubble(FastScrollMath.label(position, dragSections), offset)
    }

    private fun endDrag() {
        dragging = false
        animateWidth(restWidth)
        hideBubble()
        // Back to where the list landed, rather than a jump.
        val from = heldOffset
        val to = listOffset
        if (from == null || to == null) {
            heldOffset = null
            invalidate()
            return
        }
        settleAnimator = ValueAnimator.ofFloat(from, to).apply {
            duration = SETTLE_MS
            addUpdateListener {
                heldOffset = it.animatedValue as Float
                invalidate()
            }
            doOnEnd { if (!dragging) heldOffset = null }
            start()
        }
    }

    // The bubble sits above-left of the finger with its square corner
    // pointing at it, Launcher3's 1.5 bubble heights up, and stays on the
    // track.
    private fun showBubble(label: String?, offset: Float) {
        val view = bubble ?: return
        if (label == null) {
            hideBubble()
            return
        }
        if (view.text != label) view.text = label
        val finger = trackTop() + offset + grabOffset
        val top = (finger - BUBBLE_RISE * view.height)
            .coerceIn(trackTop(), (trackBottom() - view.height).coerceAtLeast(trackTop()))
        view.translationY = top
        // Faded in once. Started again on every move, the fade restarted
        // each frame and the bubble stayed all but clear while the finger
        // moved.
        if (!bubbleShown) {
            bubbleShown = true
            if (view.visibility != VISIBLE) {
                view.alpha = 0f
                view.visibility = VISIBLE
            }
            view.animate().alpha(1f).setDuration(FADE_MS).start()
        }
    }

    private fun hideBubble() {
        val view = bubble ?: return
        if (!bubbleShown) return
        bubbleShown = false
        view.animate().alpha(0f).setDuration(FADE_MS).withEndAction {
            if (!bubbleShown) view.visibility = INVISIBLE
        }.start()
    }

    private fun animateWidth(target: Float) {
        widthAnimator?.cancel()
        widthAnimator = ValueAnimator.ofFloat(trackWidth, target).apply {
            duration = FADE_MS
            addUpdateListener {
                trackWidth = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        widthAnimator?.cancel()
        settleAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val FADE_MS = 150L
        const val SETTLE_MS = 150L
        const val BUBBLE_RISE = 1.5f
    }
}
