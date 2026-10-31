package com.supernova.launcher.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.createBitmap
import androidx.viewpager2.widget.ViewPager2
import kotlin.math.roundToInt

// Edit mode's page order: a long-press on a zoomed page lifts it and the finger
// carries it along the row of pages, the others closing up behind it; the order
// changes once, on the release. The row is pictures over a hidden pager, so
// nothing is written or rebound under the finger. Pages the pager has recycled
// (beyond its offscreen window) draw as a numbered plate.
class PageDragController(
    private val parent: ViewGroup,
    private val pager: ViewPager2,
    private val host: Host
) {
    interface Host {
        fun pageCount(): Int
        fun currentPage(): Int
        fun pageView(page: Int): View?
        fun animationMs(baseMs: Long): Long
        // Writes the new order and puts the pager on `to`, then calls `done`.
        fun commit(from: Int, to: Int, done: () -> Unit)
    }

    private val context = parent.context
    private val pictures = mutableMapOf<Int, View>()
    private val bitmaps = mutableListOf<Bitmap>()
    private var overlay: FrameLayout? = null
    private var row: FrameLayout? = null
    private var held: View? = null

    private var from = 0
    private var count = 0
    private var target = 0
    private var left = 0f
    private var top = 0f
    private var step = 0f
    private var scroll = 0f
    private var startX = Float.NaN
    private var startY = 0f
    private var fingerX = 0f
    private var settling = false

    // Owns the touch stream from the lift to the end of the settle.
    var isActive = false
        private set

    private val edgeScroll = object : Runnable {
        override fun run() {
            if (!isActive || settling) return
            val direction = edgeDirection()
            if (direction == 0) return
            val min = -from * step
            val max = (count - 1 - from) * step
            scroll = (scroll + direction * context.dp(EDGE_SPEED_DP)).coerceIn(min, max)
            row?.translationX = -scroll
            updateTarget()
            parent.postOnAnimation(this)
        }
    }

    // False when there is no other page to swap with.
    fun start(): Boolean {
        count = host.pageCount()
        if (isActive || count < 2 || pager.width <= 0) return false
        isActive = true
        settling = false
        from = host.currentPage().coerceIn(0, count - 1)
        target = from
        scroll = 0f
        startX = Float.NaN

        val scale = pager.scaleX
        val width = (pager.width * scale).roundToInt()
        val height = (pager.height * scale).roundToInt()
        left = pager.left + (pager.width - width) / 2f
        top = pager.top + pager.translationY
        step = width + context.dp(GAP_DP).toFloat()

        val strip = FrameLayout(context).apply { clipChildren = false }
        val cover = FrameLayout(context).apply { clipChildren = false }
        cover.addView(strip, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        for (page in 0 until count) {
            val picture = picture(page, width, height, scale)
            picture.translationX = slotX(page)
            picture.translationY = top
            pictures[page] = picture
            if (page == from) {
                cover.addView(picture, FrameLayout.LayoutParams(width, height))
            } else {
                strip.addView(picture, FrameLayout.LayoutParams(width, height))
            }
        }
        val lifted = pictures.getValue(from)
        lifted.elevation = context.dp(LIFT_ELEVATION_DP).toFloat()
        held = lifted
        row = strip
        overlay = cover
        parent.addView(
            cover,
            parent.indexOfChild(pager) + 1,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        pager.alpha = 0f
        lifted.animate().scaleX(LIFT_SCALE).scaleY(LIFT_SCALE).setDuration(host.animationMs(LIFT_MS)).start()
        return true
    }

    fun onTouch(ev: MotionEvent) {
        if (settling) return
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_DOWN -> move(ev.rawX, ev.rawY)
            MotionEvent.ACTION_UP -> settle(commit = true)
            MotionEvent.ACTION_CANCEL -> settle(commit = false)
            else -> Unit
        }
    }

    // Edit mode closing under the finger: no order change and no animation.
    fun cancel() {
        if (!isActive) return
        pictures.values.forEach { it.animate().cancel() }
        tearDown()
    }

    private fun move(rawX: Float, rawY: Float) {
        if (startX.isNaN()) {
            startX = rawX
            startY = rawY
        }
        fingerX = rawX
        held?.translationX = left + (rawX - startX)
        held?.translationY = top + (rawY - startY)
        updateTarget()
        if (edgeDirection() != 0) {
            parent.removeCallbacks(edgeScroll)
            parent.postOnAnimation(edgeScroll)
        }
    }

    private fun edgeDirection(): Int {
        val edge = parent.width * EDGE_FRACTION
        return when {
            fingerX < edge -> -1
            fingerX > parent.width - edge -> 1
            else -> 0
        }
    }

    private fun updateTarget() {
        val offset = (held?.translationX ?: left) - left + scroll
        val next = (from + (offset / step).roundToInt()).coerceIn(0, count - 1)
        if (next == target) return
        target = next
        parent.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        placeOthers()
    }

    // The pages between the lifted one's place and its target close up.
    private fun placeOthers() {
        val duration = host.animationMs(SHIFT_MS)
        for ((page, picture) in pictures) {
            if (page == from) continue
            val slot = when {
                page in (from + 1)..target -> page - 1
                page in target until from -> page + 1
                else -> page
            }
            picture.animate().translationX(slotX(slot)).setDuration(duration).start()
        }
    }

    private fun settle(commit: Boolean) {
        settling = true
        parent.removeCallbacks(edgeScroll)
        if (!commit && target != from) {
            target = from
            placeOthers()
        }
        val to = target
        val lifted = held ?: return tearDown()
        lifted.animate()
            .translationX(slotX(to) - scroll)
            .translationY(top)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(host.animationMs(SETTLE_MS))
            .withEndAction {
                if (!isActive) return@withEndAction
                if (to == from) tearDown() else host.commit(from, to, ::tearDown)
            }
            .start()
    }

    private fun tearDown() {
        if (!isActive) return
        isActive = false
        settling = false
        parent.removeCallbacks(edgeScroll)
        overlay?.let(parent::removeView)
        overlay = null
        row = null
        held = null
        pictures.clear()
        bitmaps.forEach(Bitmap::recycle)
        bitmaps.clear()
        pager.alpha = 1f
    }

    private fun slotX(slot: Int): Float = left + (slot - from) * step

    private fun picture(page: Int, width: Int, height: Int, scale: Float): View {
        val plate = GradientDrawable().apply {
            setColor(PLATE_COLOR)
            cornerRadius = context.dp(CORNER_DP).toFloat()
        }
        val source = host.pageView(page)
        if (source == null || source.width <= 0 || source.height <= 0) {
            return TextView(context).apply {
                text = (page + 1).toString()
                gravity = Gravity.CENTER
                setTextColor(android.graphics.Color.WHITE)
                textSize = PLACEHOLDER_SP
                background = plate
            }
        }
        val bitmap = createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1))
        val canvas = Canvas(bitmap)
        canvas.scale(scale, scale)
        runCatching { source.draw(canvas) }
        bitmaps.add(bitmap)
        return ImageView(context).apply {
            setImageBitmap(bitmap)
            background = plate
        }
    }

    private companion object {
        const val GAP_DP = 16
        const val CORNER_DP = 16
        const val LIFT_ELEVATION_DP = 8
        const val EDGE_SPEED_DP = 6
        const val EDGE_FRACTION = 0.1f
        const val LIFT_SCALE = 1.04f
        const val LIFT_MS = 120L
        const val SHIFT_MS = 160L
        const val SETTLE_MS = 180L
        const val PLACEHOLDER_SP = 28f
        const val PLATE_COLOR = 0x1AFFFFFF
    }
}
