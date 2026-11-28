package com.supernova.launcher.ui

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.supernova.launcher.R
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// An icon's left (or top) edge along one side of the canvas: its centre at
// `center` of `extent`, pulled back in where that would hang its `size` over
// an edge.
internal fun iconOrigin(center: Float, extent: Int, size: Int, anchor: Int = size): Int =
    (center.coerceIn(0f, 1f) * extent - anchor / 2f).roundToInt().coerceIn(0, max(0, extent - size))

// The part of an icon item `height` tall that is centred on its point: the
// icon and its label's first line. Further label lines hang below (#346):
// centring the whole item put a one-line label's icon half a line lower than
// a two-line neighbour's, so icons in a row didn't share a top.
internal fun iconAnchorHeight(item: View, height: Int = item.measuredHeight): Int {
    val label = item.findViewById<TextView>(R.id.label)?.takeIf { it.isVisible }
    val layout = label?.layout ?: return height
    val lines = if (label.maxLines > 0) minOf(layout.lineCount, label.maxLines) else layout.lineCount
    return if (lines <= 1) height else height - (layout.getLineTop(lines) - layout.getLineTop(1))
}

// A widget's left (or top) edge along one side of the canvas: `cell` cells of
// the `count` across `extent`, then `inset` in. Cells are whole pixels, as the
// drop maths (workspacePlacement) and the resize frame count them.
internal fun widgetOrigin(cell: Float, count: Int, extent: Int, inset: Int): Int =
    (cell * cellSize(extent, count)).roundToInt() + inset

// Its width (or height): `span` of those cells, less the inset at both ends.
internal fun widgetSize(span: Float, count: Int, extent: Int, inset: Int): Int =
    max(1, (span * cellSize(extent, count)).roundToInt() - inset * 2)

// One of the `count` whole-pixel cells across `extent`.
internal fun cellSize(extent: Int, count: Int): Int = max(1, extent / max(1, count))

// The box an icon `boxPx` square is drawn at in a `cellW` x `cellH` cell it
// keeps `padPx` of to every edge: its own size wherever that fits, and the
// cell less that padding where it does not, because a dense grid's cells
// are smaller than an icon and neighbours drew over each other. The label
// is not in it and nor is the padding scaled: one icon size is one icon
// size on the home screen, in the dock and in the drawer, labels or no
// labels (#383).
internal fun fittedIconPx(cellW: Int, cellH: Int, boxPx: Int, padPx: Int): Int {
    if (min(cellW, cellH) <= 0 || boxPx <= 0) return boxPx
    return minOf(boxPx, cellW - padPx * 2, cellH - padPx * 2).coerceAtLeast(1)
}

// A large icon's box, `span` cells a side: it fills its block rather than
// doubling, its top on the top row's icon line and its label on the bottom
// row's label line, so it reads as part of the grid. Laid out centred on the
// block, a box `span - 1` cell heights bigger than a one-cell icon's
// (`boxPx`, at the grid's fit scale like `padPx`) lands on both lines. No
// wider than the block, less the item's padding.
internal fun largeIconBoxPx(span: Int, cellW: Int, cellH: Int, boxPx: Int, padPx: Int): Int {
    if (span <= 1) return boxPx
    val fill = (span - 1) * cellH + boxPx
    val room = span * cellW - 2 * padPx
    return max(boxPx, min(fill, room))
}

// A workspace page's canvas. Items are placed against the canvas's size at
// every layout rather than the one it had at bind. The canvas can change size
// after the page binds - on a cold start the page binds before the canvas has
// a size at all, and the dock's real height lands after the first layout and
// the pager shrinks by it - and items placed once stayed where the old size
// put them: icons lower than drops land, a bottom-row icon cut off by the
// canvas edge, and widgets sized from the display rather than the canvas.
//
// Icons carry a normalized centre (IconParams) and are laid out on it here,
// at least a cell wide: an icon owns its cell, so its label runs the
// cell's width before it fades out, not just the icon's. An icon that needs
// more room than its cell shrinks into it (IconFit), as the cell's size is
// only known here: a cold start binds the page before the canvas has one.
// Widgets carry their cells
// (WidgetParams), which become their margins and size at every measure, and
// so does the resize frame around one, with no inset.
//
// Laid out, not moved with x/y, so translation is left to the reorder
// preview: a relayout in the middle of a preview doesn't snap a shifted item
// back home.
class WorkspaceCanvas @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    // An icon's tile wraps it tightly, so a corner badge (#250) reaches past
    // the tile's edge, as an icon's shadow nearly does; nothing here may
    // cut it.
    init {
        clipChildren = false
    }

    // True while the page's icons, folders and widgets take no touches of
    // their own (home edit mode, #348): the canvas keeps the whole stream,
    // so a tap is a tap on the page and a long-press lifts it.
    var itemsInert: () -> Boolean = { false }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean =
        itemsInert() || super.onInterceptTouchEvent(ev)

    // Told about the lift of a touch stream the page itself took - a tap on
    // empty space - so the wallpaper can be handed the tap (#379). Fed by the
    // page's touch listener, which only hears those: a stream an icon, folder
    // or widget took never reaches the page, and one the pager took for a
    // page swipe, the drag layer for a drag or the drawer, search, the feed
    // and the home swipes for themselves ends in a cancel, not a lift. That
    // is the reference launcher's rule too: it sends the command from the
    // workspace's own touch listener. Set by the activity, which decides
    // whether the tap goes to the wallpaper at all.
    var onPageTap: (MotionEvent) -> Unit = {}

    // The canvas has a new size, so the cell its icons are fitted into has
    // one too. Surfaces drawn at the size the desktop's icons end up at -
    // a dock that matches the desktop size (#383) - are told, since nothing
    // in the prefs changed. Posted from the measure that found the new size,
    // so a listener can lay views out. Set by the activity when the page
    // binds, so a recycled canvas keeps one listener, not one per bind.
    var onRoomChanged: () -> Unit = {}

    private var roomWidth = 0
    private var roomHeight = 0

    // A one-cell icon's box at the size its bar asks for, the padding it
    // keeps to its cell, and how it sets its parts: the icon to `iconPx`
    // (its own size unless the cell is too small for it) and the label into
    // `labelRoomPx`, what the icon leaves of the cell. A large icon shrinks
    // as the grid's one-cell icons do and grows its icon to fill its block
    // (largeIconBoxPx).
    class IconFit(
        val boxPx: Int,
        val padPx: Int,
        val apply: (iconPx: Int, labelRoomPx: Int) -> Unit
    )

    // `cols` x `rows` is the grid, which sets the cell an icon widens to and,
    // with a `fit`, shrinks into; 0 leaves it at its own size. A large
    // icon's `span` makes that cell `span` cells a side.
    class IconParams(
        val centerX: Float,
        val centerY: Float,
        val cols: Int = 0,
        val rows: Int = 0,
        val fit: IconFit? = null,
        val span: Int = 1
    ) : FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT) {
        // The size the icon was last drawn at, and the room that left the
        // label.
        var fitIconPx = -1
        var fitLabelRoomPx = -1
    }

    // `spanX` x `spanY` cells of the `cols` x `rows` grid from (`cellX`,
    // `cellY`), inset on every side. Floats so the resize frame can slide
    // itself and the widget between two snaps; a bound widget sits on whole
    // cells. LEFT, not START: cells count from the left in either layout
    // direction, as drops do.
    class WidgetParams(
        cellX: Int,
        cellY: Int,
        spanX: Int,
        spanY: Int,
        val cols: Int,
        val rows: Int,
        // Settable, so a padding being picked can preview on the widget.
        var inset: Int
    ) : FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.LEFT
    ) {
        var cellX = cellX.toFloat()
        var cellY = cellY.toFloat()
        var spanX = spanX.toFloat()
        var spanY = spanY.toFloat()
    }

    // The canvas matches its page, so it is always measured at an exact size:
    // the one the widgets' cells are worked out against, before FrameLayout
    // measures and places them.
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        fitIcons(width, height)
        for (i in 0 until childCount) {
            val params = getChildAt(i).layoutParams as? WidgetParams ?: continue
            params.leftMargin = widgetOrigin(params.cellX, params.cols, width, params.inset)
            params.topMargin = widgetOrigin(params.cellY, params.rows, height, params.inset)
            params.width = widgetSize(params.spanX, params.cols, width, params.inset)
            params.height = widgetSize(params.spanY, params.rows, height, params.inset)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val params = child.layoutParams as? IconParams ?: continue
            if (params.cols <= 0 || child.visibility == GONE) continue
            val cell = cellSize(width, params.cols) * params.span
            if (child.measuredWidth >= cell) continue
            child.measure(
                MeasureSpec.makeMeasureSpec(cell, MeasureSpec.EXACTLY),
                getChildMeasureSpec(heightMeasureSpec, paddingTop + paddingBottom, params.height)
            )
        }
    }

    // The page padding the canvas sits in, for the Home screen preview's
    // padding bars (IconFitRoom).
    private fun recordMargins() {
        val margins = layoutParams as? MarginLayoutParams ?: return
        IconFitRoom.desktopMarginHPx = margins.leftMargin
        IconFitRoom.desktopMarginVPx = margins.topMargin
    }

    // A size this canvas has not had before, told to whoever draws at the
    // size the icons end up at (onRoomChanged). Posted, since this runs in
    // the middle of the measure that found it.
    private fun recordRoom(width: Int, height: Int) {
        if (width == roomWidth && height == roomHeight) return
        roomWidth = width
        roomHeight = height
        post { onRoomChanged() }
    }

    // Before the children are measured, so they are measured as they will
    // draw. Nothing while the canvas has no size yet.
    private fun fitIcons(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        IconFitRoom.desktopWidthPx = width
        IconFitRoom.desktopHeightPx = height
        recordMargins()
        recordRoom(width, height)
        for (i in 0 until childCount) {
            val params = getChildAt(i).layoutParams as? IconParams ?: continue
            if (params.cols <= 0 || params.rows <= 0) continue
            fitIcon(params, cellSize(width, params.cols), cellSize(height, params.rows))
        }
    }

    // One icon into its cell: the icon and its padding by the cell's own
    // scale, and the label into what they leave of the block. Nothing when
    // the icon already has that size and that much room.
    private fun fitIcon(params: IconParams, cellW: Int, cellH: Int) {
        val fit = params.fit ?: return
        val fitted = fittedIconPx(cellW, cellH, fit.boxPx, fit.padPx)
        val iconPx = largeIconBoxPx(params.span, cellW, cellH, fitted, fit.padPx)
        val labelRoom = (cellH * params.span - iconPx - fit.padPx * 2).coerceAtLeast(0)
        if (iconPx == params.fitIconPx && labelRoom == params.fitLabelRoomPx) return
        params.fitIconPx = iconPx
        params.fitLabelRoomPx = labelRoom
        fit.apply(iconPx, labelRoom)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val width = right - left
        val height = bottom - top
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val params = child.layoutParams as? IconParams ?: continue
            if (child.visibility == GONE) continue
            val x = iconOrigin(params.centerX, width, child.measuredWidth)
            val y = iconOrigin(params.centerY, height, child.measuredHeight, iconAnchorHeight(child))
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
        }
    }
}
