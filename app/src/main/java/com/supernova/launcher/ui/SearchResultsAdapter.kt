package com.supernova.launcher.ui

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.supernova.launcher.R
import com.supernova.launcher.data.AppCatalog
import com.supernova.launcher.data.LaunchableApp
import com.supernova.launcher.data.LauncherPrefs
import com.supernova.launcher.databinding.ItemDrawerAppBinding
import com.supernova.launcher.databinding.ItemSearchCalcBinding
import com.supernova.launcher.databinding.ItemSearchDividerBinding
import com.supernova.launcher.databinding.ItemSearchHeaderBinding
import com.supernova.launcher.databinding.ItemSearchRowBinding
import com.supernova.launcher.databinding.ItemSearchTopHitBinding
import com.supernova.launcher.search.SearchChip
import com.supernova.launcher.search.SearchResult

// The search window's list. Empty query: suggested-shortcut
// pills, the recently opened apps, a hairline, then the drawer's folders and
// every app as a grid. With a query: a calculation, the top hit as a card (its shortcuts as
// pills), the other app matches as grid tiles with the matched letters
// bold, then cards/folders/shortcuts/contacts rows, and always
// "Search with" and the provider marks at the end. Queries produce small
// lists, so every submit is a full rebind (the item animator is off).
class SearchResultsAdapter(
    private val appCatalog: AppCatalog,
    private val callbacks: Callbacks
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    class Callbacks(
        val onApp: (View, LaunchableApp) -> Unit,
        val onShortcut: (View, SearchResult.Shortcut) -> Unit,
        val onContact: (SearchResult.Contact) -> Unit,
        val onContactCall: (String) -> Unit,
        val onContactMessage: (String) -> Unit,
        val onChip: (SearchChip, String) -> Unit,
        val onWeb: (SearchResult.Web) -> Unit,
        val onCopyCalculation: (String) -> Unit,
        val onFrequentApp: (View, LaunchableApp) -> Unit,
        val onFolder: (SearchResult.Folder) -> Unit,
        // The top hit's gear.
        val onAppInfo: (LaunchableApp) -> Unit,
        // Long-presses on an app, an app shortcut, or a drawer folder: the
        // item's menu, and a drag to the home screen. The view is the one to
        // lift the picture from and to anchor the menu on.
        val onAppLongPress: (View, LaunchableApp) -> Unit = { _, _ -> },
        val onShortcutLongPress: (View, SearchResult.Shortcut) -> Unit = { _, _ -> },
        val onFolderLongPress: (View, SearchResult.Folder) -> Unit = { _, _ -> }
    )

    // The best match, shown as a card with the app's own shortcuts.
    class TopHit(val app: LaunchableApp, val shortcuts: List<SearchResult.Shortcut>)

    private sealed interface Row {
        data class Result(val result: SearchResult) : Row
        data class Header(@StringRes val title: Int) : Row
        data class Frequent(val apps: List<LaunchableApp>) : Row
        data class Shortcuts(val pills: List<SuggestedShortcuts.Pill>) : Row
        object Divider : Row
        // Tiles; each app carries the matched ranges to embolden (none in
        // the zero state's list, which leads with the drawer's folders).
        data class AppGrid(
            val apps: List<SearchResult.App>,
            val folders: List<SearchResult.Folder> = emptyList()
        ) : Row
        data class Top(val hit: TopHit) : Row
        data class ProviderIcons(val query: String) : Row
    }

    // Provider marks are raw app icons, so they go through the same renderer
    // every other icon does rather than drawing unshaped.
    private var iconRenderer: com.supernova.launcher.data.IconRenderer? = null

    // Custom cards render through the shared binder, whose callbacks the
    // controller wires to launch-and-hide. Card rows are skipped until set.
    private var cardBinder: CardViewBinder? = null

    fun setCardBinder(binder: CardViewBinder) {
        cardBinder = binder
    }

    private fun renderer(context: android.content.Context): com.supernova.launcher.data.IconRenderer =
        iconRenderer ?: com.supernova.launcher.data.IconRenderer(context).also { iconRenderer = it }

    private val rows = mutableListOf<Row>()
    // Stable ids: a list re-submitted as the slower providers answer keeps
    // the rows it already showed, attached, instead of recycling every one.
    // A tile the finger is holding stays the same view, so its long-press
    // still fires, and one already long-pressed keeps its place on screen.
    private val rowIds = mutableListOf<Long>()
    private val stableIds = mutableMapOf<String, Long>()
    private var prefs: LauncherPrefs? = null
    private var accentColor: Int = 0
    private var textColor: Int = 0
    private var cardColor: Int = 0
    private var cardTextColor: Int = 0

    // Grid columns for the app tiles; the controller derives it from the
    // drawer's column setting.
    var columns: Int = 4

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = rowIds[position]

    // Every submit ends here: one id per row, from what the row shows, made
    // unique within the list.
    private fun notifyRowsChanged() {
        rowIds.clear()
        val seen = mutableMapOf<String, Int>()
        rows.forEach { row ->
            val base = rowKey(row)
            val n = seen.merge(base, 1, Int::plus) ?: 1
            val key = if (n == 1) base else "$base#$n"
            rowIds.add(stableIds.getOrPut(key) { stableIds.size.toLong() })
        }
        notifyDataSetChanged()
    }

    private fun rowKey(row: Row): String = when (row) {
        is Row.Header -> "header/${row.title}"
        is Row.Frequent -> "frequent"
        is Row.Shortcuts -> "suggested"
        Row.Divider -> "divider"
        is Row.AppGrid -> "grid"
        is Row.Top -> "top"
        is Row.ProviderIcons -> "providers"
        is Row.Result -> when (val result = row.result) {
            is SearchResult.Calculation -> "calc"
            is SearchResult.Card -> "card/${result.card.id}"
            is SearchResult.Folder -> "folder/${result.itemId}/${result.groupName}"
            is SearchResult.Shortcut -> "shortcut/${result.userSerial}/${result.packageName}/${result.shortcutId}"
            is SearchResult.Contact -> "contact/${result.contactId}"
            is SearchResult.App -> "app/${result.app.userSerial}/${result.app.packageName}/${result.app.className}"
            is SearchResult.Web -> "web"
            is SearchResult.ProviderChips -> "chips"
        }
    }

    // Plate text/accent and the card plate follow the active style; called
    // by the controller when it (re)applies the surface style.
    fun setAppearance(prefs: LauncherPrefs, accent: Int, text: Int, cardSurface: Int, cardText: Int) {
        this.prefs = prefs
        accentColor = accent
        textColor = text
        cardColor = cardSurface
        cardTextColor = cardText
    }

    // oneRowApps: the app tiles are capped to a single grid row.
    fun submitResults(
        results: List<SearchResult>,
        query: String,
        topHit: TopHit?,
        oneRowApps: Boolean = false
    ) {
        rows.clear()
        results.filterIsInstance<SearchResult.Calculation>().mapTo(rows) { Row.Result(it) }
        if (topHit != null) rows.add(Row.Top(topHit))
        val apps = results.filterIsInstance<SearchResult.App>()
            .filter { it.app != topHit?.app }
            .let { if (oneRowApps) it.take(columns) else it }
        if (apps.isNotEmpty()) rows.add(Row.AppGrid(apps))
        results.filterIsInstance<SearchResult.Card>().mapTo(rows) { Row.Result(it) }
        results.filterIsInstance<SearchResult.Folder>().mapTo(rows) { Row.Result(it) }
        // The card already lists the top hit's shortcuts.
        results.filterIsInstance<SearchResult.Shortcut>()
            .filter { topHit == null || it.packageName != topHit.app.packageName }
            .mapTo(rows) { Row.Result(it) }
        results.filterIsInstance<SearchResult.Contact>().mapTo(rows) { Row.Result(it) }
        // Every query ends with the providers; the web engine leads them.
        // With every provider off it's the web engine alone, as a plain row.
        if (SearchMarks.hasProviders(prefs)) {
            rows.add(Row.Header(R.string.search_with))
            rows.add(Row.ProviderIcons(query))
        } else {
            results.filterIsInstance<SearchResult.Web>().mapTo(rows) { Row.Result(it) }
        }
        notifyRowsChanged()
    }

    // An embedded surface with an empty query shows nothing of its own.
    fun clear() {
        if (rows.isEmpty()) return
        rows.clear()
        notifyRowsChanged()
    }

    fun submitZeroState(
        shortcuts: List<SuggestedShortcuts.Pill>,
        frequentApps: List<LaunchableApp>,
        allApps: List<LaunchableApp>,
        folders: List<SearchResult.Folder> = emptyList()
    ) {
        rows.clear()
        if (shortcuts.isNotEmpty()) rows.add(Row.Shortcuts(shortcuts))
        if (frequentApps.isNotEmpty()) rows.add(Row.Frequent(frequentApps))
        if (allApps.isNotEmpty() || folders.isNotEmpty()) {
            rows.add(Row.Divider)
            rows.add(Row.AppGrid(allApps.map { SearchResult.App(it) }, folders))
        }
        notifyRowsChanged()
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int = when (val row = rows[position]) {
        is Row.Header -> TYPE_HEADER
        is Row.Frequent -> TYPE_FREQUENT
        is Row.Shortcuts -> TYPE_SHORTCUTS
        Row.Divider -> TYPE_DIVIDER
        is Row.AppGrid -> TYPE_GRID
        is Row.Top -> TYPE_TOP
        is Row.ProviderIcons -> TYPE_PROVIDER_ICONS
        is Row.Result -> when (row.result) {
            is SearchResult.Calculation -> TYPE_CALC
            is SearchResult.Card -> TYPE_CARD
            else -> TYPE_ROW
        }
    }

    private fun fullWidth(view: View): View = view.apply {
        layoutParams = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val density = parent.resources.displayMetrics.density
        return when (viewType) {
            TYPE_CALC -> CalcHolder(ItemSearchCalcBinding.inflate(inflater, parent, false))
            TYPE_CARD -> CardHolder(
                com.supernova.launcher.databinding.ItemFeedCustomBinding.inflate(inflater, parent, false)
            )
            TYPE_TOP -> TopHitHolder(ItemSearchTopHitBinding.inflate(inflater, parent, false))
            TYPE_HEADER -> HeaderHolder(ItemSearchHeaderBinding.inflate(inflater, parent, false))
            TYPE_DIVIDER -> DividerHolder(ItemSearchDividerBinding.inflate(inflater, parent, false))
            TYPE_GRID -> GridHolder(fullWidth(SearchGridView(parent.context).apply {
                setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
            }) as SearchGridView)
            TYPE_SHORTCUTS -> ShortcutsHolder(fullWidth(FlowLayout(parent.context).apply {
                maxLines = 1
                setPadding((16 * density).toInt(), (4 * density).toInt(), (16 * density).toInt(), 0)
            }) as FlowLayout)
            TYPE_PROVIDER_ICONS -> ProviderIconsHolder(fullWidth(FrequentAppsRowView(parent.context)) as FrequentAppsRowView)
            TYPE_FREQUENT -> FrequentHolder(fullWidth(FrequentAppsRowView(parent.context)) as FrequentAppsRowView)
            else -> RowHolder(ItemSearchRowBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> (holder as HeaderHolder).bind(row.title)
            is Row.Frequent -> (holder as FrequentHolder).bind(row.apps)
            is Row.Shortcuts -> (holder as ShortcutsHolder).bind(row.pills)
            is Row.ProviderIcons -> (holder as ProviderIconsHolder).bind(row.query)
            Row.Divider -> (holder as DividerHolder).bind()
            is Row.AppGrid -> (holder as GridHolder).bind(row.apps, row.folders)
            is Row.Top -> (holder as TopHitHolder).bind(row.hit)
            is Row.Result -> when (val result = row.result) {
                is SearchResult.Calculation -> (holder as CalcHolder).bind(result)
                is SearchResult.Shortcut -> (holder as RowHolder).bindShortcut(result)
                is SearchResult.Contact -> (holder as RowHolder).bindContact(result)
                is SearchResult.Folder -> (holder as RowHolder).bindFolder(result)
                is SearchResult.Card -> (holder as CardHolder).bind(result)
                is SearchResult.Web -> (holder as RowHolder).bindWeb(result)
                is SearchResult.App, is SearchResult.ProviderChips -> Unit
            }
        }
    }

    // The letters the matcher hit, in bold, to show why a
    // tile is there.
    private fun highlighted(label: String, ranges: List<IntRange>): CharSequence {
        if (ranges.isEmpty()) return label
        val spannable = SpannableString(label)
        for (range in ranges) {
            val start = range.first.coerceIn(0, label.length)
            val end = (range.last + 1).coerceIn(start, label.length)
            if (end > start) {
                spannable.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return spannable
    }

    // The best match as a card: launches on tap, App info from the gear,
    // and the app's shortcuts as the same pills the cards use.
    private inner class TopHitHolder(
        private val binding: ItemSearchTopHitBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        // What the pills were last built from: the slower providers re-submit
        // the same top hit, and pills rebuilt then took a held one, and its
        // long-press, out from under the finger.
        private var pillsKey: List<Any>? = null

        fun bind(hit: TopHit) {
            val context = binding.root.context
            (binding.root.background?.mutate() as? android.graphics.drawable.GradientDrawable)?.setColor(cardColor)
            binding.topHitIcon.setImageDrawable(appCatalog.iconFor(hit.app))
            // Hidden while it was dragged; the holder comes back for the next
            // query.
            binding.topHitIcon.alpha = 1f
            binding.topHitLabel.text = hit.app.label
            binding.topHitLabel.setTextColor(cardTextColor)
            binding.topHitInfo.imageTintList = ColorStateList.valueOf(cardTextColor)
            binding.topHitInfo.setOnClickListener { callbacks.onAppInfo(hit.app) }
            binding.topHitRow.contentDescription = hit.app.label
            binding.topHitRow.setOnClickListener { callbacks.onApp(it, hit.app) }
            // Long-press lifts the icon, not the card: the menu opens under it and
            // the icon alone leaves the row while it is dragged.
            binding.topHitRow.setOnLongClickListener {
                callbacks.onAppLongPress(binding.topHitIcon, hit.app)
                true
            }
            binding.topHitDivider.setBackgroundColor((0x33 shl 24) or (cardTextColor and 0xFFFFFF))
            val flow = binding.topHitShortcuts
            val key = hit.shortcuts.map { listOf(it.userSerial, it.packageName, it.shortcutId, it.label) } +
                cardTextColor
            if (key != pillsKey || flow.childCount != hit.shortcuts.size) {
                pillsKey = key
                flow.removeAllViews()
                hit.shortcuts.forEach { shortcut ->
                    flow.addView(
                        CardViewBinder.pill(
                            context, shortcut.icon, shortcut.label, cardTextColor,
                            onLongPress = { view -> callbacks.onShortcutLongPress(view, shortcut) }
                        ) { view ->
                            callbacks.onShortcut(view, shortcut)
                        }
                    )
                }
            }
            // A pill dragged out was hidden for the drag.
            for (i in 0 until flow.childCount) flow.getChildAt(i).alpha = 1f
            val hasShortcuts = hit.shortcuts.isNotEmpty()
            binding.topHitDivider.visibility = if (hasShortcuts) View.VISIBLE else View.GONE
            flow.visibility = if (hasShortcuts) View.VISIBLE else View.GONE
        }
    }

    // App tiles, the drawer's own cell, in a fixed-column grid.
    private inner class GridHolder(private val grid: SearchGridView) : RecyclerView.ViewHolder(grid) {
        fun bind(apps: List<SearchResult.App>, folders: List<SearchResult.Folder>) {
            grid.columns = columns
            // The tiles already there are bound again rather than replaced
            // (see rowIds): one taken out from under a finger took its
            // long-press with it.
            val count = folders.size + apps.size
            if (grid.childCount > count) grid.removeViews(count, grid.childCount - count)
            val inflater = LayoutInflater.from(grid.context)
            while (grid.childCount < count) grid.addView(ItemDrawerAppBinding.inflate(inflater, grid, false).root)
            folders.forEachIndexed { index, folder ->
                val tile = ItemDrawerAppBinding.bind(grid.getChildAt(index))
                tile.root.alpha = 1f
                if (folder.icon != null) {
                    tile.appIcon.setImageDrawable(folder.icon)
                } else {
                    tile.appIcon.setImageResource(R.drawable.ic_folder)
                }
                prefs?.let {
                    LabelStyler.apply(tile.appLabel, it, onWallpaper = false, context = LabelContext.DRAWER)
                }
                tile.appLabel.text = folder.label
                tile.appLabel.setTextColor(textColor)
                tile.root.contentDescription = grid.context.getString(R.string.folder_desc, folder.label)
                tile.root.setOnClickListener { callbacks.onFolder(folder) }
                tile.root.setOnLongClickListener {
                    callbacks.onFolderLongPress(it, folder)
                    true
                }
            }
            apps.forEachIndexed { index, result ->
                val app = result.app
                val tile = ItemDrawerAppBinding.bind(grid.getChildAt(folders.size + index))
                // Hidden while it was dragged.
                tile.root.alpha = 1f
                tile.appIcon.setImageDrawable(appCatalog.iconFor(app))
                prefs?.let {
                    LabelStyler.apply(tile.appLabel, it, onWallpaper = false, context = LabelContext.DRAWER)
                }
                tile.appLabel.text = highlighted(app.label, result.highlight)
                tile.appLabel.setTextColor(textColor)
                tile.root.contentDescription = app.label
                tile.root.setOnClickListener { callbacks.onApp(it, app) }
                tile.root.setOnLongClickListener {
                    callbacks.onAppLongPress(it, app)
                    true
                }
            }
        }
    }

    private inner class ShortcutsHolder(private val flow: FlowLayout) : RecyclerView.ViewHolder(flow) {
        fun bind(pills: List<SuggestedShortcuts.Pill>) {
            flow.removeAllViews()
            pills.forEach { pill ->
                val shortcut = SearchResult.Shortcut(
                    pill.packageName, pill.shortcutId, pill.label, pill.userSerial, pill.icon
                )
                flow.addView(
                    CardViewBinder.pill(
                        flow.context, pill.icon, pill.label, textColor,
                        onLongPress = { view -> callbacks.onShortcutLongPress(view, shortcut) }
                    ) { view ->
                        callbacks.onShortcut(view, shortcut)
                    }
                )
            }
        }
    }

    private inner class DividerHolder(
        private val binding: ItemSearchDividerBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind() {
            binding.searchDivider.setBackgroundColor((0x4D shl 24) or (textColor and 0xFFFFFF))
        }
    }

    // A matched custom card, drawn exactly as the feed draws it. The card's
    // own margins give it the same rhythm as the feed list.
    private inner class CardHolder(
        private val binding: com.supernova.launcher.databinding.ItemFeedCustomBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        init {
            val density = binding.root.resources.displayMetrics.density
            (binding.root.layoutParams as? ViewGroup.MarginLayoutParams)?.apply {
                leftMargin = (16 * density).toInt()
                rightMargin = (16 * density).toInt()
            }
        }

        fun bind(result: SearchResult.Card) {
            val binder = cardBinder ?: return
            binder.bind(binding, result.card)
        }
    }

    private inner class CalcHolder(
        private val binding: ItemSearchCalcBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(result: SearchResult.Calculation) {
            binding.calcExpression.text = result.expression
            binding.calcResult.text = result.display
            binding.root.setOnClickListener { callbacks.onCopyCalculation(result.display) }
        }
    }

    private inner class RowHolder(
        private val binding: ItemSearchRowBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private fun reset() {
            // Hidden while it was dragged.
            binding.root.alpha = 1f
            ImageViewCompat.setImageTintList(binding.resultIcon, null)
            binding.resultLabel.setTextColor(textColor)
            binding.root.setOnLongClickListener(null)
            binding.root.isLongClickable = false
            // Contact-card action buttons from a previous bind (D4).
            val root = binding.root as ViewGroup
            (root.childCount - 1 downTo 0).forEach { index ->
                if (root.getChildAt(index).tag == ACTION_TAG) root.removeViewAt(index)
            }
        }

        fun bindShortcut(shortcut: SearchResult.Shortcut) {
            reset()
            binding.resultIcon.setImageDrawable(shortcut.icon)
            binding.resultLabel.text = shortcut.label
            prefs?.let { LabelStyler.apply(binding.resultLabel, it, onWallpaper = false) }
            binding.resultLabel.setTextColor(textColor)
            binding.root.contentDescription = shortcut.label
            binding.root.setOnClickListener { callbacks.onShortcut(it, shortcut) }
            binding.root.setOnLongClickListener {
                callbacks.onShortcutLongPress(it, shortcut)
                true
            }
        }

        fun bindContact(contact: SearchResult.Contact) {
            reset()
            binding.resultIcon.setImageDrawable(contact.photo)
            binding.resultLabel.text = contact.name
            binding.root.contentDescription = contact.name
            binding.root.setOnClickListener { callbacks.onContact(contact) }
            // Card view (D4): call/message actions when a number is known.
            contact.phoneNumber?.let { number ->
                addActionButton(R.drawable.ic_action_phone, R.string.contact_call) {
                    callbacks.onContactCall(number)
                }
                addActionButton(R.drawable.ic_action_message, R.string.contact_message) {
                    callbacks.onContactMessage(number)
                }
            }
        }

        // Appends a tinted, borderless action to the row's end; reset() must
        // remove these before the holder is reused.
        private fun addActionButton(iconRes: Int, labelRes: Int, onClick: () -> Unit) {
            val context = binding.root.context
            val button = android.widget.ImageButton(context).apply {
                tag = ACTION_TAG
                setImageResource(iconRes)
                background = null
                contentDescription = context.getString(labelRes)
                imageTintList = ColorStateList.valueOf(accentColor)
                setOnClickListener { onClick() }
            }
            val size = (40 * context.resources.displayMetrics.density).toInt()
            (binding.root as ViewGroup).addView(button, ViewGroup.LayoutParams(size, size))
        }

        fun bindWeb(web: SearchResult.Web) {
            reset()
            binding.resultIcon.setImageResource(R.drawable.ic_search)
            tintIcon()
            binding.resultLabel.text =
                binding.root.context.getString(R.string.search_web_for, web.provider.displayName, web.query)
            binding.root.setOnClickListener { callbacks.onWeb(web) }
        }

        fun bindFolder(folder: SearchResult.Folder) {
            reset()
            if (folder.icon != null) {
                binding.resultIcon.setImageDrawable(folder.icon)
            } else {
                binding.resultIcon.setImageResource(R.drawable.ic_folder)
                tintIcon()
            }
            binding.resultLabel.text = folder.label
            binding.root.contentDescription =
                binding.root.context.getString(R.string.folder_desc, folder.label)
            binding.root.setOnClickListener { callbacks.onFolder(folder) }
            // A drawer folder is a drawer icon: its menu, and a drag home.
            // A home-screen folder is already on the home screen.
            if (folder.groupName != null) {
                binding.root.setOnLongClickListener {
                    callbacks.onFolderLongPress(it, folder)
                    true
                }
            }
        }


        private fun tintIcon() {
            ImageViewCompat.setImageTintList(binding.resultIcon, ColorStateList.valueOf(textColor))
        }
    }

    private inner class HeaderHolder(
        private val binding: ItemSearchHeaderBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(@StringRes title: Int) {
            binding.headerLabel.setText(title)
            binding.headerLabel.setTextColor(textColor)
        }
    }

    // Brand marks for the configured providers, the web engine first so a web
    // search stays reachable even when Google is not one of the chips.
    private inner class ProviderIconsHolder(
        private val view: FrequentAppsRowView
    ) : RecyclerView.ViewHolder(view) {
        fun bind(query: String) {
            view.bind(
                SearchMarks.entries(view.context, prefs, renderer(view.context), accentColor) { chip ->
                    callbacks.onChip(chip, query)
                },
                SearchMarks.MARK_DP
            )
        }
    }

    // The labelled frequent row, as on the feed page.
    private inner class FrequentHolder(
        private val view: FrequentAppsRowView
    ) : RecyclerView.ViewHolder(view) {
        fun bind(apps: List<LaunchableApp>) {
            view.bindLabeled(
                apps.map { app ->
                    FrequentAppsRowView.Entry(
                        app.label, appCatalog.iconFor(app),
                        onLongPress = { anchor -> callbacks.onAppLongPress(anchor, app) }
                    ) { anchor ->
                        callbacks.onFrequentApp(anchor, app)
                    }
                },
                textColor, columns, sidePaddingDp = 8
            )
        }
    }

    private companion object {
        const val ACTION_TAG = "contact-action"
        const val TYPE_ROW = 0
        const val TYPE_CALC = 1
        const val TYPE_HEADER = 3
        const val TYPE_FREQUENT = 4
        const val TYPE_PROVIDER_ICONS = 5
        const val TYPE_CARD = 6
        const val TYPE_TOP = 7
        const val TYPE_GRID = 8
        const val TYPE_SHORTCUTS = 9
        const val TYPE_DIVIDER = 10
    }
}
