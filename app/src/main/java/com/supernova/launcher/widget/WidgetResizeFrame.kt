package com.supernova.launcher.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import com.supernova.launcher.data.WorkspaceGeometry
import com.supernova.launcher.ui.WorkspaceCanvas
import com.supernova.launcher.ui.cellSize
import kotlin.math.min
import kotlin.math.roundToInt

// Launcher3-style resize frame: a dashed outline over the widget with four edge
// handles that snap to whole cells while dragging. Left/top drags move the
// anchor cell in lockstep with the span so the opposite edge stays fixed.
// The frame sits on whole cells and the widget view it wraps rides along
// inset: the canvas lays both out from their cells (WidgetParams). Both
// animate to each new snap rather than jumping, so the commit's rebind lands
// on a rect that is already there.
//
// The frame stays up through that rebind, for the next drag: the page keeps
// it, and the activity anchors it to the widget's new view and cells until
// it is dismissed.
class WidgetResizeFrame(
    context: Context,
    // The widget it frames; a rebind finds the widget's new view by it.
    val itemId: String,
    // Re-read on every drag start so consecutive drags see fresh occupancy.
    // Asked about the frame's own cells: the model can still be catching up
    // with the last lift.
    private val roomProvider: (cellX: Int, cellY: Int, spanX: Int, spanY: Int) -> WorkspaceGeometry.ResizeRoom,
    // How long each snap step takes; 0 snaps at once.
    private val snapDurationMs: Long = 0L,
    private val onCommit: (cellX: Int, cellY: Int, spanX: Int, spanY: Int) -> Unit
) : View(context) {

    var cellX: Int = 0
        private set
    var cellY: Int = 0
        private set
    var spanX: Int = 1
        private set
    var spanY: Int = 1
        private set

    // All set by anchor.
    private var bounds = SpanBounds(1, 1, 1, 1, canResizeH = false, canResizeV = false)
    // The widget's host view, resized live under the frame.
    private var widgetView: View? = null

    private val handleRadius = HANDLE_RADIUS_DP * resources.displayMetrics.density
    private val touchSlop = handleRadius * 2f

    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        color = OUTLINE_COLOR
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = HANDLE_COLOR }

    private var activeHandle = Handle.NONE
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var startCellX = 0
    private var startCellY = 0
    private var startSpanX = 1
    private var startSpanY = 1
    // The canvas's whole-pixel cells, which the finger's travel is counted
    // in: read at each press, from the canvas's size then.
    private var cellWidthPx = 1
    private var cellHeightPx = 1
    private var room = WorkspaceGeometry.ResizeRoom(0, 0, 0, 0)
    private var snapAnimator: android.animation.ValueAnimator? = null
    private val screenLocation = IntArray(2)

    private enum class Handle { NONE, LEFT, RIGHT, TOP, BOTTOM }

    // Puts the frame on the widget's cells, around its host view: when the
    // frame opens, and after every rebind of its page, which gives the widget
    // new params from the model (a committed resize does) or a new view. A
    // drag in progress keeps its own cells - that rebind is catching up with
    // an earlier lift - and hands them to the new params; its own lift
    // commits, and the rebind after it anchors the frame again.
    // False if the view isn't a widget laid out in cells.
    fun anchor(widgetView: View, bounds: SpanBounds): Boolean {
        val cells = widgetView.layoutParams as? WorkspaceCanvas.WidgetParams ?: return false
        this.widgetView = widgetView
        if (activeHandle != Handle.NONE) {
            placeWidget()
            return true
        }
        this.bounds = bounds
        cellX = cells.cellX.roundToInt()
        cellY = cells.cellY.roundToInt()
        spanX = cells.spanX.roundToInt()
        spanY = cells.spanY.roundToInt()
        layoutParams = WorkspaceCanvas.WidgetParams(cellX, cellY, spanX, spanY, cells.cols, cells.rows, 0)
        invalidate()
        return true
    }

    // Whether a press at this screen point would pick up a handle. The
    // activity asks at the down, before its own swipe trackers see the
    // press: they sit ahead of the view tree, and the feed pull or the
    // drawer pull would otherwise take a handle drag over and cancel it.
    fun hasHandleAt(rawX: Float, rawY: Float): Boolean {
        // Not shown also covers a frame left on a page the pager has let go.
        if (!isShown) return false
        getLocationOnScreen(screenLocation)
        val x = rawX - screenLocation[0]
        val y = rawY - screenLocation[1]
        if (x < 0f || y < 0f || x >= width || y >= height) return false
        return handleAt(x, y) != Handle.NONE
    }

    override fun onDraw(canvas: Canvas) {
        val inset = outlinePaint.strokeWidth
        canvas.drawRoundRect(
            inset, inset, width - inset, height - inset,
            8f * resources.displayMetrics.density, 8f * resources.displayMetrics.density,
            outlinePaint
        )
        if (bounds.canResizeH) {
            canvas.drawCircle(inset, height / 2f, handleRadius, handlePaint)
            canvas.drawCircle(width - inset, height / 2f, handleRadius, handlePaint)
        }
        if (bounds.canResizeV) {
            canvas.drawCircle(width / 2f, inset, handleRadius, handlePaint)
            canvas.drawCircle(width / 2f, height - inset, handleRadius, handlePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val canvas = parent as? View ?: return false
                val cells = layoutParams as? WorkspaceCanvas.WidgetParams ?: return false
                activeHandle = handleAt(event.x, event.y)
                if (activeHandle == Handle.NONE) return false
                // The workspace lives in a ViewPager2, which would otherwise
                // claim the horizontal drag and page away mid-resize.
                parent?.requestDisallowInterceptTouchEvent(true)
                dragStartX = event.rawX
                dragStartY = event.rawY
                startCellX = cellX
                startCellY = cellY
                startSpanX = spanX
                startSpanY = spanY
                cellWidthPx = cellSize(canvas.width, cells.cols)
                cellHeightPx = cellSize(canvas.height, cells.rows)
                room = roomProvider(cellX, cellY, spanX, spanY)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (activeHandle == Handle.NONE) return false
                applyDrag(event.rawX - dragStartX, event.rawY - dragStartY)
                return true
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                if (activeHandle == Handle.NONE) return false
                activeHandle = Handle.NONE
                parent?.requestDisallowInterceptTouchEvent(false)
                // Land on the final rect before the model write rebinds the
                // page, so the rebind changes nothing visible.
                snapAnimator?.end()
                onCommit(cellX, cellY, spanX, spanY)
                return true
            }
        }
        return false
    }

    private fun applyDrag(dx: Float, dy: Float) {
        val cellsX = (dx / cellWidthPx).roundToInt()
        val cellsY = (dy / cellHeightPx).roundToInt()

        var newCellX = cellX
        var newSpanX = spanX
        if (bounds.canResizeH) {
            when (activeHandle) {
                Handle.RIGHT -> newSpanX = clampSpan(
                    startSpanX + cellsX, bounds.minX, min(bounds.maxX, startSpanX + room.right), spanX
                )
                Handle.LEFT -> {
                    newSpanX = clampSpan(
                        startSpanX - cellsX, bounds.minX, min(bounds.maxX, startSpanX + room.left), spanX
                    )
                    newCellX = startCellX + (startSpanX - newSpanX)
                }
                else -> Unit
            }
        }

        var newCellY = cellY
        var newSpanY = spanY
        if (bounds.canResizeV) {
            when (activeHandle) {
                Handle.BOTTOM -> newSpanY = clampSpan(
                    startSpanY + cellsY, bounds.minY, min(bounds.maxY, startSpanY + room.bottom), spanY
                )
                Handle.TOP -> {
                    newSpanY = clampSpan(
                        startSpanY - cellsY, bounds.minY, min(bounds.maxY, startSpanY + room.top), spanY
                    )
                    newCellY = startCellY + (startSpanY - newSpanY)
                }
                else -> Unit
            }
        }

        if (newSpanX == spanX && newSpanY == spanY && newCellX == cellX && newCellY == cellY) return
        cellX = newCellX
        cellY = newCellY
        spanX = newSpanX
        spanY = newSpanY
        snapTo(cellX, cellY, spanX, spanY)
    }

    // Animates the frame (and the widget under it) from wherever the last
    // step left them to the new cells, in fractions of a cell mid-slide.
    private fun snapTo(toCellX: Int, toCellY: Int, toSpanX: Int, toSpanY: Int) {
        snapAnimator?.cancel()
        val frameLp = layoutParams as? WorkspaceCanvas.WidgetParams ?: return
        val fromCellX = frameLp.cellX
        val fromCellY = frameLp.cellY
        val fromSpanX = frameLp.spanX
        val fromSpanY = frameLp.spanY
        // Exact at both ends, so a finished slide sits on whole cells.
        fun lerp(from: Float, to: Int, fraction: Float): Float = from * (1f - fraction) + to * fraction
        fun apply(fraction: Float) {
            frameLp.cellX = lerp(fromCellX, toCellX, fraction)
            frameLp.cellY = lerp(fromCellY, toCellY, fraction)
            frameLp.spanX = lerp(fromSpanX, toSpanX, fraction)
            frameLp.spanY = lerp(fromSpanY, toSpanY, fraction)
            layoutParams = frameLp
            placeWidget()
        }
        if (snapDurationMs <= 0L) {
            apply(1f)
            return
        }
        snapAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = snapDurationMs
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { apply(it.animatedValue as Float) }
            start()
        }
    }

    // The widget takes the frame's cells, however far a slide has got; the
    // canvas lays it out inset by the widget padding, so the two meet at
    // every step.
    private fun placeWidget() {
        val frameLp = layoutParams as? WorkspaceCanvas.WidgetParams ?: return
        val view = widgetView ?: return
        val lp = view.layoutParams as? WorkspaceCanvas.WidgetParams ?: return
        lp.cellX = frameLp.cellX
        lp.cellY = frameLp.cellY
        lp.spanX = frameLp.spanX
        lp.spanY = frameLp.spanY
        view.layoutParams = lp
    }

    // A provider min above the available room means this axis simply can't
    // resize right now; keep the current span rather than crash or overlap.
    private fun clampSpan(value: Int, lo: Int, hi: Int, fallback: Int): Int =
        if (hi < lo) fallback else value.coerceIn(lo, hi)

    private fun handleAt(x: Float, y: Float): Handle = when {
        bounds.canResizeH && x <= touchSlop -> Handle.LEFT
        bounds.canResizeH && x >= width - touchSlop -> Handle.RIGHT
        bounds.canResizeV && y <= touchSlop -> Handle.TOP
        bounds.canResizeV && y >= height - touchSlop -> Handle.BOTTOM
        else -> Handle.NONE
    }

    companion object {
        private const val HANDLE_RADIUS_DP = 8f
        private const val OUTLINE_COLOR = 0xCCFFFFFF.toInt()
        private const val HANDLE_COLOR = 0xFFFFFFFF.toInt()
    }
}
