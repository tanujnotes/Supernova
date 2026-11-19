package com.supernova.launcher.ui

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.PopupWindow
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
        mode: FolderMode = FolderMode.WINDOW,
        scroll: FolderScroll = FolderScroll.VERTICAL,
        // Resolved FOLDER surface; null keeps the drawable's default color.
        surfaceColor: Int? = null,
        // The resolved FOLDER text, for the name, its hint, the count and the
        // menu button; null keeps the theme's text color.
        textColor: Int? = null,
        // Style-engine radii/cuts for the card, in dp; null keeps bg_folder's
        // built-in 24dp round corners.
        cornerRadiiDp: FloatArray? = null,
        // folderShowName pref: hides the title row, its menu button too. The
        // folder's long-press menu still has what that menu held.
        showTitle: Boolean = true,
        // folderShowCount pref: the number of apps beside the name.
        showCount: Boolean = false,
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
        // False: the title is a name to read, not a field to rename in (the
        // private space card's).
        titleEditable: Boolean = true,
        // A text button on the title row, before the menu (the private space
        // card's Lock).
        actionLabel: CharSequence? = null,
        onAction: ((View) -> Unit)? = null,
        onRename: (String) -> Unit,
        // The title row's overflow button, handed over to anchor a menu on.
        onMenu: ((View) -> Unit)? = null,
        onDismiss: (() -> Unit)? = null,
        // The screen y the card stays above: the dock's top, so a folder
        // opened from the dock or the home grid never covers the dock's
        // icons. Null leaves the whole screen to it.
        keepAboveScreenY: Int? = null
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
                context.dp(24).toFloat()
            )
        } else {
            surfaceColor?.let { color ->
                // The card shares bg_drawer's constant state with the drawer
                // sheet; mutate before recoloring so the sheet keeps its color.
                (binding.folderCard.background?.mutate() as? GradientDrawable)?.setColor(color)
            }
        }
        // The title row reads on the card's own color: a fixed hint was white
        // on the light theme's white card, so an unnamed folder showed nothing.
        val text = textColor ?: context.getColor(com.supernova.launcher.R.color.textPrimary)
        binding.folderTitle.setTextColor(text)
        binding.folderTitle.setHintTextColor(androidx.core.graphics.ColorUtils.setAlphaComponent(text, HINT_ALPHA))
        binding.folderCount.setTextColor(androidx.core.graphics.ColorUtils.setAlphaComponent(text, COUNT_ALPHA))
        binding.folderEditButton.imageTintList = android.content.res.ColorStateList.valueOf(text)
        binding.folderAction.setTextColor(text)
        binding.folderTitle.visibility = if (showTitle) View.VISIBLE else View.GONE
        if (!titleEditable) {
            binding.folderTitle.apply {
                isFocusable = false
                isFocusableInTouchMode = false
                isLongClickable = false
                isCursorVisible = false
                keyListener = null
            }
        }

        val immersive = mode == FolderMode.IMMERSIVE
        val metrics = context.resources.displayMetrics
        val cellWidth = cellWidthPx(context, adapter.iconScale)
        val columns = columnsFor(context, members.size, columnsOverride, immersive, adapter.iconScale)

        // Cells are wide enough for a typical label at 12sp, and the grid
        // width is fixed so GridLayoutManager centres each tile in its span
        // instead of packing wrap_content tiles against the left edge.
        val gridWidth = min(columns * cellWidth, metrics.widthPixels - context.dp(48))
        val rowsNeeded = ceil(members.size / columns.toFloat()).toInt().coerceAtLeast(1)
        // Window folders cap at ~60% of the screen so long folders scroll;
        // immersive gets most of the height. Over the dock, no more than
        // the room above it (less the title row and the card's padding,
        // estimated: the card isn't measured yet).
        val screenTop = IntArray(2).also(anchor.rootView::getLocationOnScreen)[1]
        val roomAboveDock = keepAboveScreenY?.let { y ->
            y - screenTop - context.dp(STATUS_ROOM_DP) - context.dp(DOCK_GAP_DP) - 2 * context.dp(CARD_MARGIN_DP) -
                context.dp(if (showTitle) CARD_CHROME_DP else CARD_PADDING_DP)
        }
        val maxGridHeight = minOf(
            (metrics.heightPixels * if (immersive) 0.72f else 0.6f).toInt(),
            roomAboveDock ?: Int.MAX_VALUE
        ).coerceAtLeast(context.dp(MIN_GRID_DP))
        // A full-screen card centres between the bars and, over the dock,
        // above it.
        val dockClearance = keepAboveScreenY?.let { y ->
            screenTop + anchor.rootView.height - y + context.dp(DOCK_GAP_DP)
        } ?: 0
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
                val cellHeight = measureCellHeight(adapter, members, gridWidth / columns)
                val visibleRows = rowsNeeded.coerceAtMost((maxGridHeight / cellHeight).coerceAtLeast(1))
                // Pages filled row by row, the order the preview reads.
                binding.folderGrid.layoutManager = PagedGridLayoutManager(columns, visibleRows)
                // Fixed viewport so paging has a page to snap to.
                binding.folderGrid.layoutParams = binding.folderGrid.layoutParams.apply {
                    width = gridWidth
                    height = visibleRows * cellHeight
                }
                attachColumnPaging(binding.folderGrid, gridWidth, smooth = animate)
            }
        }

        if (!shadow) binding.folderCard.elevation = 0f

        binding.folderGrid.adapter = adapter
        adapter.submit(members)
        if (showTitle && showCount) {
            // Kept to the folder's contents as apps leave or are added.
            fun showCount() {
                binding.folderCount.text = java.text.NumberFormat.getIntegerInstance().format(adapter.itemCount)
            }
            binding.folderCount.visibility = View.VISIBLE
            showCount()
            adapter.registerAdapterDataObserver(
                object : androidx.recyclerview.widget.RecyclerView.AdapterDataObserver() {
                    override fun onChanged() = showCount()
                }
            )
        }

        if (actionLabel != null && onAction != null && showTitle) {
            binding.folderAction.text = actionLabel
            binding.folderAction.visibility = View.VISIBLE
            binding.folderAction.setOnClickListener { onAction(it) }
        }

        if (onMenu != null && showTitle) {
            binding.folderEditButton.visibility = View.VISIBLE
            binding.folderEditButton.setOnClickListener { onMenu(it) }
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
            // Below Android 11 a resizing window is what reports the keyboard
            // at all; the card is lifted clear of it below either way. From
            // 11 the keyboard arrives as an inset, so the window keeps its
            // size: resized, it squeezed a long full-screen card into the
            // space above the keyboard, centred on the middle of its grid.
            softInputMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            } else {
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            }
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

        // Where the card rests once positioned: over its icon in WINDOW mode,
        // centred in IMMERSIVE.
        var restingY = 0f

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
                // Down from above the keyboard too, if the title had it up.
                .translationY(restingY)
                .setDuration((CLOSE_MS * speed).toLong())
                .setInterpolator(COLLAPSE)
                .withEndAction { popup.dismiss() }
                .start()
        }
        binding.folderScrim.setOnClickListener { animateClose() }

        // Renaming in the title brings up the keyboard, which covered a card
        // opened low on the screen, title and all. While it is up the card
        // rises until its title row clears it, as a panned window would, and
        // it settles back when the keyboard goes.
        var imeBottom = 0
        fun clearKeyboard() {
            val card = binding.folderCard
            if (!card.isLaidOut || closing) return
            val keyboardTop = binding.root.height - imeBottom
            val cardBottom = card.top + restingY + card.height + context.dp(KEYBOARD_GAP_DP)
            val target = restingY - (cardBottom - keyboardTop).coerceAtLeast(0f)
            if (card.translationY == target) return
            if (animate) {
                card.animate().translationY(target)
                    .setDuration((LIFT_MS * speed).toLong()).setInterpolator(EXPAND).start()
            } else {
                card.translationY = target
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { root, insets ->
            imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            if (immersive) {
                // The scrim runs behind the bars (layOutBehindBars); the card
                // centres in what is left between them.
                val bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                )
                root.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, dockClearance))
            }
            clearKeyboard()
            insets
        }
        // A window the keyboard resized (below Android 11) reports no
        // overlap, only a shorter root.
        binding.root.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) clearKeyboard()
        }

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
                val margin = context.dp(CARD_MARGIN_DP)
                val left = (anchorAt[0] - rootAt[0] + (anchor.width - card.width) / 2)
                    .coerceIn(margin, (binding.root.width - card.width - margin).coerceAtLeast(margin))
                // Its bottom stays above the dock, when there is one.
                val bottom = keepAboveScreenY?.let { y -> y - rootAt[1] - context.dp(DOCK_GAP_DP) }
                    ?.coerceAtMost(binding.root.height - margin) ?: (binding.root.height - margin)
                val top = (anchorAt[1] - rootAt[1] + (anchor.height - card.height) / 2)
                    .coerceIn(margin, (bottom - card.height).coerceAtLeast(margin))
                card.translationX = left.toFloat()
                restingY = top.toFloat()
                card.translationY = restingY
            }
            startExpand(card)
        }

        if (immersive) layOutInScreen(popup, binding.root)
        popup.showAtLocation(anchor, Gravity.CENTER, 0, 0)
        if (immersive) layOutBehindBars(binding.root)
        return popup
    }

    // A popup window fits inside the system bars by default, so the
    // full-screen scrim stopped at them and transparent bars showed the
    // undimmed wallpaper as two bright bands (#256). These let the window
    // run under the bars; the insets listener pads the card back clear.
    @Suppress("DEPRECATION")
    private fun layOutInScreen(popup: PopupWindow, root: View) {
        popup.isClippingEnabled = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) popup.setIsLaidOutInScreen(true)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // Before insets types, the layout flags are what reach the nav bar.
            root.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    // From Android 11 the window's own fit-insets types decide, and
    // PopupWindow has no setter for them: clear them on the shown window's
    // params, which PopupWindow.update() edits in place and so keeps.
    private fun layOutBehindBars(root: View) {
        val decor = root.rootView
        val params = decor.layoutParams as? WindowManager.LayoutParams ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            params.setFitInsetsTypes(0)
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        context.getSystemService(WindowManager::class.java)?.updateViewLayout(decor, params)
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
    private fun attachColumnPaging(
        grid: androidx.recyclerview.widget.RecyclerView,
        pageWidth: Int,
        smooth: Boolean
    ) {
        if (pageWidth <= 0) return
        // With animations off a page lands at once.
        fun scrollBy(rv: androidx.recyclerview.widget.RecyclerView, dx: Int) =
            if (smooth) rv.smoothScrollBy(dx, 0) else rv.scrollBy(dx, 0)
        fun maxOffset(rv: androidx.recyclerview.widget.RecyclerView) =
            (rv.computeHorizontalScrollRange() - rv.computeHorizontalScrollExtent()).coerceAtLeast(0)
        grid.onFlingListener = object : androidx.recyclerview.widget.RecyclerView.OnFlingListener() {
            override fun onFling(velocityX: Int, velocityY: Int): Boolean {
                val offset = grid.computeHorizontalScrollOffset()
                val page = offset / pageWidth
                val target = (if (velocityX > 0) page + 1 else page) * pageWidth
                scrollBy(grid, target.coerceIn(0, maxOffset(grid)) - offset)
                return true
            }
        }
        grid.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: androidx.recyclerview.widget.RecyclerView, newState: Int) {
                if (newState != androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE) return
                // A drag let go without a fling settles on the nearer page.
                val offset = rv.computeHorizontalScrollOffset()
                val target = (((offset + pageWidth / 2) / pageWidth) * pageWidth).coerceIn(0, maxOffset(rv))
                if (target != offset) scrollBy(rv, target - offset)
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

    companion object {
        private const val HINT_ALPHA = 0x80
        private const val COUNT_ALPHA = 0xB3

        // A member's icon box grows with the folder's icon size
        // (FolderMemberAdapter), so its cell grows with it, as the drawer's
        // automatic cell does; a smaller icon keeps the base cell for its
        // label.
        private fun cellWidthPx(context: Context, iconScale: Float): Int =
            (context.dp(CELL_WIDTH_DP) * iconScale.coerceAtLeast(1f)).toInt()

        // How many columns a folder of `count` apps opens with. The preview
        // asks too, to show the window's top-left corner.
        fun columnsFor(context: Context, count: Int, columnsOverride: Int, immersive: Boolean, iconScale: Float): Int {
            if (columnsOverride > 0) return columnsOverride
            val portraitColumns = when {
                immersive -> 4
                count <= 4 -> 2
                count <= 9 -> 3
                else -> 4
            }
            // Landscape has width to spare and very little height: the same
            // column count there makes a tall card that has to scroll before
            // it has shown four apps, so the members lay out wider instead.
            // Capped by what the window can actually hold, and never wider
            // than the folder has members to fill.
            val metrics = context.resources.displayMetrics
            return if (metrics.widthPixels <= metrics.heightPixels) {
                portraitColumns
            } else {
                val fitsWidth = ((metrics.widthPixels - context.dp(48)) / cellWidthPx(context, iconScale))
                    .coerceAtLeast(2)
                (portraitColumns + LANDSCAPE_EXTRA_COLUMNS)
                    .coerceAtMost(fitsWidth)
                    .coerceAtMost(count.coerceAtLeast(2))
            }
        }

        // 70%-black dim behind the immersive card (#B3000000).
        private const val IMMERSIVE_SCRIM = -0x4D000000

        // Member tile width at the base icon size: the 48dp icon plus room
        // for a one-line 12sp label.
        private const val CELL_WIDTH_DP = 76

        // Columns a landscape folder gains over its portrait count, trading
        // the height it does not have for the width it does.
        private const val LANDSCAPE_EXTRA_COLUMNS = 2

        private const val OPEN_MS = 240L
        private const val CLOSE_MS = 160L
        private const val SCRIM_MS = 120L
        private const val LIFT_MS = 200L

        // Room left between the card's title row and the keyboard's top.
        private const val KEYBOARD_GAP_DP = 12
        // A window card's distance from the screen's edges, wide enough for
        // its shadow to fade out inside the window.
        private const val CARD_MARGIN_DP = 20
        // Between a card and the dock it stays above.
        private const val DOCK_GAP_DP = 8
        // Estimates for sizing the grid above the dock before the card is
        // measured: the status bar, the title row with the card's padding,
        // the padding alone, and the least grid left to scroll in.
        private const val STATUS_ROOM_DP = 48
        private const val CARD_CHROME_DP = 88
        private const val CARD_PADDING_DP = 32
        private const val MIN_GRID_DP = 96

        // Material fast-out-slow-in out, and its accelerating counterpart
        // for collapsing back into the icon.
        private val EXPAND = PathInterpolator(0.4f, 0f, 0.2f, 1f)
        private val COLLAPSE = PathInterpolator(0.4f, 0f, 1f, 1f)
    }
}
