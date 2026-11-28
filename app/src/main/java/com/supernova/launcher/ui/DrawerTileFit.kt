package com.supernova.launcher.ui

import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.supernova.launcher.R
import com.supernova.launcher.databinding.ItemDrawerAppBinding
import kotlin.math.roundToInt

// How a drawer tile fits a grid row of a set height: the vertical grid with
// Rows set shares the list's height out between that many rows. A row with
// room keeps the tile's padding around its icon and label, centred. A
// shorter one gives the padding up first, then puts the label on one line,
// then shrinks the label into what the icon leaves. The icon holds the size
// the Icon size bar asked for throughout - the size the home screen and the
// dock draw it at, labels or no labels (#383) - and only a row shorter than
// the icon itself shrinks the icon, as a too-small cell does on the home
// screen. Before, the row stopped shrinking at the tile's own height, so a
// dense grid showed fewer rows than it was set to and left gaps between
// them.
data class DrawerTileFit(
    // The tile's top and bottom padding.
    val paddingPx: Int,
    // The label kept to one line whatever the label settings say.
    val singleLine: Boolean,
    // Icon size, 1 at its own.
    val iconScale: Float,
    // Gap and label size, 1 at their own.
    val labelScale: Float = iconScale
) {
    companion object {
        // A grid row's height when the list's `heightPx` is shared out
        // between `rows`; 0 (the tiles' own height) for Auto. A count the
        // user set is a portrait one, as the paged style's is, so in
        // `landscape` a row never gets shorter than `compactPx`, a tile with
        // its label on one line: the list scrolls there rather than shrinking
        // icons into rows a short window has no room for.
        fun rowHeight(heightPx: Int, rows: Int, compactPx: Int, landscape: Boolean): Int {
            if (rows <= 0 || heightPx <= 0) return 0
            val shared = heightPx / rows
            return if (landscape) maxOf(shared, compactPx) else shared
        }

        // rowPx: the row's height, 0 for the tile's own. paddingPx: the tile's
        // own top and bottom padding. contentPx: its icon, gap and label with
        // the labels wrapping as set; compactPx: the same on one line. 0 for
        // either when not measured yet, which leaves the tile as it is.
        // iconPx: the icon box measured in those, 0 when unknown (everything
        // then shrinks alike).
        fun of(rowPx: Int, paddingPx: Int, contentPx: Int, compactPx: Int, iconPx: Int = 0): DrawerTileFit {
            val spare = rowPx - contentPx
            val compact = compactPx.takeIf { it in 1..contentPx } ?: contentPx
            return when {
                rowPx <= 0 || contentPx <= 0 -> DrawerTileFit(paddingPx, singleLine = false, iconScale = 1f)
                spare >= 0 -> DrawerTileFit(minOf(paddingPx, spare / 2), singleLine = false, iconScale = 1f)
                rowPx >= compact -> DrawerTileFit(0, singleLine = true, iconScale = 1f)
                else -> shrunk(rowPx, compact, iconPx.coerceIn(0, compact))
            }
        }

        // A one-line tile `compact` tall into a shorter row. The label gives
        // way first and the icon keeps the size the Icon size bar asked for,
        // the size the home screen and the dock draw it at (#383); only a
        // row too short for the icon itself shrinks the icon, and then there
        // is no room for a label at all.
        private fun shrunk(rowPx: Int, compact: Int, iconPx: Int): DrawerTileFit {
            val label = (compact - iconPx).toFloat()
            return when {
                // No icon box measured: everything shrinks alike.
                iconPx <= 0 -> DrawerTileFit(0, singleLine = true, iconScale = rowPx.toFloat() / compact)
                // Shorter than the icon itself: the icon fits the row and
                // there is nothing left for a label beside it.
                rowPx < iconPx ->
                    DrawerTileFit(0, singleLine = true, iconScale = rowPx.toFloat() / iconPx, labelScale = 0f)
                // Otherwise the label takes what the icon leaves.
                else -> DrawerTileFit(
                    0,
                    singleLine = true,
                    iconScale = 1f,
                    labelScale = if (label > 0f) ((rowPx - iconPx) / label).coerceIn(0f, 1f) else 1f
                )
            }
        }

        // Puts a tile (item_drawer_app) into a grid row: `rowPx` tall, or its
        // own height at 0, with its icon box `iconPx` before the fit. The
        // label is styled before this; its size is taken as the unscaled one
        // unless it is still the size this last gave it.
        fun apply(tile: ItemDrawerAppBinding, rowPx: Int, iconPx: Int, fit: DrawerTileFit) {
            val root = tile.root
            val height = if (rowPx > 0) rowPx else ViewGroup.LayoutParams.WRAP_CONTENT
            if (root.layoutParams.height != height) root.layoutParams = root.layoutParams.apply { this.height = height }
            val gravity = if (rowPx > 0) Gravity.CENTER else Gravity.CENTER_HORIZONTAL
            if (root.gravity != gravity) root.gravity = gravity
            if (root.paddingTop != fit.paddingPx || root.paddingBottom != fit.paddingPx) {
                root.setPadding(root.paddingLeft, fit.paddingPx, root.paddingRight, fit.paddingPx)
            }
            val box = (iconPx * fit.iconScale).roundToInt().coerceAtLeast(1)
            val icon = tile.appIcon.layoutParams
            if (icon.width != box || icon.height != box) {
                icon.width = box
                icon.height = box
                tile.appIcon.layoutParams = icon
            }
            fitLabel(tile.appLabel, fit)
        }

        private fun fitLabel(label: TextView, fit: DrawerTileFit) {
            if (fit.singleLine) LabelStyler.capLines(label, 1)
            val gap = (LABEL_GAP_DP * label.resources.displayMetrics.density * fit.labelScale).roundToInt()
            val params = label.layoutParams as LinearLayout.LayoutParams
            if (params.topMargin != gap) {
                params.topMargin = gap
                label.layoutParams = params
            }
            val lastSet = label.getTag(R.id.tag_tile_label_px) as? Float
            val base = (label.getTag(R.id.tag_tile_label_base_px) as? Float)
                ?.takeIf { lastSet == label.textSize }
                ?: label.textSize
            val size = base * fit.labelScale
            if (label.textSize != size) label.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
            label.setTag(R.id.tag_tile_label_base_px, base)
            label.setTag(R.id.tag_tile_label_px, label.textSize)
        }

        // item_drawer_app's gap between icon and label.
        const val LABEL_GAP_DP = 7
    }
}
