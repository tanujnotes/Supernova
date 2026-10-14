package com.supernova.launcher.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import com.supernova.launcher.R
import com.supernova.launcher.data.LaunchableApp

// Horizontal strip of the most-launched apps for the search overlay's
// zero-query state. Icons come from the shared catalog cache; each gets a
// constantState copy because ImageView assigns its own bounds and the cached
// instance is simultaneously drawn at drawer bounds.
class FrequentAppsRowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    init {
        orientation = HORIZONTAL
        val horizontal = dp(12)
        setPadding(horizontal, dp(4), horizontal, dp(4))
    }

    // One cell: apps and search providers both reduce to this. onLongPress,
    // when set, gets the cell (the drawer's rows: menu and drag); it comes
    // before onTap so every caller keeps passing the tap as a trailing lambda.
    class Entry(
        val label: String,
        val icon: Drawable?,
        val onLongPress: ((View) -> Unit)? = null,
        val onTap: (View) -> Unit
    )

    fun bind(
        apps: List<LaunchableApp>,
        iconFor: (LaunchableApp) -> Drawable?,
        onTap: (View, LaunchableApp) -> Unit
    ) = bind(apps.map { app -> Entry(app.label, iconFor(app)) { view -> onTap(view, app) } })

    // iconSizeDp lets the "Search with" marks sit smaller than app icons, so a
    // row of providers never reads as a row of apps.
    fun bind(entries: List<Entry>, iconSizeDp: Int = APP_ICON_DP) {
        removeAllViews()
        val iconSize = dp(iconSizeDp)
        val cellPadding = dp(8)
        entries.take(MAX_ITEMS).forEach { entry ->
            val cell = ImageView(context).apply {
                setPadding(cellPadding, cellPadding, cellPadding, cellPadding)
                contentDescription = entry.label
                foreground = context.getDrawable(R.drawable.ripple_icon)
                val icon = entry.icon
                setImageDrawable(
                    icon?.constantState?.newDrawable(resources)?.mutate() ?: icon
                )
                setOnClickListener { entry.onTap(it) }
                entry.onLongPress?.let { onLongPress ->
                    setOnLongClickListener {
                        onLongPress(it)
                        true
                    }
                }
            }
            addView(cell, LayoutParams(0, iconSize + cellPadding * 2, 1f))
        }
    }

    // The feed row: larger icons with labels in fixed cells from the
    // left, up to five, instead of the overlay's icon-only strip.
    // columns > 0 lines the cells up with a grid of that many columns under
    // the row (the drawer, the search window): one cell per column, the
    // grid's icon size, the grid's side padding.
    fun bindLabeled(entries: List<Entry>, textColor: Int, columns: Int = 0, sidePaddingDp: Int = 12) {
        removeAllViews()
        val gridAligned = columns > 0
        // Room above and below, clear of the field or pills over the row and
        // the hairline or pills under it.
        val vertical = dp(LABELED_ROW_VERTICAL_DP)
        setPadding(dp(sidePaddingDp), vertical, dp(sidePaddingDp), vertical)
        val iconSize = dp(if (gridAligned) APP_ICON_DP else LABELED_ICON_DP)
        val shown = entries.take(if (gridAligned) columns else MAX_LABELED)
        shown.forEach { entry ->
            val cell = LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                setPadding(dp(4), dp(6), dp(4), dp(6))
                contentDescription = entry.label
                foreground = context.getDrawable(R.drawable.ripple_icon)
                setOnClickListener { entry.onTap(it) }
                entry.onLongPress?.let { onLongPress ->
                    setOnLongClickListener {
                        onLongPress(it)
                        true
                    }
                }
            }
            val icon = ImageView(context).apply {
                // The drawer tile's id: a drag lifts its picture from it, and
                // a launch opens out of the icon rather than the whole cell.
                id = R.id.appIcon
                val drawable = entry.icon
                setImageDrawable(drawable?.constantState?.newDrawable(resources)?.mutate() ?: drawable)
            }
            cell.addView(icon, LayoutParams(iconSize, iconSize))
            cell.addView(
                android.widget.TextView(context).apply {
                    text = entry.label
                    setTextColor(textColor)
                    textSize = 12f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    gravity = android.view.Gravity.CENTER_HORIZONTAL
                    setPadding(0, dp(6), 0, 0)
                },
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            )
            addView(
                cell,
                if (gridAligned) LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                else LayoutParams(dp(LABELED_CELL_DP), LayoutParams.WRAP_CONTENT)
            )
        }
        // Empty columns keep the filled ones at the grid's width.
        if (gridAligned) repeat(columns - shown.size) { addView(View(context), LayoutParams(0, 1, 1f)) }
    }


    private companion object {
        const val MAX_ITEMS = 8
        const val APP_ICON_DP = 48
        const val MAX_LABELED = 4
        const val LABELED_ICON_DP = 56
        const val LABELED_CELL_DP = 88
        const val LABELED_ROW_VERTICAL_DP = 12
    }
}
