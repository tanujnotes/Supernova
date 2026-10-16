package com.supernova.launcher.ui.settings

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.supernova.launcher.ui.dp

// The cards of a grouped settings page (SettingsAdapter.grouped): each run
// of rows between Headers sits on one rounded card, 16dp in from the sides,
// with a 4dp inset above its first row and below its last and a hairline
// between rows from the text line (72dp in) to the edge. 24dp separates a
// card from the next group's label; the label sits 8dp over its card (its
// own padding). Each row draws its own slice of the card, so a card scrolled
// half off screen still draws right. Nothing on an ungrouped page.
class SettingsGroupDecoration(context: Context, private val adapter: SettingsAdapter) :
    RecyclerView.ItemDecoration() {

    var cardColor: Int
        get() = cardPaint.color
        set(value) {
            cardPaint.color = value
        }

    var dividerColor: Int
        get() = dividerPaint.color
        set(value) {
            dividerPaint.color = value
        }

    private val gutter = context.dp(GUTTER_DP)
    private val inset = context.dp(INSET_DP)
    private val groupGap = context.dp(GROUP_GAP_DP)
    // The search bar's own 8dp bottom padding makes up the rest of the 24dp
    // between it and the first card.
    private val firstGap = context.dp(GROUP_GAP_DP - SEARCH_PADDING_DP)
    private val radius = context.dp(CORNER_DP).toFloat()
    private val dividerStart = context.dp(DIVIDER_START_DP)
    private val dividerHeight = context.dp(DIVIDER_DP).coerceAtLeast(1)
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dividerPaint = Paint()
    private val slice = RectF()
    private val path = Path()
    private val radii = FloatArray(RADII_SIZE)

    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        if (!adapter.grouped) return
        val position = parent.getChildAdapterPosition(view)
        if (position == RecyclerView.NO_POSITION) return
        outRect.left = gutter
        outRect.right = gutter
        if (adapter.isGroupCard(position)) {
            if (adapter.startsCard(position)) outRect.top = inset + if (position == 0) firstGap else 0
            if (adapter.endsCard(position)) outRect.bottom = inset
        } else {
            outRect.top = if (position == 0) firstGap else groupGap
        }
    }

    override fun onDraw(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        if (!adapter.grouped) return
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val position = parent.getChildAdapterPosition(child)
            if (position == RecyclerView.NO_POSITION || !adapter.isGroupCard(position)) continue
            val first = adapter.startsCard(position)
            val last = adapter.endsCard(position)
            val top = child.top + child.translationY
            val bottom = child.bottom + child.translationY
            slice.set(
                child.left.toFloat(), if (first) top - inset else top,
                child.right.toFloat(), if (last) bottom + inset else bottom
            )
            val upper = if (first) radius else 0f
            val lower = if (last) radius else 0f
            // Corner pairs (x, y) clockwise from top left: the top two,
            // then the bottom two.
            radii.fill(upper, 0, RADII_TOP_END)
            radii.fill(lower, RADII_TOP_END, RADII_SIZE)
            path.reset()
            path.addRoundRect(slice, radii, Path.Direction.CW)
            canvas.drawPath(path, cardPaint)
            if (!last) {
                canvas.drawRect(
                    child.left.toFloat() + dividerStart, bottom - dividerHeight,
                    child.right.toFloat(), bottom, dividerPaint
                )
            }
        }
    }

    private companion object {
        const val GUTTER_DP = 16
        const val INSET_DP = 4
        const val GROUP_GAP_DP = 24
        const val SEARCH_PADDING_DP = 8
        const val CORNER_DP = 20
        const val DIVIDER_START_DP = 72
        const val DIVIDER_DP = 1
        const val RADII_TOP_END = 4
        const val RADII_SIZE = 8
    }
}
