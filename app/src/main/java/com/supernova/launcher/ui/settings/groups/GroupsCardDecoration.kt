package com.supernova.launcher.ui.settings.groups

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.supernova.launcher.R
import com.supernova.launcher.ui.dp

// The Drawer groups list's cards, to the settings pages' measures
// (SettingsGroupDecoration): each run of group rows with its New row sits on
// one rounded card 16dp in from the sides, 4dp clear of its first and last
// row, with a hairline between rows from the text line; a label stands 24dp
// under what is above it, and a card's help line 8dp under the card.
//
// The card is drawn where the rows are laid out, not where they are moving:
// a dragged row leaves a band in its place and floats over a card that
// stays whole, and the rows it passes slide across it.
class GroupsCardDecoration(context: Context, private val adapter: GroupListAdapter) :
    RecyclerView.ItemDecoration() {

    var cardColor: Int
        get() = cardPaint.color
        set(value) {
            cardPaint.color = value
        }

    private val gutter = context.dp(GUTTER_DP)
    private val inset = context.dp(INSET_DP)
    private val radius = context.dp(CORNER_DP).toFloat()
    private val dividerStart = context.dp(DIVIDER_START_DP)
    private val dividerHeight = context.dp(1).coerceAtLeast(1)
    private val gaps = Gaps(
        first = context.dp(FIRST_GAP_DP), firstLabel = context.dp(ENTRY_GAP_DP),
        label = context.dp(GROUP_GAP_DP), caption = context.dp(CAPTION_GAP_DP), entry = context.dp(ENTRY_GAP_DP)
    )
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bandPaint = Paint().apply { color = context.getColor(R.color.settingsBand) }
    private val dividerPaint = Paint().apply { color = context.getColor(R.color.settingsDivider) }
    private val slice = RectF()
    private val path = Path()
    private val radii = FloatArray(RADII_SIZE)

    private class Gaps(val first: Int, val firstLabel: Int, val label: Int, val caption: Int, val entry: Int)

    private fun startsCard(position: Int) = adapter.isCardRow(position) && !adapter.isCardRow(position - 1)

    private fun endsCard(position: Int) = adapter.isCardRow(position) && !adapter.isCardRow(position + 1)

    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        val position = parent.getChildAdapterPosition(view)
        val row = adapter.row(position) ?: return
        outRect.left = gutter
        outRect.right = gutter
        outRect.top = when {
            position == 0 -> if (row is GroupListRow.Header) gaps.firstLabel else gaps.first
            row is GroupListRow.Header -> gaps.label
            row is GroupListRow.Caption -> gaps.caption
            row is GroupListRow.Empty -> gaps.entry
            startsCard(position) -> inset
            else -> 0
        }
        if (endsCard(position)) outRect.bottom = inset
    }

    override fun onDraw(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val position = parent.getChildAdapterPosition(child)
            if (!adapter.isCardRow(position)) continue
            val first = startsCard(position)
            val last = endsCard(position)
            val top = child.top.toFloat()
            val bottom = child.bottom.toFloat()
            slice.set(
                child.left.toFloat(), if (first) top - inset else top,
                child.right.toFloat(), if (last) bottom + inset else bottom
            )
            radii.fill(if (first) radius else 0f, 0, RADII_TOP_END)
            radii.fill(if (last) radius else 0f, RADII_TOP_END, RADII_SIZE)
            path.reset()
            path.addRoundRect(slice, radii, Path.Direction.CW)
            canvas.drawPath(path, cardPaint)
            // The place a lifted row left.
            if (child.isActivated) canvas.drawRect(child.left.toFloat(), top, child.right.toFloat(), bottom, bandPaint)
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
        const val CORNER_DP = 20
        const val DIVIDER_START_DP = 72
        const val FIRST_GAP_DP = 8
        const val GROUP_GAP_DP = 24
        const val CAPTION_GAP_DP = 8
        const val ENTRY_GAP_DP = 16
        const val RADII_TOP_END = 4
        const val RADII_SIZE = 8
    }
}
