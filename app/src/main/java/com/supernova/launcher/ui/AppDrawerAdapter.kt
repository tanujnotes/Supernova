package com.supernova.launcher.ui

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.supernova.launcher.R
import com.supernova.launcher.data.AppCatalog
import com.supernova.launcher.data.DrawerGroup
import com.supernova.launcher.data.LaunchableApp
import com.supernova.launcher.databinding.ItemDrawerAppBinding
import com.supernova.launcher.databinding.ItemDrawerAppRowBinding

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
        val key: String = "folder/${group.name}"
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

    override fun getItemId(position: Int): Long {
        if (showWebRow()) return WEB_ROW_ID - position
        return stableIds.getOrPut(keyAt(position)) { stableIds.size.toLong() }
    }

    private fun keyAt(position: Int): String = when (val item = displayItems[position]) {
        is Entry -> item.key
        is FolderEntry -> item.key
        HeaderItem -> HEADER_KEY
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

    fun setDisplay(iconScale: Float, showLabels: Boolean, prefs: com.supernova.launcher.data.LauncherPrefs? = null) {
        val labelStyleChanged = prefs != null && (
            labelPrefs?.labelSize != prefs.labelSize ||
                labelPrefs?.labelColor != prefs.labelColor ||
                labelPrefs?.labelShadow != prefs.labelShadow ||
                labelPrefs?.labelFont != prefs.labelFont ||
                labelPrefs?.labelSingleLine != prefs.labelSingleLine ||
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
                labelPrefs?.folderBadges != prefs.folderBadges
            )
        if (this.iconScale == iconScale && this.showLabels == showLabels &&
            !labelStyleChanged && !badgeStyleChanged
        ) {
            return
        }
        this.iconScale = iconScale
        this.showLabels = showLabels
        if (prefs != null) labelPrefs = prefs
        if (displayItems.isNotEmpty()) notifyDataSetChanged()
    }

    fun submit(
        apps: List<LaunchableApp>,
        folders: List<FolderEntry> = emptyList(),
        foldersFirst: Boolean = true,
        order: Order = Order.A_Z
    ) {
        allEntries.clear()
        apps.mapTo(allEntries, ::Entry)
        folderEntries.clear()
        folderEntries.addAll(folders)
        this.foldersFirst = foldersFirst
        this.order = order
        applyFilter()
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

    private fun applyFilter() {
        val oldItems = displayItems.toList()
        val oldWebRow = showWebRow()
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
            displayItems.addAll(allEntries.filter { entry ->
                com.supernova.launcher.search.AppMatcher.matches(entry.lowerLabel, query)
            })
        }
        if (query.isBlank() && headerView != null && displayItems.isNotEmpty()) {
            displayItems.add(0, HeaderItem)
        }
        // Diff so a keystroke rebinds only the rows that changed. The web-row
        // sentinel changes the list shape; fall back to a full refresh there.
        if (oldWebRow || showWebRow()) {
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
        val initials = displayItems.withIndex()
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

    private fun showWebRow(): Boolean =
        query.isNotBlank() && displayItems.isEmpty() && SearchMarks.hasProviders(labelPrefs)

    // Empty state: "Search with" header + the provider marks, the same answer
    // the global overlay gives.
    override fun getItemCount(): Int = if (showWebRow()) 2 else displayItems.size

    // Both empty-state rows run the width of the grid; everything else is one
    // cell. The layout manager asks through this.
    fun spanSizeAt(position: Int, spanCount: Int): Int =
        if (showWebRow() || displayItems.getOrNull(position) === HeaderItem) spanCount else 1

    override fun getItemViewType(position: Int): Int = when {
        showWebRow() -> if (position == 0) TYPE_MARKS_HEADER else TYPE_WEB
        displayItems[position] === HeaderItem -> TYPE_LIST_HEADER
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
            TYPE_LIST_HEADER -> ListHeaderHolder(
                android.widget.FrameLayout(parent.context).apply {
                    layoutParams = RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
            )
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
            is HeaderHolder -> Unit
            is ListHeaderHolder -> holder.bind(headerView)
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
        var box = (basePx * iconScale).toInt()
        val room = tileWidthPx - root.paddingLeft - root.paddingRight
        if (keepInsideTile && tileWidthPx > 0 && room > 0) box = box.coerceAtMost(room)
        val params = icon.layoutParams
        if (params.width == box && params.height == box) return
        params.width = box
        params.height = box
        icon.layoutParams = params
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

    inner class TileHolder(private val binding: ItemDrawerAppBinding) : RecyclerView.ViewHolder(binding.root) {
        private val baseIconPx = binding.appIcon.layoutParams.width

        fun bind(app: LaunchableApp) {
            applyTileWidth(binding.root)
            binding.appLabel.text = app.label
            labelPrefs?.let { LabelStyler.apply(binding.appLabel, it, onWallpaper = false, context = LabelContext.DRAWER) }
            binding.appLabel.visibility = if (showLabels && !app.hideLabel) View.VISIBLE else View.GONE
            binding.root.contentDescription = app.label
            binding.appIcon.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            sizeIcon(binding.root, binding.appIcon, baseIconPx, keepInsideTile = true)
            binding.appIcon.setImageDrawable(decorateIcon(appCatalog.iconFor(app), app))
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
            labelPrefs?.let { LabelStyler.apply(binding.appLabel, it, onWallpaper = false, context = LabelContext.DRAWER) }
            binding.appIcon.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            sizeIcon(binding.root, binding.appIcon, baseIconPx, keepInsideTile = false)
            binding.appIcon.setImageDrawable(decorateIcon(appCatalog.iconFor(app), app))
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
            sizeIcon(binding.root, binding.appIcon, baseIconPx, keepInsideTile = true)
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
            labelPrefs?.let { LabelStyler.apply(binding.appLabel, it, onWallpaper = false, context = LabelContext.DRAWER) }
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
        private const val TYPE_TILE = 0
        private const val TYPE_ROW = 1
        private const val TYPE_WEB = 2
        private const val TYPE_FOLDER_TILE = 3
        private const val TYPE_FOLDER_ROW = 4
        private const val TYPE_MARKS_HEADER = 5
        private const val TYPE_LIST_HEADER = 6
        private const val HEADER_KEY = "list-header"
        private const val WEB_ROW_ID = -2L

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
