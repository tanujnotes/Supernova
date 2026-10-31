package com.supernova.launcher.ui

import android.content.res.Configuration
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.doOnLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.supernova.launcher.R
import com.supernova.launcher.data.ContainerType
import com.supernova.launcher.data.ItemType
import com.supernova.launcher.data.LauncherItem
import com.supernova.launcher.data.LauncherPrefs
import com.supernova.launcher.data.LauncherRepository
import com.supernova.launcher.data.WorkspaceGeometry
import com.supernova.launcher.databinding.ViewWorkspaceAddPageBinding
import com.supernova.launcher.databinding.ViewWorkspacePageBinding
import com.supernova.launcher.widget.WidgetResizeFrame
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

// How much of the desktop's vertical padding survives in landscape. The pref
// is a portrait number of dp; the same value against a landscape window is a
// far larger share of a much shorter height.
private const val LANDSCAPE_PADDING_V_SCALE = 0.45f

// The page padding above and below the grid, in pixels. The dock chrome
// spaces the page indicator and the swipe arrow in the one under it.
internal fun pagePaddingVPx(paddingDp: Int, density: Float, landscape: Boolean): Int =
    (paddingDp * density * if (landscape) LANDSCAPE_PADDING_V_SCALE else 1f).toInt()

// Snaps a normalized coordinate to the nearest half-cell step, e.g. on a
// 4-column grid: 0.125, 0.25, 0.375, ... - subgrid positioning, where
// an icon centres on a cell or on the line between two. The outermost steps
// are left out so the icon's one-cell footprint stays on the grid.
internal fun snapToHalfCell(normalized: Float, cellCount: Int): Float {
    val steps = max(1, cellCount) * 2
    val step = (normalized * steps).roundToInt().coerceIn(1, steps - 1)
    return step.toFloat() / steps
}

// Converts a raw canvas position into the cell + snapped float coordinates a
// drop produces, plus the unsnapped position in cell units (for the folder
// centre test): [cellX, cellY, workspaceX, workspaceY, rawCellX, rawCellY].
// Shared by the page's tap path and the drag layer's hover/drop path so both
// agree on where a point lands.
internal fun workspacePlacement(
    canvasWidth: Int,
    canvasHeight: Int,
    cols: Int,
    rows: Int,
    subgrid: Boolean,
    rawX: Float,
    rawY: Float
): FloatArray {
    val cellWidth = max(1, canvasWidth / max(1, cols))
    val cellHeight = max(1, canvasHeight / max(1, rows))
    val x = rawX.coerceIn(0f, canvasWidth.toFloat())
    val y = rawY.coerceIn(0f, canvasHeight.toFloat())
    val cellX = (x / cellWidth).toInt().coerceIn(0, cols - 1)
    val cellY = (y / cellHeight).toInt().coerceIn(0, rows - 1)
    val normX = (x / max(1f, canvasWidth.toFloat())).coerceIn(0f, 1f)
    val normY = (y / max(1f, canvasHeight.toFloat())).coerceIn(0f, 1f)
    val wx: Float
    val wy: Float
    if (subgrid) {
        wx = snapToHalfCell(normX, cols)
        wy = snapToHalfCell(normY, rows)
    } else {
        wx = (cellX + 0.5f) / cols
        wy = (cellY + 0.5f) / rows
    }
    return floatArrayOf(cellX.toFloat(), cellY.toFloat(), wx, wy, x / cellWidth, y / cellHeight)
}

// The same placement from a live canvas and the current prefs.
internal fun workspacePlacement(canvas: View, prefs: LauncherPrefs, rawX: Float, rawY: Float): FloatArray {
    val width = canvas.width.takeIf { it > 0 } ?: canvas.resources.displayMetrics.widthPixels
    val height = canvas.height.takeIf { it > 0 } ?: canvas.resources.displayMetrics.heightPixels
    return workspacePlacement(width, height, prefs.gridCols, prefs.gridRows, prefs.subgrid, rawX, rawY)
}

class WorkspacePagerAdapter(private val host: Host) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    interface Host {
        fun state(): LauncherUiState
        fun viewForItem(item: LauncherItem): View
        fun reportWidgetSize(item: LauncherItem, view: View)
        fun onCanvasLongPress()
        fun onCanvasTap(page: Int, cellX: Int, cellY: Int, workspaceX: Float, workspaceY: Float)

        // Edit mode's add-page slot: its "+" asks for a real page; a tap on
        // the rest of the slot is a tap on empty page space.
        fun onAddPageTap()
        fun onAddPageBackgroundTap()

        // Short-and-wide window. Part of a page's signature because it moves
        // the padding and the cell arithmetic while nothing in the prefs
        // changes at all.
        fun landscape(): Boolean

        // The item whose drop is still settling in the drag layer: a rebind
        // that lands mid-flight adds its view hidden, so the item is not seen
        // twice, and the layer shows it once the picture has arrived.
        fun isSettling(itemId: String): Boolean

        // The page has been built into this canvas. A widget resize frame the
        // bind left standing goes back around its widget here, or comes down
        // if its widget isn't on this canvas.
        fun onPageBound(canvas: ViewGroup)
    }

    private var pageCount: Int = 1
    // Infinite scroll: expose the pages three times over and keep the pager in
    // the middle copy (recenterIfNeeded), so a neighbour always exists on both
    // sides and the invisible jump lands on identical content.
    private var looping = false
    // Edit mode's add-page slot: one position past the last real
    // page, in every copy while looping, drawn as a "+" instead of a canvas.
    private var addSlot = false
    // Edit mode left while the pager sat on the slot: the pager slides back
    // onto the last page first and the slot goes once it has settled
    // (onPagerIdle), so the exit doesn't cut.
    private var slotRemovalPending = false
    // Signature of what each page is currently displaying, so untouched pages
    // are left alone instead of being torn down on every state emission.
    private val renderedPages = mutableMapOf<Int, String>()

    val realPageCount: Int get() = pageCount

    // Positions per copy: the real pages plus the slot while it is up.
    private val span: Int get() = pageCount + if (addSlot) 1 else 0

    // The slot reports as the page past the last one.
    fun realPage(position: Int): Int = if (looping) position % span else position

    fun isAddPage(position: Int): Boolean = addSlot && realPage(position) == pageCount

    // Target position for a programmatic jump to a real page: the middle copy
    // while looping, the page itself otherwise.
    fun centerPosition(realPage: Int): Int {
        val page = realPage.coerceIn(0, span - 1)
        return if (looping) span + page else page
    }

    fun recenterIfNeeded(pager: ViewPager2) {
        if (!looping) return
        val position = pager.currentItem
        if (position < span || position >= span * 2) {
            pager.setCurrentItem(centerPosition(realPage(position)), false)
        }
    }

    // The pager came to rest: a slot removal that waited for the slide back
    // onto the last page runs now, then the loop recentres as usual.
    fun onPagerIdle(pager: ViewPager2) {
        if (slotRemovalPending) {
            slotRemovalPending = false
            removeSlot(pager)
        }
        recenterIfNeeded(pager)
    }

    // Edit mode opening and closing. Nothing to add past the page limit.
    fun setAddPageSlot(show: Boolean, pager: ViewPager2) {
        if (show) {
            slotRemovalPending = false
            if (addSlot || pageCount >= LauncherRepository.MAX_PAGES) return
            val current = realPage(pager.currentItem)
            addSlot = true
            if (looping) rebuildAround(pager, current) else notifyItemInserted(pageCount)
        } else {
            if (!addSlot || slotRemovalPending) return
            if (isAddPage(pager.currentItem)) {
                slotRemovalPending = true
                pager.setCurrentItem(pager.currentItem - 1, true)
            } else {
                removeSlot(pager)
            }
        }
    }

    private fun removeSlot(pager: ViewPager2) {
        val current = realPage(pager.currentItem).coerceAtMost(pageCount - 1)
        addSlot = false
        if (looping) {
            rebuildAround(pager, current)
        } else {
            notifyItemRemoved(pageCount)
            if (pager.currentItem >= pageCount) pager.setCurrentItem(current, false)
        }
    }

    // Every copy changes length: rebuild, and put the pager back on the page
    // it was showing (identical content, so the jump is invisible).
    private fun rebuildAround(pager: ViewPager2, realPage: Int) {
        renderedPages.clear()
        notifyDataSetChanged()
        pager.setCurrentItem(centerPosition(realPage), false)
    }

    fun refresh() {
        val state = host.state()
        val model = state.model
        val itemMax = model.items
            .filter { it.container == ContainerType.WORKSPACE }
            .maxOfOrNull { it.page + 1 } ?: 1
        val newCount = max(1, max(model.pageCount, itemMax))
        val newLooping = state.prefs.infiniteScroll && newCount > 1

        // First bindable refresh: the item count goes 0 -> N, so this needs
        // the wholesale notify below rather than the per-page diff.
        val becameBindable = !bindable
        bindable = true

        if (becameBindable || newCount != pageCount || newLooping != looping) {
            pageCount = newCount
            looping = newLooping
            if (addSlot && pageCount >= LauncherRepository.MAX_PAGES) {
                addSlot = false
                slotRemovalPending = false
            }
            renderedPages.clear()
            notifyDataSetChanged()
            return
        }

        (0 until pageCount).forEach { page ->
            if (renderedPages[page] != pageSignature(state, page)) notifyRealPageChanged(page)
        }
    }

    // A real page renders at up to three positions while looping, a copy
    // apart - the add-page slot included, which is one more than the page
    // count: counted without it, edit mode rebound the page beside the one
    // that changed, and an icon removed there stayed on screen.
    private fun notifyRealPageChanged(page: Int) {
        notifyItemChanged(page)
        if (looping) {
            notifyItemChanged(page + span)
            notifyItemChanged(page + span * 2)
        }
    }

    // A widget has one host view, but infinite scroll binds a page at up to
    // three positions, so only the copy bound last holds the page's widgets.
    // With two pages both neighbours of the page in view are copies of the
    // other page, and the one bound first came into view with every widget
    // gone. The copies on screen take their widgets back: on every scroll
    // frame, and after any bind (a rebind of another copy can take them).
    private var list: RecyclerView? = null

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        list = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        list = null
    }

    fun claimWidgetsInView() {
        if (!looping) return
        val list = list ?: return
        for (i in 0 until list.childCount) {
            val child = list.getChildAt(i)
            val holder = list.getChildViewHolder(child) as? PageViewHolder
            val position = holder?.bindingAdapterPosition ?: RecyclerView.NO_POSITION
            val onScreen = child.right > 0 && child.left < list.width
            if (onScreen && position != RecyclerView.NO_POSITION && holder?.lostWidgets() == true) {
                holder.bind(realPage(position))
            }
        }
    }

    fun invalidateAll() {
        renderedPages.clear()
        notifyDataSetChanged()
    }

    // Rebind only the pages holding an item the caller cares about - a badge
    // count moving for one app should not tear down and re-inflate the whole
    // workspace, which is what invalidateAll does.
    //
    // Page-level rather than per-icon on purpose: ViewPager2's RecyclerView
    // re-attaches cached page holders without rebinding them, so swapping a
    // drawable in place would miss any page that is currently detached.
    //
    // Folder expansion is the caller's job: it knows whether folder badges
    // are on, and a folder's icon is drawn from its members.
    fun invalidatePagesWith(matches: (LauncherItem) -> Boolean) {
        if (!bindable) return
        val items = host.state().model.items
        (0 until pageCount).forEach { page ->
            val hit = items.any {
                it.container == ContainerType.WORKSPACE && it.page == page && matches(it)
            }
            if (hit) {
                renderedPages.remove(page)
                notifyRealPageChanged(page)
            }
        }
    }

    private fun pageSignature(state: LauncherUiState, page: Int): String {
        val pageItems = state.model.items.filter { it.container == ContainerType.WORKSPACE && it.page == page }
        // The page adds its items in list order, which is their stacking where
        // they overlap, so a Bring to front or Send to back rebinds it.
        val stack = pageItems.joinToString(",") { it.id }
        val items = pageItems
            .sortedBy { it.id }
            .joinToString(";") {
                "${it.id}:${it.cellX},${it.cellY},${it.spanX},${it.spanY},${it.halfCells}," +
                    "${it.workspaceX},${it.workspaceY},${it.label}," +
                    "${it.swipeAction},${it.swipeActionDown},${it.swipeToOpen},${it.widgetPadding}" +
                    // A folder's icon is drawn from its members, which live
                    // in the FOLDER container: an app dropped in from the
                    // dock or the drawer changes nothing else on this page,
                    // and the icon stayed a member short without this.
                    if (it.itemType == ItemType.FOLDER) {
                        folderSignature(state, it.id) + FolderIconDrawable.customIconKey(it)
                    } else {
                        ""
                    }
            }
        val prefs = state.prefs
        return "$items|$stack|${prefs.gridRows}x${prefs.gridCols}|${prefs.iconScale}|" +
            "${prefs.showLabels}|${prefs.subgrid}|${prefs.desktopPaddingH},${prefs.desktopPaddingV}|" +
            "${prefs.widgetPadding},${prefs.widgetCornerRadius}|" +
            "${prefs.labelSize},${prefs.labelColor},${prefs.labelShadow},${prefs.labelFont},${prefs.labelSingleLine}|" +
            "${prefs.iconSwipeIndicator}|" +
            // A rotation changes the padding and the cell arithmetic below,
            // and nothing in the prefs announces it.
            "${host.landscape()}|" +
            "${prefs.folderPreviewStyle}|${prefs.badgeColor},${prefs.badgePosition},${prefs.badgeScale}|" +
            // Folder icon appearance: the dock's signature takes every pref,
            // and without these a page kept its old plates until a restart.
            "${prefs.folderIconShape},${prefs.folderIconOutline},${prefs.folderClipToBg}," +
            "${prefs.folderMatchWindowColor},${prefs.folderPrioritizeFirst},${prefs.folderBgColor}," +
            "${prefs.styleConfig}|" +
            "${prefs.badgeTextColor},${prefs.badgeCornerRadius},${prefs.badgeOutline}|" +
            // The rest of the badge settings belong here too. They were
            // missing, and the only thing that applied them was the badge
            // collector rebuilding the world on every return to home - so
            // once that collector started diffing, a toggle flipped in
            // Settings stopped showing up at all.
            "${prefs.badges},${BadgeStyle.effective(prefs)},${prefs.folderBadges}|" +
            "${prefs.badgeApps.sorted().joinToString(",")}|" +
            "${state.iconGeneration}"
    }

    private fun folderSignature(state: LauncherUiState, folderId: String): String =
        state.model.items
            .filter { it.container == ContainerType.FOLDER && it.folderId == folderId }
            .sortedBy { it.rank }
            .joinToString(",", prefix = "[", postfix = "]") { "${it.id}:${it.packageName}/${it.className}" }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_ADD_PAGE) {
            AddPageViewHolder(ViewWorkspaceAddPageBinding.inflate(inflater, parent, false))
        } else {
            PageViewHolder(ViewWorkspacePageBinding.inflate(inflater, parent, false))
        }
    }

    // The pager is given its adapter in onCreate, and RecyclerView binds page
    // 0 the moment it has one - before the icon catalog is configured and the
    // first frame's icons are warmed, so every icon on that page rendered on
    // the main thread from a cold cache. Report no pages until the first
    // state emission that is ready to bind (refresh() flips this), so the
    // page is built once, with warm icons.
    private var bindable = false

    override fun getItemCount(): Int = when {
        !bindable -> 0
        looping -> span * 3
        else -> span
    }

    override fun getItemViewType(position: Int): Int =
        if (isAddPage(position)) TYPE_ADD_PAGE else TYPE_PAGE

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        // A wholesale rebind (a new day's calendar icons, a wallpaper color
        // change, a rotation, a page added) recycles the page views, and the
        // one landing on the page in view can be a neighbour's, still turned
        // or faded by the scroll effect. ViewPager2 re-runs the effect only on
        // a scroll, so that page stayed blank or shrunken until a swipe. At
        // rest the page in view wants no transform under any effect, and a
        // scroll re-applies the rest on its first frame.
        PageTransformers.resetTransforms(holder.itemView)
        if (holder is PageViewHolder) holder.bind(realPage(position))
        if (looping) list?.post(::claimWidgetsInView)
    }

    inner class AddPageViewHolder(binding: ViewWorkspaceAddPageBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.addPageButton.setOnClickListener { host.onAddPageTap() }
            binding.root.setOnClickListener { host.onAddPageBackgroundTap() }
        }
    }

    inner class PageViewHolder(private val binding: ViewWorkspacePageBinding) : RecyclerView.ViewHolder(binding.root) {

        // The widgets the last bind put on the canvas.
        private var widgets: List<Pair<LauncherItem, View>> = emptyList()

        init {
            // The canvas resizes its widgets along with itself, and it can
            // change size after the page binds - on a cold start the dock's
            // real height shrinks it a frame after the first layout - so each
            // new size goes on to their providers. This runs once the canvas
            // has laid them out; reportWidgetSize drops repeats.
            binding.workspaceCanvas.addOnLayoutChangeListener { canvas, left, top, right, bottom,
                                                                oldLeft, oldTop, oldRight, oldBottom ->
                if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                    widgets.forEach { (item, view) ->
                        if (view.parent === canvas) host.reportWidgetSize(item, view)
                    }
                }
            }
        }

        fun lostWidgets(): Boolean = widgets.any { (_, view) -> view.parent !== binding.workspaceCanvas }

        fun bind(page: Int) {
            val state = host.state()
            renderedPages[page] = pageSignature(state, page)
            val canvas = binding.workspaceCanvas
            val rows = state.prefs.gridRows
            val cols = state.prefs.gridCols

            val density = itemView.resources.displayMetrics.density
            // The vertical padding is a portrait number: in landscape the
            // same dp eats a far larger share of the height, squeezing the
            // rows into each other. The horizontal padding is untouched -
            // landscape has width to spare.
            val landscape = itemView.resources.configuration.orientation ==
                Configuration.ORIENTATION_LANDSCAPE
            val padH = (state.prefs.desktopPaddingH * density).toInt()
            val padV = pagePaddingVPx(state.prefs.desktopPaddingV, density, landscape)
            // Margins, not page padding: the drag grid's views around the
            // canvas span the whole page, so the sparkles on the grid's outer
            // edge are not clipped at the padding.
            val margins = canvas.layoutParams as ViewGroup.MarginLayoutParams
            if (margins.leftMargin != padH || margins.topMargin != padV ||
                margins.rightMargin != padH || margins.bottomMargin != padV
            ) {
                margins.setMargins(padH, padV, padH, padV)
                canvas.layoutParams = margins
            }

            // Everything but a widget resize frame. It outlives the rebind - a
            // committed resize is one - and a drag on it isn't cut off; the
            // host re-anchors it once the page is built (onPageBound).
            for (i in canvas.childCount - 1 downTo 0) {
                if (canvas.getChildAt(i) !is WidgetResizeFrame) canvas.removeViewAt(i)
            }
            val boundWidgets = mutableListOf<Pair<LauncherItem, View>>()

            val pageItems = state.model.items.filter { it.container == ContainerType.WORKSPACE && it.page == page }
            pageItems.forEach { item ->
                val child = host.viewForItem(item)
                if (child.parent != null) {
                    (child.parent as? ViewGroup)?.removeView(child)
                }
                // A reused view (cached widget host) may still carry the
                // drag-source hiding or a reorder-preview offset; the rebind
                // is authoritative that the item lives here, so it always
                // lands visible and unshifted - unless its drop is still in
                // flight, in which case the drag layer reveals it.
                child.alpha = if (host.isSettling(item.id)) 0f else 1f
                child.translationX = 0f
                child.translationY = 0f
                child.setTag(R.id.tag_item_id, item.id)
                child.setTag(R.id.tag_preview_base, null)

                when (item.itemType) {
                    ItemType.WIDGET -> {
                        // The canvas sizes and places the widget from these
                        // cells at every measure, against the size it has
                        // then - not its size now, which on a cold start is
                        // no size at all. The provider hears the first size
                        // here, and any later one through the canvas (see
                        // init).
                        // In cells, half ones included (subgrid placement).
                        val rect = WorkspaceGeometry.itemRect(item, cols, rows)
                        val spanX = (rect.right - rect.left).coerceIn(MIN_WIDGET_SPAN, cols.toFloat())
                        val spanY = (rect.bottom - rect.top).coerceIn(MIN_WIDGET_SPAN, rows.toFloat())
                        canvas.addView(
                            child,
                            WorkspaceCanvas.WidgetParams(
                                0, 0, 1, 1, cols, rows,
                                ((item.widgetPadding ?: state.prefs.widgetPadding) * density).toInt()
                            ).apply {
                                this.cellX = rect.left.coerceIn(0f, cols - MIN_WIDGET_SPAN)
                                this.cellY = rect.top.coerceIn(0f, rows - MIN_WIDGET_SPAN)
                                this.spanX = spanX
                                this.spanY = spanY
                            }
                        )
                        child.doOnLayout { host.reportWidgetSize(item, child) }
                        boundWidgets += item to child
                    }
                    else -> {
                        // The canvas lays the icon out on this centre at every
                        // layout, against the size it has then: it is on its
                        // point before the first draw, and stays on it when
                        // the canvas resizes after this bind.
                        val centerX = item.workspaceX ?: ((item.cellX + 0.5f) / max(1, cols))
                        val centerY = item.workspaceY ?: ((item.cellY + 0.5f) / max(1, rows))
                        val fit = child.getTag(R.id.tag_icon_fit) as? WorkspaceCanvas.IconFit
                        canvas.addView(child, WorkspaceCanvas.IconParams(centerX, centerY, cols, rows, fit))
                    }
                }
            }
            widgets = boundWidgets

            // Taps read the live canvas size like drops do, so a padding
            // change after bind can't skew where a tap-to-place lands.
            fun placement(rawX: Float, rawY: Float): FloatArray =
                workspacePlacement(canvas, state.prefs, rawX, rawY)

            var lastTouchX = 0f
            var lastTouchY = 0f
            val touchSlop = android.view.ViewConfiguration.get(canvas.context).scaledTouchSlop
            canvas.setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        lastTouchX = event.x
                        lastTouchY = event.y
                    }
                    // A press that drifts is a swipe, not a long-press. A
                    // View's long-press timer is only cancelled by the finger
                    // LEAVING the view, so without this a slow swipe across
                    // empty space opened edit mode halfway through - which
                    // then swallowed the lift the swipe was waiting for.
                    // Launcher3 cancels on slop the same way.
                    android.view.MotionEvent.ACTION_MOVE ->
                        if (hypot(event.x - lastTouchX, event.y - lastTouchY) > touchSlop) {
                            view.cancelLongPress()
                        }
                }
                false
            }
            canvas.setOnClickListener {
                val p = placement(lastTouchX, lastTouchY)
                host.onCanvasTap(page, p[0].toInt(), p[1].toInt(), p[2], p[3])
            }

            canvas.setOnLongClickListener {
                host.onCanvasLongPress()
                true
            }
            // Drops arrive through the activity's drag layer, which finds the
            // page under the finger itself; the page tags itself so it can.
            canvas.setTag(R.id.tag_page, page)
            host.onPageBound(canvas)
        }
    }

    private companion object {
        const val MIN_WIDGET_SPAN = 0.5f
        const val TYPE_PAGE = 0
        const val TYPE_ADD_PAGE = 1
    }
}
