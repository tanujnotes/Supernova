package com.supernova.launcher.ui.settings

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.core.graphics.withTranslation
import androidx.recyclerview.widget.RecyclerView
import com.supernova.launcher.R

// A settings page's group labels stick to the top of the list while their
// cards scroll under them, on a strip of the page colour; the next label
// pushes the stuck one up and off. The label is the same view the list
// uses, so it keeps its text style and its place over the card's content.
class StickyGroupLabels(context: Context, private val adapter: SettingsAdapter) :
    RecyclerView.ItemDecoration() {

    var pageColor: Int
        get() = stripPaint.color
        set(value) {
            stripPaint.color = value
        }

    private val gutter = context.resources.getDimensionPixelSize(R.dimen.settings_gutter)
    private val topPad = context.resources.getDimensionPixelSize(R.dimen.space_sm)
    private val stripPaint = Paint()
    private var label: TextView? = null
    private var labelTitle = 0

    override fun onDrawOver(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val top = firstVisible(parent) ?: return
        val position = parent.getChildAdapterPosition(top)
        val title = adapter.labelOver(position)
        // While the label's own row still shows in full, it is its own label.
        val ownRowShows = adapter.isLabel(position) && top.top >= topPad
        if (title == 0 || ownRowShows) return
        val view = labelView(parent, title)
        val strip = topPad + view.height
        // The next label, coming up from below, pushes this one up.
        val nextTop = attached(parent)
            .filter { (at, _) -> at > position && adapter.isLabel(at) }
            .minOfOrNull { (_, child) -> child.top + child.translationY.toInt() }
        val shift = if (nextTop != null && nextTop < strip) nextTop - strip else 0
        canvas.withTranslation(0f, shift.toFloat()) {
            drawRect(0f, 0f, parent.width.toFloat(), strip.toFloat(), stripPaint)
            withTranslation(gutter.toFloat(), topPad.toFloat()) { view.draw(this) }
        }
    }

    // The list's laid-out rows with their adapter positions.
    private fun attached(parent: RecyclerView): List<Pair<Int, View>> =
        (0 until parent.childCount).map { parent.getChildAt(it) }
            .map { parent.getChildAdapterPosition(it) to it }
            .filter { (at, _) -> at != RecyclerView.NO_POSITION }

    // The topmost row still (partly) in view.
    private fun firstVisible(parent: RecyclerView): View? =
        attached(parent).map { it.second }
            .filter { it.bottom + it.translationY > 0 }
            .minByOrNull { it.top }

    private fun labelView(parent: RecyclerView, title: Int): TextView {
        val width = parent.width - 2 * gutter
        val view = label ?: (
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_setting_group_label, parent, false) as TextView
            ).also { label = it }
        if (title != labelTitle || view.width != width) {
            view.setText(title)
            labelTitle = title
            view.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        }
        return view
    }

}
