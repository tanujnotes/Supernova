package com.supernova.launcher.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import com.supernova.launcher.data.WorkspaceGeometry
import com.supernova.launcher.ui.WorkspaceCanvas
import com.supernova.launcher.ui.cellSize
import kotlin.math.roundToInt

// Launcher3-style resize frame: an outline over the widget with four handles.
// Any widget resizes both ways, whatever its provider declares, down to one
// step. A drag from anywhere along an edge, either side of it, moves that edge,
// a corner both of its edges, snapping to whole cells. Left/top drags move the
// anchor cell in lockstep with the span so the opposite edge stays fixed. The
// activity hands a drag over from dispatchTouchEvent (WidgetController), so
// nothing else reads it, and the grab band can reach outside this view.
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
    // Steps per cell: 2 with subgrid positioning, which resizes in half
    // cells. cellX, spanX and the rest, and what roomProvider and onCommit
    // are handed, all count these steps.
    private val units: Int = 1,
    private val onCommit: (cellX: Int, cellY: Int, spanX: Int, spanY: Int) -> Unit
) : View(context) {

    enum class Press { EDGE, WIDGET, OUTSIDE }

    var cellX: Int = 0
        private set
    var cellY: Int = 0
        private set
    var spanX: Int = 1
        private set
    var spanY: Int = 1
        private set

    // The widget's host view, resized live under the frame.
    private var widgetView: View? = null

    private val handleRadius = HANDLE_RADIUS_DP * resources.displayMetrics.density
    private val grabBand = GRAB_BAND_DP * resources.displayMetrics.density

    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        color = OUTLINE_COLOR
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = HANDLE_COLOR }

    // The edges the drag in progress moves.
    private var dragLeft = false
    private var dragRight = false
    private var dragTop = false
    private var dragBottom = false
    private val dragging get() = dragLeft || dragRight || dragTop || dragBottom
    private var dragScale = 1f
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

    // Puts the frame on the widget's cells, around its host view: when the
    // frame opens, and after every rebind of its page, which gives the widget
    // new params from the model (a committed resize does) or a new view. A
    // drag in progress keeps its own cells - that rebind is catching up with
    // an earlier lift - and hands them to the new params; its own lift
    // commits, and the rebind after it anchors the frame again.
    // False if the view isn't a widget laid out in cells.
    fun anchor(widgetView: View): Boolean {
        val cells = widgetView.layoutParams as? WorkspaceCanvas.WidgetParams ?: return false
        this.widgetView = widgetView
        if (dragging) {
            placeWidget()
        } else {
            cellX = (cells.cellX * units).roundToInt()
            cellY = (cells.cellY * units).roundToInt()
            spanX = (cells.spanX * units).roundToInt()
            spanY = (cells.spanY * units).roundToInt()
            layoutParams = WorkspaceCanvas.WidgetParams(0, 0, 1, 1, cells.cols, cells.rows, 0).apply {
                this.cellX = this@WidgetResizeFrame.cellX / units.toFloat()
                this.cellY = this@WidgetResizeFrame.cellY / units.toFloat()
                this.spanX = this@WidgetResizeFrame.spanX / units.toFloat()
                this.spanY = this@WidgetResizeFrame.spanY / units.toFloat()
            }
            invalidate()
        }
        return true
    }

    // What a press at this screen point is on, starting the drag if that is an
    // edge's band. Not shown also covers a frame left on a page the pager has
    // let go. Measured in the frame's own pixels: edit mode scales the page
    // down, and the band stays the same size on screen.
    fun press(rawX: Float, rawY: Float): Press {
        if (!isShown) return Press.OUTSIDE
        getLocationOnScreen(screenLocation)
        dragScale = screenScale()
        val x = (rawX - screenLocation[0]) / dragScale
        val y = (rawY - screenLocation[1]) / dragScale
        val band = grabBand / dragScale
        val inBand = within(x, width, band) && within(y, height, band)
        val edgeX = if (inBand) edgeOf(x, width, band) else 0
        val edgeY = if (inBand) edgeOf(y, height, band) else 0
        dragLeft = edgeX < 0
        dragRight = edgeX > 0
        dragTop = edgeY < 0
        dragBottom = edgeY > 0
        if (dragging) beginDrag(rawX, rawY)
        return when {
            dragging -> Press.EDGE
            within(x, width, 0f) && within(y, height, 0f) -> Press.WIDGET
            else -> Press.OUTSIDE
        }
    }

    private fun beginDrag(rawX: Float, rawY: Float) {
        val canvas = parent as? View ?: return
        val cells = layoutParams as? WorkspaceCanvas.WidgetParams ?: return
        dragStartX = rawX
        dragStartY = rawY
        startCellX = cellX
        startCellY = cellY
        startSpanX = spanX
        startSpanY = spanY
        cellWidthPx = (cellSize(canvas.width, cells.cols) / units).coerceAtLeast(1)
        cellHeightPx = (cellSize(canvas.height, cells.rows) / units).coerceAtLeast(1)
        room = roomProvider(cellX, cellY, spanX, spanY)
    }

    // How much the views above it scale this frame on screen.
    private fun screenScale(): Float {
        var scale = 1f
        var view: View? = this
        while (view != null) {
            scale *= view.scaleX
            view = view.parent as? View
        }
        return scale
    }

    fun dragTo(rawX: Float, rawY: Float) {
        if (dragging) applyDrag((rawX - dragStartX) / dragScale, (rawY - dragStartY) / dragScale)
    }

    fun endDrag() {
        if (!dragging) return
        dragLeft = false
        dragRight = false
        dragTop = false
        dragBottom = false
        // Land on the final rect before the model write rebinds the page, so
        // the rebind changes nothing visible.
        snapAnimator?.end()
        onCommit(cellX, cellY, spanX, spanY)
    }

    override fun onDraw(canvas: Canvas) {
        val inset = outlinePaint.strokeWidth
        canvas.drawRoundRect(
            inset, inset, width - inset, height - inset,
            8f * resources.displayMetrics.density, 8f * resources.displayMetrics.density,
            outlinePaint
        )
        canvas.drawCircle(inset, height / 2f, handleRadius, handlePaint)
        canvas.drawCircle(width - inset, height / 2f, handleRadius, handlePaint)
        canvas.drawCircle(width / 2f, inset, handleRadius, handlePaint)
        canvas.drawCircle(width / 2f, height - inset, handleRadius, handlePaint)
    }

    private fun applyDrag(dx: Float, dy: Float) {
        val cellsX = (dx / cellWidthPx).roundToInt()
        val cellsY = (dy / cellHeightPx).roundToInt()

        var newCellX = cellX
        var newSpanX = spanX
        if (dragRight) {
            newSpanX = (startSpanX + cellsX).coerceIn(MIN_SPAN, startSpanX + room.right)
        } else if (dragLeft) {
            newSpanX = (startSpanX - cellsX).coerceIn(MIN_SPAN, startSpanX + room.left)
            newCellX = startCellX + (startSpanX - newSpanX)
        }

        var newCellY = cellY
        var newSpanY = spanY
        if (dragBottom) {
            newSpanY = (startSpanY + cellsY).coerceIn(MIN_SPAN, startSpanY + room.bottom)
        } else if (dragTop) {
            newSpanY = (startSpanY - cellsY).coerceIn(MIN_SPAN, startSpanY + room.top)
            newCellY = startCellY + (startSpanY - newSpanY)
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
        // Exact at both ends, so a finished slide sits on whole steps.
        fun lerp(from: Float, to: Int, fraction: Float): Float =
            from * (1f - fraction) + to / units.toFloat() * fraction
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

    companion object {
        private const val HANDLE_RADIUS_DP = 9.5f
        // How far either side of an edge a press still takes it.
        private const val GRAB_BAND_DP = 24f
        // One step: a cell, or half a cell with subgrid positioning.
        private const val MIN_SPAN = 1
        private const val OUTLINE_COLOR = 0xCCFFFFFF.toInt()
        private const val HANDLE_COLOR = 0xFFFFFFFF.toInt()
    }
}

private fun within(position: Float, size: Int, margin: Float) = position >= -margin && position <= size + margin

// Which edge's band a position along one axis is in: -1 the start, 1 the end,
// 0 neither. On a widget narrower than two bands, the nearer edge.
private fun edgeOf(position: Float, size: Int, band: Float): Int = when {
    position < band && position <= size - position -> -1
    position > size - band -> 1
    else -> 0
}
