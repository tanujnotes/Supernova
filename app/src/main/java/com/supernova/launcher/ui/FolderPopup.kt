package com.supernova.launcher.ui

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.PopupWindow
import androidx.core.view.doOnLayout
import androidx.recyclerview.widget.GridLayoutManager
import com.supernova.launcher.data.LauncherItem
import com.supernova.launcher.databinding.PopupFolderBinding
import kotlin.math.ceil
import kotlin.math.min

// Opens a folder in place over the workspace instead of in a modal dialog.
// WINDOW mode is the classic anchored card; IMMERSIVE fills the screen over a
// dimmed scrim. Content lives in a RecyclerView grid that scrolls vertically
// or pages horizontally per FolderScroll. Reveal: the card expands
// out of the folder icon that was tapped and collapses back into it.
class FolderPopup(private val context: Context) {

    fun show(
        anchor: View,
        title: String,
        members: List<LauncherItem>,
        adapter: FolderMemberAdapter,
        columnsOverride: Int = 0,
        sortAz: Boolean = false,
        mode: FolderMode = FolderMode.WINDOW,
        scroll: FolderScroll = FolderScroll.VERTICAL,
        // Resolved FOLDER surface; null keeps the drawable's default color.
        surfaceColor: Int? = null,
        // Style-engine radii/cuts for the card, in dp; null keeps bg_folder's
        // built-in 24dp round corners.
        cornerRadiiDp: FloatArray? = null,
        cornerCuts: List<com.supernova.launcher.data.CornerCut>? = null,
        // folderShowName pref: hides the title text, keeping the edit button.
        showTitle: Boolean = true,
        // folderShadow pref: the card's own elevation, not the window's - the
        // popup background is transparent, so the layout's 8dp is the shadow
        // anyone actually sees. Matches the menus; the window fills the screen,
        // so the only thing that could cut this shadow off is the card sitting
        // too close to the window's edge, which the margin below covers.
        shadow: Boolean = true,
        // Animations.enabled: false skips the open/close transition.
        animate: Boolean = true,
        // Duration multiplier from the animation-speed pref.
        speed: Float = 1f,
        onRename: (String) -> Unit,
        onEdit: (() -> Unit)? = null,
        onDismiss: (() -> Unit)? = null
    ): PopupWindow {
        // A drawer folder can be opened with the drawer's search keyboard up;
        // the card takes focus and would drop it a step late otherwise.
        anchor.hideKeyboardForWindow()

        val binding = PopupFolderBinding.inflate(LayoutInflater.from(context))
        binding.folderTitle.setText(title)

        if (cornerRadiiDp != null) {
            // Style-engine corners replace the stock drawable outright.
            val density = context.resources.displayMetrics.density
            binding.folderCard.background = com.supernova.launcher.ui.theme.SurfaceShapes.background(
                surfaceColor ?: context.getColor(com.supernova.launcher.R.color.drawerBackground),
                FloatArray(4) { cornerRadiiDp[it] * density },
                cornerCuts,
                context.dp(24).toFloat()
            )
        } else {
            surfaceColor?.let { color ->
                // The card shares bg_drawer's constant state with the drawer
                // sheet; mutate before recoloring so the sheet keeps its color.
                (binding.folderCard.background?.mutate() as? GradientDrawable)?.setColor(color)
            }
        }
        binding.folderTitle.visibility = if (showTitle) View.VISIBLE else View.GONE

        val immersive = mode == FolderMode.IMMERSIVE
        val metrics = context.resources.displayMetrics
        // A member's icon box grows with the folder's icon size
        // (FolderMemberAdapter), so its cell grows with it, as the drawer's
        // automatic cell does; a smaller icon keeps the base cell for its
        // label.
        val cellWidth = (context.dp(CELL_WIDTH_DP) * adapter.iconScale.coerceAtLeast(1f)).toInt()
        val portraitColumns = when {
            immersive -> 4
            members.size <= 4 -> 2
            members.size <= 9 -> 3
            else -> 4
        }
        // Landscape has width to spare and very little height: the same
        // column count there makes a tall card that has to scroll before it
        // has shown four apps, so the members lay out wider instead. Capped
        // by what the window can actually hold, and never wider than the
        // folder has members to fill.
        val landscape = metrics.widthPixels > metrics.heightPixels
        val columns = columnsOverride.takeIf { it > 0 } ?: if (!landscape) {
            portraitColumns
        } else {
            val fitsWidth = ((metrics.widthPixels - context.dp(48)) / cellWidth).coerceAtLeast(2)
            (portraitColumns + LANDSCAPE_EXTRA_COLUMNS)
                .coerceAtMost(fitsWidth)
                .coerceAtMost(members.size.coerceAtLeast(2))
        }

        // Cells are wide enough for a typical label at 12sp, and the grid
        // width is fixed so GridLayoutManager centres each tile in its span
        // instead of packing wrap_content tiles against the left edge.
        val gridWidth = min(columns * cellWidth, metrics.widthPixels - context.dp(48))
        val rowsNeeded = ceil(members.size / columns.toFloat()).toInt().coerceAtLeast(1)
        // Window folders cap at ~60% of the screen so long folders scroll;
        // immersive gets most of the height.
        val maxGridHeight = (metrics.heightPixels * if (immersive) 0.72f else 0.6f).toInt()
        val ordered = if (sortAz) members.sortedBy { it.label.lowercase() } else members
        adapter.cellWidthPx = gridWidth / columns

        when (scroll) {
            FolderScroll.VERTICAL -> {
                binding.folderGrid.layoutManager = GridLayoutManager(context, columns)
                // Content-sized up to the cap, scrolling beyond it.
                binding.folderGrid.maxHeight = maxGridHeight
                binding.folderGrid.layoutParams = binding.folderGrid.layoutParams.apply {
                    width = gridWidth
                }
            }
            FolderScroll.HORIZONTAL -> {
                val cellHeight = measureCellHeight(adapter, ordered, gridWidth / columns)
                val visibleRows = rowsNeeded.coerceAtMost((maxGridHeight / cellHeight).coerceAtLeast(1))
                binding.folderGrid.layoutManager =
                    GridLayoutManager(context, visibleRows, GridLayoutManager.HORIZONTAL, false)
                // Fixed viewport so paging has a page to snap to.
                binding.folderGrid.layoutParams = binding.folderGrid.layoutParams.apply {
                    width = gridWidth
                    height = visibleRows * cellHeight
                }
                attachColumnPaging(binding.folderGrid, gridWidth)
            }
        }

        if (!shadow) binding.folderCard.elevation = 0f

        binding.folderGrid.adapter = adapter
        adapter.submit(ordered)

        if (onEdit != null) {
            binding.folderEditButton.visibility = View.VISIBLE
            binding.folderEditButton.setOnClickListener { onEdit() }
        }

        val popup = PopupWindow(
            binding.root,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            true
        ).apply {
            setBackgroundDrawable(ColorDrawable(0))
            elevation = 16f
            isOutsideTouchable = true
            // The expand-from-icon enter animation is driven in-view below.
            // The window style keeps a generic shrink+fade for the dismiss
            // paths that bypass the animated close (back key, launching an
            // app from the folder); 0 restores the old instant pop when
            // animations are disabled.
            animationStyle = if (animate) com.supernova.launcher.R.style.FolderPopupAnimation else 0
        }

        // The root always fills the window so the card can expand from the
        // icon underneath it; WINDOW mode just keeps the scrim transparent.
        if (immersive) binding.folderScrim.setBackgroundColor(IMMERSIVE_SCRIM)
        binding.folderCard.layoutParams = FrameLayout.LayoutParams(
            if (immersive) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            if (immersive) Gravity.CENTER else Gravity.TOP or Gravity.START
        ).apply { if (immersive) setMargins(context.dp(24), context.dp(24), context.dp(24), context.dp(24)) }
        // The card swallows taps; only true outside taps reach the scrim.
        binding.folderCard.isClickable = true

        // Collapse back into the folder icon, then dismiss for real. The
        // pivot/scale captured at open time make close the exact reverse.
        var openScale = 0.3f
        var closing = false
        fun animateClose() {
            if (closing) return
            if (!animate) {
                popup.dismiss()
                return
            }
            closing = true
            binding.root.animate().alpha(0f).setDuration((CLOSE_MS * speed).toLong()).start()
            binding.folderCard.animate()
                .scaleX(openScale).scaleY(openScale)
                .setDuration((CLOSE_MS * speed).toLong())
                .setInterpolator(COLLAPSE)
                .withEndAction { popup.dismiss() }
                .start()
        }
        binding.folderScrim.setOnClickListener { animateClose() }

        popup.setOnDismissListener {
            val newName = binding.folderTitle.text?.toString().orEmpty()
            if (newName.isNotBlank() && newName != title) onRename(newName)
            onDismiss?.invoke()
        }

        // Start invisible either way: WINDOW mode can only be positioned once
        // the card has a measured size, and showing it centred for one frame
        // before that would flash.
        binding.root.alpha = 0f
        if (animate) {
            binding.folderCard.scaleX = 0f
            binding.folderCard.scaleY = 0f
        }

        fun startExpand(card: View) {
            binding.root.animate().alpha(1f).setDuration((SCRIM_MS * speed).toLong()).start()
            if (!animate) return
            val anchorAt = IntArray(2).also(anchor::getLocationOnScreen)
            // The card is mid-transform, so derive its untransformed screen
            // position from the (unscaled) root plus layout left/top and the
            // positioning translation applied below.
            val rootAt = IntArray(2).also(binding.root::getLocationOnScreen)
            val anchorCenterX = anchorAt[0] + anchor.width / 2f
            val anchorCenterY = anchorAt[1] + anchor.height / 2f
            card.pivotX = (anchorCenterX - (rootAt[0] + card.left + card.translationX))
                .coerceIn(0f, card.width.toFloat())
            card.pivotY = (anchorCenterY - (rootAt[1] + card.top + card.translationY))
                .coerceIn(0f, card.height.toFloat())
            openScale = (anchor.width.toFloat() / card.width.coerceAtLeast(1))
                .coerceIn(0.15f, 0.45f)
            card.scaleX = openScale
            card.scaleY = openScale
            card.animate()
                .scaleX(1f).scaleY(1f)
                .setDuration((OPEN_MS * speed).toLong())
                .setInterpolator(EXPAND)
                .start()
        }

        binding.folderCard.doOnLayout { card ->
            if (!immersive) {
                // The folder expands in place: the card sits centred
                // over its own icon, clamped inside the screen with a small
                // margin. Positioned via translation - a margin change here
                // would need a second layout pass, and requestLayout from
                // inside a layout listener is unreliable.
                val anchorAt = IntArray(2).also(anchor::getLocationOnScreen)
                val rootAt = IntArray(2).also(binding.root::getLocationOnScreen)
                // Wide enough for the card's own shadow to fade out inside
                // the window rather than being cut off against its edge; a
                // material shadow reaches about twice the card's elevation.
                val margin = context.dp(20)
                val left = (anchorAt[0] - rootAt[0] + (anchor.width - card.width) / 2)
                    .coerceIn(margin, (binding.root.width - card.width - margin).coerceAtLeast(margin))
                val top = (anchorAt[1] - rootAt[1] + (anchor.height - card.height) / 2)
                    .coerceIn(margin, (binding.root.height - card.height - margin).coerceAtLeast(margin))
                card.translationX = left.toFloat()
                card.translationY = top.toFloat()
            }
            startExpand(card)
        }

        popup.showAtLocation(anchor, Gravity.CENTER, 0, 0)
        return popup
    }


    // Real height of one member tile at the cell width, from a probe holder
    // bound off-screen with the longest label (the one most likely to wrap)
    // and its icon box sized as every member's is. Horizontal paging needs
    // it to size a viewport of whole rows.
    // Horizontal folders page by the viewport: a whole page of columns per
    // swipe, settling with its columns square to the edges. PagerSnapHelper
    // centred a single column instead, so with an even column count the
    // tiles at both edges were cut in half after every swipe, and a fling
    // moved one column.
    private fun attachColumnPaging(grid: androidx.recyclerview.widget.RecyclerView, pageWidth: Int) {
        if (pageWidth <= 0) return
        fun maxOffset(rv: androidx.recyclerview.widget.RecyclerView) =
            (rv.computeHorizontalScrollRange() - rv.computeHorizontalScrollExtent()).coerceAtLeast(0)
        grid.onFlingListener = object : androidx.recyclerview.widget.RecyclerView.OnFlingListener() {
            override fun onFling(velocityX: Int, velocityY: Int): Boolean {
                val offset = grid.computeHorizontalScrollOffset()
                val page = offset / pageWidth
                val target = (if (velocityX > 0) page + 1 else page) * pageWidth
                grid.smoothScrollBy(target.coerceIn(0, maxOffset(grid)) - offset, 0)
                return true
            }
        }
        grid.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: androidx.recyclerview.widget.RecyclerView, newState: Int) {
                if (newState != androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE) return
                // A drag let go without a fling settles on the nearer page.
                val offset = rv.computeHorizontalScrollOffset()
                val target = (((offset + pageWidth / 2) / pageWidth) * pageWidth).coerceIn(0, maxOffset(rv))
                if (target != offset) rv.smoothScrollBy(target - offset, 0)
            }
        })
    }

    private fun measureCellHeight(adapter: FolderMemberAdapter, members: List<LauncherItem>, cellWidth: Int): Int {
        val sample = members.maxByOrNull { it.label.length } ?: return context.dp(72)
        adapter.submit(listOf(sample))
        val holder = adapter.createViewHolder(FrameLayout(context), 0)
        adapter.bindViewHolder(holder, 0)
        holder.itemView.measure(
            View.MeasureSpec.makeMeasureSpec(cellWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        return holder.itemView.measuredHeight.coerceAtLeast(1)
    }

    private companion object {
        // 70%-black dim behind the immersive card (#B3000000).
        const val IMMERSIVE_SCRIM = -0x4D000000

        // Member tile width at the base icon size: the 48dp icon plus room
        // for a one-line 12sp label.
        const val CELL_WIDTH_DP = 76

        // Columns a landscape folder gains over its portrait count, trading
        // the height it does not have for the width it does.
        const val LANDSCAPE_EXTRA_COLUMNS = 2

        const val OPEN_MS = 240L
        const val CLOSE_MS = 160L
        const val SCRIM_MS = 120L

        // Material fast-out-slow-in out, and its accelerating counterpart
        // for collapsing back into the icon.
        val EXPAND = PathInterpolator(0.4f, 0f, 0.2f, 1f)
        val COLLAPSE = PathInterpolator(0.4f, 0f, 1f, 1f)
    }
}
