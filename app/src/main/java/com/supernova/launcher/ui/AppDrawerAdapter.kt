package com.supernova.launcher.ui

import androidx.core.content.withStyledAttributes
import androidx.core.view.doOnAttach
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.supernova.launcher.R
import com.supernova.launcher.data.AppCatalog
import com.supernova.launcher.data.DrawerGroup
import com.supernova.launcher.data.IconThread
import com.supernova.launcher.data.LaunchableApp
import com.supernova.launcher.databinding.ItemDrawerAppBinding
import com.supernova.launcher.databinding.ItemDrawerAppRowBinding
import kotlin.math.roundToInt

class AppDrawerAdapter(
    private val appCatalog: AppCatalog,
    private val decorateIcon: (Drawable?, LaunchableApp) -> Drawable?,
    private val decorateFolderIcon: (Drawable, FolderEntry) -> Drawable = { icon, _ -> icon },
    private val onClick: (View, LaunchableApp) -> Unit,
    private val onLongPress: (View, LaunchableApp) -> Unit,
    private val onWebSearch: () -> Unit,
    // Tap on one of the "Search with" marks shown when nothing matched.
    private val onChip: (com.supernova.launcher.search.SearchChip, String) -> Unit = { _, _ -> },
    private val onFolderClick: (View, FolderEntry) -> Unit = { _, _ -> },
    // A folder long-pressed: its menu, and a drag that puts a copy on the
    // home screen.
    private val onFolderLongPress: (View, FolderEntry) -> Unit = { _, _ -> }
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    enum class DisplayMode { TILE, ROW }

    // A FOLDER-type drawer group rendered as one grid entry. The icon is a
    // FolderIconDrawable built by the host at submit time (never per frame).
    class FolderEntry(
        val group: DrawerGroup,
        val icon: Drawable,
        val members: List<LaunchableApp>
    ) {
        val key: String = "folder/${group.id}"
        val lowerLabel: String = group.name.lowercase()
        val section: String by lazy { LabelSections.of(group.name) }
    }

    init {
        setHasStableIds(true)
    }

    private class Entry(val app: LaunchableApp) {
        val key: String = "${app.userSerial}/${app.packageName}/${app.className}"
        val lowerLabel: String = app.label.lowercase()
        val section: String by lazy { LabelSections.of(app.label) }
    }

    private val stableIds = mutableMapOf<String, Long>()

    override fun getItemId(position: Int): Long = when {
        showWebRow() -> WEB_ROW_ID - position
        // An empty tab's one row stands in for the empty list.
        showEmpty() -> EMPTY_ROW_ID
        else -> stableIds.getOrPut(keyAt(position)) { stableIds.size.toLong() }
    }

    private fun keyAt(position: Int): String = when (val item = displayItems[position]) {
        is Entry -> item.key
        is FolderEntry -> item.key
        HeaderItem -> HEADER_KEY
        PrivateHeaderItem -> PRIVATE_HEADER_KEY
        else -> position.toString()
    }

    // The list's first, full-width item while it is not filtering: a view
    // the drawer owns (the recently-opened row and its hairline), so it
    // scrolls away with the apps instead of sitting fixed above them.
    private object HeaderItem

    private var headerView: View? = null

    fun setHeader(view: View?) {
        if (headerView === view) return
        headerView = view
        applyFilter()
    }

    // The private space's section at the end of the list, while the drawer
    // has no tabs to give it one of its own: a full-width header the drawer
    // owns (its lock and buttons), then the space's apps, which the list's
    // name order and fast scroller leave out. Taken in on the next submit.
    private object PrivateHeaderItem

    private var privateHeader: View? = null
    private val privateEntries = mutableListOf<Entry>()

    fun setPrivateSection(header: View?, apps: List<LaunchableApp>) {
        privateHeader = header
        privateEntries.clear()
        if (header != null) apps.mapTo(privateEntries, ::Entry)
    }

    // Where the section's header sits in the list; -1 without one.
    fun privateSectionPosition(): Int = displayItems.indexOf(PrivateHeaderItem)

    private val allEntries = mutableListOf<Entry>()
    private val folderEntries = mutableListOf<FolderEntry>()
    private var foldersFirst = true
    private var order = Order.A_Z

    // How the apps arrive: by label either way, or by something else
    // (install date, launches) where a folder has no place by name.
    enum class Order { A_Z, Z_A, UNSORTED }
    // What actually renders: FolderEntry and Entry rows interleaved.
    private val displayItems = mutableListOf<Any>()
    private var query = ""
    private var iconScale = 1f
    private var showLabels = true
    private var labelPrefs: com.supernova.launcher.data.LauncherPrefs? = null

    var displayMode: DisplayMode = DisplayMode.TILE
    // Exact tile width for the paged style; 0 = natural width.
    var cellWidthPx: Int = 0
    // The width a tile gets in the grid, one column's, so a big icon's box
    // can be kept inside it; 0 = unknown, and the box goes unchecked. The
    // controller sets it with the column count, before the rebind that
    // follows.
    var tileWidthPx: Int = 0
    // A grid row's height when the drawer's Rows share out its height (the
    // vertical grid with Rows set); 0 = the tile's own height.
    var tileHeightPx: Int = 0
    // What a tile holds without its padding (measureTile): icon, gap and
    // label as the labels wrap, and with the label on one line. A row
    // shorter than that fits the tile into it (DrawerTileFit); 0 = not
    // measured.
    var tileContentPx: Int = 0
    var tileCompactPx: Int = 0

    // A tile's content height at the current icon size and label style
    // (tileContentPx, then tileCompactPx), taken from the app whose label
    // runs longest, the likeliest to wrap; null with no apps or no column
    // width yet. Measured without the tile's padding and its minimum
    // height: a tile whose icon the column's width shrank is shorter than
    // that minimum, and a row only has to hold what the tile draws.
    fun measureTile(parent: ViewGroup): IntArray? {
        val longest = allEntries.filterNot { it.app.hideLabel }.maxByOrNull { it.app.label.length }
            ?: allEntries.firstOrNull()
        val width = tileWidthPx
        if (longest == null || width <= 0) return null
        val binding = ItemDrawerAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        val holder = TileHolder(binding)
        val fixed = tileHeightPx
        tileHeightPx = 0
        holder.bind(longest.app)
        tileHeightPx = fixed
        val root = binding.root
        root.minimumHeight = 0
        root.setPadding(root.paddingLeft, 0, root.paddingRight, 0)
        fun measured(): Int {
            root.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            return root.measuredHeight
        }
        val content = measured()
        LabelStyler.capLines(binding.appLabel, 1)
        return intArrayOf(content, measured())
    }

    // How a grid tile with an `iconPx` icon box sits in its row now.
    private fun tileFit(density: Float, iconPx: Int): DrawerTileFit = DrawerTileFit.of(
        tileHeightPx,
        (TILE_PADDING_V_DP * density).toInt(),
        tileContentPx,
        tileCompactPx,
        iconPx
    )

    // A grid tile as the drawer draws it, for the search window's tiles,
    // which line up with the drawer's grid: the row, the icon box the
    // column leaves it and the fit. Null before the drawer has measured a
    // tile, or while its rows are the tiles' own height.
    class TileLook(val rowPx: Int, val iconPx: Int, val fit: DrawerTileFit)

    fun tileLook(context: android.content.Context): TileLook? {
        if (tileHeightPx <= 0 || tileContentPx <= 0) return null
        val density = context.resources.displayMetrics.density
        val side = (TILE_PADDING_H_DP * density).toInt()
        val box = iconBoxPx((TILE_ICON_DP * density).roundToInt(), side, side, keepInsideTile = true)
        return TileLook(tileHeightPx, box, tileFit(density, box))
    }

    // A grid's spare tiles: RecyclerView keeps five of a kind by default, and
    // a fling through a grid of `columns` brings a whole row of them in at
    // once, so every row past the fifth tile inflated new ones mid-fling.
    // Enough here for a screenful, so a new list (a tab, a search) takes
    // the old one's tiles instead of inflating its own.
    fun sizePool(pool: RecyclerView.RecycledViewPool, columns: Int) {
        val spare = (columns * POOL_ROWS).coerceAtLeast(DEFAULT_POOL)
        pool.setMaxRecycledViews(TYPE_TILE, spare)
        pool.setMaxRecycledViews(TYPE_FOLDER_TILE, columns.coerceAtLeast(DEFAULT_POOL))
    }

    // Inflates a pool's worth of spare tiles while the main thread is idle,
    // one tile at a time. The list's first fling otherwise inflated every
    // row it brought in, five tiles at 2-4ms each, and dropped frames all
    // the way down; and the first open after the process started inflated a
    // whole screenful on the tap (#277).
    fun prefillPool(list: RecyclerView, columns: Int) {
        val pool = list.recycledViewPool
        val target = columns * POOL_ROWS
        list.doOnAttach {
            android.os.Looper.myQueue().addIdleHandler {
                val more = list.isAttachedToWindow && pool.getRecycledViewCount(TYPE_TILE) < target
                if (more) pool.putRecycledView(createViewHolder(list, TYPE_TILE))
                more && pool.getRecycledViewCount(TYPE_TILE) < target
            }
        }
    }

    // One spare tile into `list`'s pool, inflated now, unless it already
    // holds `target`. True while it still holds fewer. The paged drawer
    // stocks its spare pages this way, a tile per idle moment (#340). A
    // full pool (sizePool's cap) ends it too.
    fun stockTile(list: RecyclerView, target: Int): Boolean {
        val pool = list.recycledViewPool
        val had = pool.getRecycledViewCount(TYPE_TILE)
        if (had >= target) return false
        pool.putRecycledView(createViewHolder(list, TYPE_TILE))
        val has = pool.getRecycledViewCount(TYPE_TILE)
        return has > had && has < target
    }

    // True when something a tile draws changed.
    fun setDisplay(
        iconScale: Float,
        showLabels: Boolean,
        prefs: com.supernova.launcher.data.LauncherPrefs? = null
    ): Boolean {
        val labelStyleChanged = prefs != null && (
            labelPrefs?.labelSize != prefs.labelSize ||
                labelPrefs?.labelColor != prefs.labelColor ||
                labelPrefs?.labelShadow != prefs.labelShadow ||
                labelPrefs?.labelFont != prefs.labelFont ||
                labelPrefs?.labelSingleLine != prefs.labelSingleLine ||
                labelPrefs?.let { LabelStyler.evenRows(LabelContext.DRAWER, it) } !=
                LabelStyler.evenRows(LabelContext.DRAWER, prefs) ||
                labelPrefs?.labelConfig != prefs.labelConfig ||
                // Unset, the label color is the drawer surface's text color.
                labelPrefs?.styleConfig != prefs.styleConfig ||
                labelPrefs?.drawerBgColor != prefs.drawerBgColor ||
                labelPrefs?.paletteSource != prefs.paletteSource ||
                labelPrefs?.paletteSeed != prefs.paletteSeed
            )
        // Drawer icons carry badges too - the activity hands the adapter a
        // decorateIcon that applies them - so a badge setting changing has to
        // rebind here, exactly like a label setting. Nothing did this before:
        // the badge collector's rebuild-on-every-onStart happened to cover it.
        val badgeStyleChanged = prefs != null && (
            labelPrefs?.badges != prefs.badges ||
                labelPrefs?.badgeStyle != prefs.badgeStyle ||
                labelPrefs?.badgeCounts != prefs.badgeCounts ||
                labelPrefs?.badgeApps != prefs.badgeApps ||
                labelPrefs?.badgeColor != prefs.badgeColor ||
                labelPrefs?.badgePosition != prefs.badgePosition ||
                labelPrefs?.badgeScale != prefs.badgeScale ||
                labelPrefs?.badgeTextColor != prefs.badgeTextColor ||
                labelPrefs?.badgeCornerRadius != prefs.badgeCornerRadius ||
                labelPrefs?.badgeOutline != prefs.badgeOutline ||
                labelPrefs?.badgeOnCorner != prefs.badgeOnCorner ||
                labelPrefs?.folderBadges != prefs.folderBadges
            )
        if (this.iconScale == iconScale && this.showLabels == showLabels &&
            !labelStyleChanged && !badgeStyleChanged
        ) {
            return false
        }
        this.iconScale = iconScale
        this.showLabels = showLabels
        if (prefs != null) labelPrefs = prefs
        if (displayItems.isNotEmpty()) notifyDataSetChanged()
        return true
    }

    fun submit(
        apps: List<LaunchableApp>,
        folders: List<FolderEntry> = emptyList(),
        foldersFirst: Boolean = true,
        order: Order = Order.A_Z,
        // Another tab's list: a diff against the old one finds nothing to
        // keep and only costs time (#278).
        replace: Boolean = false
    ) {
        allEntries.clear()
        apps.mapTo(allEntries, ::Entry)
        folderEntries.clear()
        folderEntries.addAll(folders)
        this.foldersFirst = foldersFirst
        this.order = order
        applyFilter(replace)
    }

    // The flat, filtered list the paged style cuts its pages from.
    fun items(): List<Any> = displayItems.filter { it !== HeaderItem }

    // Paged mode: a page is an exact slice of items(), already ordered and
    // filtered, so it bypasses submit()/applyFilter() entirely.
    fun submitPage(items: List<Any>) {
        displayItems.clear()
        displayItems.addAll(items)
        notifyDataSetChanged()
    }

    fun filter(query: String) {
        this.query = query.trim().lowercase()
        applyFilter()
    }

    private fun applyFilter(replace: Boolean = false) {
        val oldItems = displayItems.toList()
        displayItems.clear()
        if (query.isBlank()) {
            when {
                folderEntries.isEmpty() -> displayItems.addAll(allEntries)
                foldersFirst -> {
                    displayItems.addAll(folderEntries)
                    displayItems.addAll(allEntries)
                }
                // Not first, and no name order to put them in: after the apps.
                order == Order.UNSORTED -> {
                    displayItems.addAll(allEntries)
                    displayItems.addAll(folderEntries)
                }
                else -> displayItems.addAll(
                    mergeByLabel(allEntries, folderEntries, order == Order.Z_A, ::labelOf)
                )
            }
        } else {
            // Search shows apps only; folder contents are reachable by name.
            displayItems.addAll(com.supernova.launcher.search.AppMatcher.filter(allEntries, query) { it.lowerLabel })
        }
        if (query.isBlank() && headerView != null && displayItems.isNotEmpty()) {
            displayItems.add(0, HeaderItem)
        }
        if (query.isBlank() && privateHeader != null) {
            displayItems.add(PrivateHeaderItem)
            displayItems.addAll(privateEntries)
        }
        // Diff so a keystroke rebinds only the rows that changed. An empty
        // list shows rows of its own instead (the web rows, or an empty tab's
        // message), which the diff doesn't see; fall back to a full refresh
        // there, which also rebinds an empty tab's action for the new tab.
        if (replace || oldItems.isEmpty() || displayItems.isEmpty()) {
            notifyDataSetChanged()
            return
        }
        val newItems = displayItems
        androidx.recyclerview.widget.DiffUtil.calculateDiff(
            object : androidx.recyclerview.widget.DiffUtil.Callback() {
                override fun getOldListSize(): Int = oldItems.size
                override fun getNewListSize(): Int = newItems.size
                override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean =
                    keyOf(oldItems[oldPos]) == keyOf(newItems[newPos])

                override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
                    val old = oldItems[oldPos]
                    val new = newItems[newPos]
                    // Folder rows always rebind: their composed icon is a
                    // fresh drawable each submit and rebinding is cheap.
                    if (old is FolderEntry || new is FolderEntry) return false
                    return (old as? Entry)?.app == (new as? Entry)?.app
                }
            }
        ).dispatchUpdatesTo(this)
    }

    private fun keyOf(item: Any): String = when (item) {
        is Entry -> item.key
        is FolderEntry -> item.key
        HeaderItem -> HEADER_KEY
        PrivateHeaderItem -> PRIVATE_HEADER_KEY
        else -> ""
    }

    fun firstVisibleApp(): LaunchableApp? =
        displayItems.firstNotNullOfOrNull { (it as? Entry)?.app }

    // The fast scrollbar's letters: where each one starts in the name-sorted
    // run. Folders shown first sit outside the name order, so they don't
    // count - a folder called Travel at the top would otherwise open T there.
    // None for a list not sorted by name.
    internal fun fastScrollSections(): List<FastScrollMath.Section> {
        if (order == Order.UNSORTED) return emptyList()
        val privateStart = privateSectionPosition().takeIf { it >= 0 } ?: displayItems.size
        val initials = displayItems.withIndex()
            .filter { (index, _) -> index < privateStart }
            .filter { (_, item) -> item is Entry || (item is FolderEntry && !foldersFirst) }
            .map { (index, item) -> index to sectionOf(item) }
        return FastScrollMath.sections(initials)
    }

    private fun sectionOf(item: Any): String = when (item) {
        is Entry -> item.section
        is FolderEntry -> item.section
        else -> "#"
    }

    private fun labelOf(item: Any): String = when (item) {
        is Entry -> item.lowerLabel
        is FolderEntry -> item.lowerLabel
        else -> ""
    }

    private fun showWebRow(): Boolean = query.isNotBlank() && displayItems.isEmpty()

    // Nothing matched: the rows the search window ends every query with. A
    // web engine other than Google (the only one with a mark) keeps its own
    // row above the "Search with" header and the provider marks; with every
    // provider off the engine row stands alone.
    private fun footerTypes(): List<Int> {
        val prefs = labelPrefs
        val marks = SearchMarks.hasProviders(prefs)
        val engine = prefs?.searchWebProvider ?: com.supernova.launcher.search.WebProvider.GOOGLE
        return buildList {
            if (!marks || engine != com.supernova.launcher.search.WebProvider.GOOGLE) add(TYPE_WEB_ROW)
            if (marks) {
                add(TYPE_MARKS_HEADER)
                add(TYPE_WEB)
            }
        }
    }

    // A tab with nothing in it: what to say, and the way to fill it. Null
    // leaves an empty list empty (the All tab, a search).
    // messageColor: the drawer's own text colour, null for the theme's.
    class EmptyState(
        val message: CharSequence,
        val actionLabel: CharSequence,
        val messageColor: Int? = null,
        val onAction: () -> Unit
    )

    var emptyState: EmptyState? = null

    private fun showEmpty(): Boolean = query.isBlank() && displayItems.isEmpty() && emptyState != null

    override fun getItemCount(): Int = when {
        showWebRow() -> footerTypes().size
        showEmpty() -> 1
        else -> displayItems.size
    }

    // Both empty-state rows run the width of the grid; everything else is one
    // cell. The layout manager asks through this.
    fun spanSizeAt(position: Int, spanCount: Int): Int =
        if (showWebRow() || showEmpty() || isHeaderAt(position)) spanCount else 1

    private fun isHeaderAt(position: Int): Boolean =
        displayItems.getOrNull(position).let { it === HeaderItem || it === PrivateHeaderItem }

    override fun getItemViewType(position: Int): Int = when {
        showWebRow() -> footerTypes()[position]
        showEmpty() -> TYPE_EMPTY
        displayItems[position] === HeaderItem -> TYPE_LIST_HEADER
        displayItems[position] === PrivateHeaderItem -> TYPE_PRIVATE_HEADER
        displayItems[position] is FolderEntry ->
            if (displayMode == DisplayMode.ROW) TYPE_FOLDER_ROW else TYPE_FOLDER_TILE
        displayMode == DisplayMode.ROW -> TYPE_ROW
        else -> TYPE_TILE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_ROW -> RowHolder(ItemDrawerAppRowBinding.inflate(inflater, parent, false))
            TYPE_MARKS_HEADER -> HeaderHolder(
                android.widget.TextView(parent.context).apply {
                    setText(R.string.search_with)
                    setTextColor(parent.context.getColor(R.color.textSecondary))
                    val pad = (16 * resources.displayMetrics.density).toInt()
                    setPadding(pad, pad, pad, pad / 2)
                    layoutParams = RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
            )
            TYPE_WEB -> MarksHolder(FrequentAppsRowView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            })
            TYPE_WEB_ROW -> WebRowHolder(
                com.supernova.launcher.databinding.ItemSearchRowBinding.inflate(inflater, parent, false)
            )
            TYPE_LIST_HEADER, TYPE_PRIVATE_HEADER -> ListHeaderHolder(
                android.widget.FrameLayout(parent.context).apply {
                    layoutParams = RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
            )
            TYPE_EMPTY -> EmptyHolder(parent.context)
            TYPE_FOLDER_TILE -> FolderTileHolder(ItemDrawerAppBinding.inflate(inflater, parent, false))
            TYPE_FOLDER_ROW -> FolderRowHolder(ItemDrawerAppRowBinding.inflate(inflater, parent, false))
            else -> TileHolder(ItemDrawerAppBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is TileHolder -> holder.bind((displayItems[position] as Entry).app)
            is RowHolder -> holder.bind((displayItems[position] as Entry).app)
            is FolderTileHolder -> holder.bind(displayItems[position] as FolderEntry)
            is FolderRowHolder -> holder.bind(displayItems[position] as FolderEntry)
            is MarksHolder -> holder.bind()
            is WebRowHolder -> holder.bind()
            is HeaderHolder -> Unit
            is ListHeaderHolder -> holder.bind(
                if (displayItems[position] === PrivateHeaderItem) privateHeader else headerView
            )
            is EmptyHolder -> emptyState?.let(holder::bind)
        }
    }

    // The icon scale rides in the icon's box rather than in scaleX/scaleY,
    // as it does on the desktop and in the dock: a scaled icon drew out past
    // its box, where the tile's padding cut it square and the label, drawn
    // after it, covered its bottom. A sized box makes the tile measure what
    // it draws, so the label sits below the icon at any size. A tile's box
    // also stays inside the tile, as a dock icon's stays inside its slot, so
    // a grid set denser than its icons fit shrinks them instead of clipping
    // them. basePx is the box the layout declares.
    private fun sizeIcon(root: View, icon: View, basePx: Int, keepInsideTile: Boolean) {
        val box = iconBoxPx(basePx, root.paddingLeft, root.paddingRight, keepInsideTile)
        val params = icon.layoutParams
        if (params.width == box && params.height == box) return
        params.width = box
        params.height = box
        icon.layoutParams = params
    }

    private fun iconBoxPx(basePx: Int, paddingLeft: Int, paddingRight: Int, keepInsideTile: Boolean): Int {
        val box = (basePx * iconScale).toInt()
        val room = tileWidthPx - paddingLeft - paddingRight
        return if (keepInsideTile && tileWidthPx > 0 && room > 0) box.coerceAtMost(room) else box
    }

    private fun applyTileWidth(root: View) {
        if (cellWidthPx > 0 && root.layoutParams.width != cellWidthPx) {
            root.layoutParams = root.layoutParams.apply { width = cellWidthPx }
        } else if (cellWidthPx == 0 && root.layoutParams.width != ViewGroup.LayoutParams.MATCH_PARENT) {
            root.layoutParams = root.layoutParams.apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
            }
        }
    }

    // A row taller than its tile keeps the icon and label in its middle; a
    // shorter one fits the tile into it (DrawerTileFit). Runs after the
    // label is styled.
    private fun applyTileFit(tile: ItemDrawerAppBinding, baseIconPx: Int) {
        val root = tile.root
        val box = iconBoxPx(baseIconPx, root.paddingLeft, root.paddingRight, keepInsideTile = true)
        DrawerTileFit.apply(tile, tileHeightPx, box, tileFit(root.resources.displayMetrics.density, box))
    }

    // Hosts the drawer's header view; there is one such view, so a rebind
    // (or a second holder) simply takes it over.
    private class ListHeaderHolder(private val container: android.widget.FrameLayout) :
        RecyclerView.ViewHolder(container) {
        fun bind(view: View?) {
            if (view == null || view.parent === container) return
            (view.parent as? ViewGroup)?.removeView(view)
            container.removeAllViews()
            container.addView(view)
        }
    }

    // A bind never renders an icon: a cached one shows at once, a missing one
    // renders on the icon thread and lands when ready. Rendering on the bind
    // stalled a drawer opened soon after the launcher's process started -
    // overnight, say - for every visible tile's icon, seconds on a dense
    // grid (#277). Until then the view keeps the app's previous icon (an
    // icon setting changed) or shows none (recycled from another app).
    private fun bindIcon(view: android.widget.ImageView, app: LaunchableApp) {
        val key = "${app.userSerial}/${app.packageName}/${app.className}"
        val cached = appCatalog.cachedIcon(app)
        if (cached != null) {
            view.setTag(R.id.tag_icon_app, key)
            view.setImageDrawable(decorateIcon(cached, app))
            return
        }
        if (view.getTag(R.id.tag_icon_app) != key) view.setImageDrawable(null)
        view.setTag(R.id.tag_icon_app, key)
        IconThread.render(appCatalog, app) { icon ->
            // The cache wins over a render that an icon change has since
            // evicted and redrawn.
            if (view.getTag(R.id.tag_icon_app) == key) {
                view.setImageDrawable(decorateIcon(appCatalog.cachedIcon(app) ?: icon, app))
            }
        }
    }

    inner class TileHolder(private val binding: ItemDrawerAppBinding) : RecyclerView.ViewHolder(binding.root) {
        private val baseIconPx = binding.appIcon.layoutParams.width

        fun bind(app: LaunchableApp) {
            applyTileWidth(binding.root)
            binding.appLabel.text = app.label
            labelPrefs?.let { LabelStyler.apply(binding.appLabel, it, onWallpaper = false, context = LabelContext.DRAWER) }
            binding.appLabel.visibility = if (showLabels && !app.hideLabel) View.VISIBLE else View.GONE
            binding.root.contentDescription = app.label
            binding.appIcon.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            applyTileFit(binding, baseIconPx)
            bindIcon(binding.appIcon, app)
            binding.root.setOnClickListener { onClick(it, app) }
            binding.root.setOnLongClickListener {
                onLongPress(it, app)
                true
            }
        }
    }

    inner class RowHolder(private val binding: ItemDrawerAppRowBinding) : RecyclerView.ViewHolder(binding.root) {
        private val baseIconPx = binding.appIcon.layoutParams.width

        fun bind(app: LaunchableApp) {
            binding.appLabel.text = app.label
            labelPrefs?.let {
                LabelStyler.apply(
                    binding.appLabel, it, onWallpaper = false, context = LabelContext.DRAWER, inGrid = false
                )
            }
            binding.appIcon.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            sizeIcon(binding.root, binding.appIcon, baseIconPx, keepInsideTile = false)
            bindIcon(binding.appIcon, app)
            binding.root.setOnClickListener { onClick(it, app) }
            binding.root.setOnLongClickListener {
                onLongPress(it, app)
                true
            }
        }
    }

    inner class FolderTileHolder(private val binding: ItemDrawerAppBinding) : RecyclerView.ViewHolder(binding.root) {
        private val baseIconPx = binding.appIcon.layoutParams.width

        fun bind(folder: FolderEntry) {
            applyTileWidth(binding.root)
            binding.appLabel.text = folder.group.name
            labelPrefs?.let { LabelStyler.apply(binding.appLabel, it, onWallpaper = false, context = LabelContext.DRAWER) }
            binding.appLabel.visibility = if (showLabels) View.VISIBLE else View.GONE
            // Announce like workspace folders ("Folder: name"), not as a bare app label.
            binding.root.contentDescription =
                binding.root.context.getString(R.string.folder_desc, folder.group.name)
            binding.appIcon.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            applyTileFit(binding, baseIconPx)
            binding.appIcon.setImageDrawable(decorateFolderIcon(folder.icon, folder))
            binding.root.setOnClickListener { onFolderClick(it, folder) }
            binding.root.setOnLongClickListener {
                onFolderLongPress(it, folder)
                true
            }
        }
    }

    inner class FolderRowHolder(private val binding: ItemDrawerAppRowBinding) : RecyclerView.ViewHolder(binding.root) {
        private val baseIconPx = binding.appIcon.layoutParams.width

        fun bind(folder: FolderEntry) {
            binding.appLabel.text = folder.group.name
            labelPrefs?.let {
                LabelStyler.apply(
                    binding.appLabel, it, onWallpaper = false, context = LabelContext.DRAWER, inGrid = false
                )
            }
            binding.appIcon.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            sizeIcon(binding.root, binding.appIcon, baseIconPx, keepInsideTile = false)
            binding.appIcon.setImageDrawable(decorateFolderIcon(folder.icon, folder))
            binding.root.setOnClickListener { onFolderClick(it, folder) }
            binding.root.setOnLongClickListener {
                onFolderLongPress(it, folder)
                true
            }
        }
    }

    class HeaderHolder(view: View) : RecyclerView.ViewHolder(view)

    // The empty tab's line and its button, centred in the list.
    class EmptyHolder(context: android.content.Context) : RecyclerView.ViewHolder(
        android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            val pad = (EMPTY_PAD_DP * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(android.widget.TextView(context).apply {
                setTextColor(context.getColor(R.color.textSecondary))
                gravity = android.view.Gravity.CENTER
            })
            // A text button in the accent, as dialogs' are.
            addView(android.widget.TextView(context).apply {
                setTextColor(com.supernova.launcher.ui.theme.CustomAccent.of(this))
                textSize = EMPTY_BUTTON_SP
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                gravity = android.view.Gravity.CENTER
                minHeight = (EMPTY_BUTTON_MIN_DP * resources.displayMetrics.density).toInt()
                val side = (EMPTY_BUTTON_PAD_DP * resources.displayMetrics.density).toInt()
                setPadding(side, 0, side, 0)
                context.withStyledAttributes(attrs = intArrayOf(android.R.attr.selectableItemBackground)) {
                    background = getDrawable(0)
                }
                isClickable = true
            })
        }
    ) {
        fun bind(state: EmptyState) {
            val box = itemView as android.widget.LinearLayout
            (box.getChildAt(0) as android.widget.TextView).apply {
                text = state.message
                setTextColor(state.messageColor ?: context.getColor(R.color.textSecondary))
            }
            (box.getChildAt(1) as android.widget.TextView).apply {
                text = state.actionLabel
                setOnClickListener { state.onAction() }
            }
        }
    }

    // "Search <engine> for “query”", as the search window words it.
    inner class WebRowHolder(
        private val binding: com.supernova.launcher.databinding.ItemSearchRowBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind() {
            val context = binding.root.context
            val engine = com.supernova.launcher.search.WebEngine.of(labelPrefs)
            val color = context.getColor(R.color.textPrimary)
            binding.resultIcon.setImageResource(R.drawable.ic_search)
            androidx.core.widget.ImageViewCompat.setImageTintList(
                binding.resultIcon, android.content.res.ColorStateList.valueOf(color)
            )
            binding.resultLabel.text = context.getString(R.string.search_web_for, engine.name, query.trim())
            binding.resultLabel.setTextColor(color)
            binding.root.setOnClickListener { onWebSearch() }
        }
    }

    inner class MarksHolder(
        private val view: FrequentAppsRowView
    ) : RecyclerView.ViewHolder(view) {
        fun bind() {
            view.bind(
                SearchMarks.entries(view.context, labelPrefs, renderer(view.context), accent(view.context)) { chip ->
                    onChip(chip, query)
                },
                SearchMarks.MARK_DP
            )
        }
    }

    private var iconRenderer: com.supernova.launcher.data.IconRenderer? = null

    private fun renderer(context: android.content.Context): com.supernova.launcher.data.IconRenderer =
        iconRenderer ?: com.supernova.launcher.data.IconRenderer(context).also { iconRenderer = it }

    private fun accent(context: android.content.Context): Int = labelPrefs?.let { prefs ->
        com.supernova.launcher.ui.theme.Styles.resolve(context, prefs)
            .of(com.supernova.launcher.ui.theme.SurfaceId.DRAWER).accent
    } ?: 0

    companion object {
        // item_drawer_app's paddingVertical and paddingHorizontal.
        const val TILE_PADDING_V_DP = 12
        const val TILE_PADDING_H_DP = 4
        // item_drawer_app's icon box.
        const val TILE_ICON_DP = 48
        private const val TYPE_TILE = 0
        private const val TYPE_ROW = 1
        private const val TYPE_WEB = 2
        private const val TYPE_FOLDER_TILE = 3
        private const val TYPE_FOLDER_ROW = 4
        private const val TYPE_MARKS_HEADER = 5
        private const val TYPE_LIST_HEADER = 6
        private const val TYPE_EMPTY = 7
        private const val TYPE_WEB_ROW = 8
        private const val TYPE_PRIVATE_HEADER = 9
        private const val EMPTY_PAD_DP = 32
        private const val EMPTY_BUTTON_SP = 16f
        private const val EMPTY_BUTTON_MIN_DP = 48
        private const val EMPTY_BUTTON_PAD_DP = 16
        private const val HEADER_KEY = "list-header"
        private const val PRIVATE_HEADER_KEY = "private-header"
        // Rows of spare tiles a grid keeps, and RecyclerView's own count.
        private const val POOL_ROWS = 8
        private const val DEFAULT_POOL = 5
        private const val WEB_ROW_ID = -2L
        private const val EMPTY_ROW_ID = -1L

        // Folders merged in among the apps by name, in the list's direction
        // and the apps' own alphabet (LabelOrder). The folders come in the
        // groups' own order (the one Move up/down sets), so they are sorted
        // here; a single sweep then interleaves them.
        fun <T> mergeByLabel(apps: List<T>, folders: List<T>, descending: Boolean, label: (T) -> String): List<T> {
            val byName = com.supernova.launcher.data.LabelOrder.by(label = label)
            val order = if (descending) byName.reversed() else byName
            val sortedFolders = folders.sortedWith(order)
            val merged = ArrayList<T>(apps.size + folders.size)
            var folderIndex = 0
            apps.forEach { app ->
                while (folderIndex < sortedFolders.size && order.compare(sortedFolders[folderIndex], app) <= 0) {
                    merged.add(sortedFolders[folderIndex++])
                }
                merged.add(app)
            }
            while (folderIndex < sortedFolders.size) merged.add(sortedFolders[folderIndex++])
            return merged
        }
    }
}
