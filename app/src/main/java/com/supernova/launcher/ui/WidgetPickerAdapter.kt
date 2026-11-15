package com.supernova.launcher.ui

import android.appwidget.AppWidgetProviderInfo
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.ColorUtils
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.recyclerview.widget.RecyclerView
import com.supernova.launcher.R
import com.supernova.launcher.databinding.ItemWidgetPickerHeaderBinding
import com.supernova.launcher.databinding.ItemWidgetPickerRowBinding
import com.supernova.launcher.databinding.ItemWidgetPickerShortcutBinding

// Add list: collapsible per-app cards, each expanding into a
// two-column grid of widget previews followed by the app's shortcut rows.
class WidgetPickerAdapter(
    private val onPickWidget: (AppWidgetProviderInfo) -> Unit,
    private val onPickShortcut: (ShortcutTarget) -> Unit,
    // Long-press on a preview: the host may pick the widget up and drag it
    // to a spot (the in-activity add sheet does; a dialog picker cannot, its
    // touches live in another window). Returns whether it took the press.
    private val onDragWidget: ((AppWidgetProviderInfo, View) -> Boolean)? = null,
    // The same for a shortcut row, lifted by its icon; its flow then runs
    // for the cell it is dropped on.
    private val onDragShortcut: ((ShortcutTarget, View) -> Boolean)? = null
): RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var groups: List<WidgetPickerGroup> = emptyList()
    private var query: String = ""
    // Packages currently expanded. A single-app picker (the popup "Widgets"
    // row) starts fully expanded; the general picker starts collapsed.
    private val expanded = mutableSetOf<String>()
    private var startExpanded = false

    private val flat = mutableListOf<WidgetPickerEntry>()
    private val previewCache = LruCache<String, Drawable>(40)

    fun submit(list: List<WidgetPickerGroup>, expandAll: Boolean) {
        groups = list
        startExpanded = expandAll
        if (expandAll) list.forEach { expanded.add(it.key) }
        rebuild()
    }

    fun filter(text: String) {
        query = text.trim().lowercase()
        // Typing auto-expands matches so results are visible; clearing
        // collapses back to the browse view (unless single-app).
        rebuild()
    }

    val isEmpty: Boolean get() = groups.isEmpty()

    private fun rebuild() {
        flat.clear()
        val q = query
        for (group in groups) {
            val groupMatches = q.isEmpty() || group.label.lowercase().contains(q)
            val widgets = if (q.isEmpty() || groupMatches) group.widgets else group.widgets.filter { it.label.lowercase().contains(q) }
            val shortcuts = if (q.isEmpty() || groupMatches) group.shortcuts else group.shortcuts.filter { it.label.lowercase().contains(q) }
            if (widgets.isEmpty() && shortcuts.isEmpty()) continue
            val isExpanded = startExpanded || q.isNotEmpty() || group.key in expanded
            flat.add(
                WidgetPickerEntry.Header(
                    key = group.key,
                    label = group.label,
                    icon = group.icon,
                    widgetCount = group.widgets.size,
                    shortcutCount = group.shortcuts.size,
                    expanded = isExpanded
                )
            )
            if (!isExpanded) continue
            widgets.chunked(2).forEach { pair ->
                flat.add(WidgetPickerEntry.WidgetRow(pair[0], pair.getOrNull(1)))
            }
            flat.addAll(shortcuts)
        }
        notifyDataSetChanged()
    }

    // An expanded app reads as one card: the header takes the top corners,
    // the group's last row the bottom ones, and the rows between are square
    // and butt together with no gap.
    private fun shapeAsCard(view: View, position: Int) {
        val isHeader = flat[position] is WidgetPickerEntry.Header
        val next = flat.getOrNull(position + 1)
        val closesGroup = next == null || next is WidgetPickerEntry.Header
        val res = view.resources
        val radius = res.getDimension(R.dimen.picker_card_radius)
        val top = if (isHeader) radius else 0f
        val bottom = if (closesGroup) radius else 0f
        val background = AppCompatResources.getDrawable(view.context, R.drawable.bg_picker_card)
            ?.mutate() as? GradientDrawable
        background?.cornerRadii = floatArrayOf(top, top, top, top, bottom, bottom, bottom, bottom)
        // An open app's title gets a faint rule under it, inset from the
        // card's sides, between it and the previews.
        view.background = if (isHeader && !closesGroup && background != null) {
            val ink = view.context.getColor(R.color.textPrimary)
            val rule = ColorUtils.setAlphaComponent(ink, DIVIDER_ALPHA).toDrawable()
            LayerDrawable(arrayOf(background, rule)).apply {
                val inset = res.getDimensionPixelSize(R.dimen.space_md)
                setLayerGravity(1, Gravity.BOTTOM or Gravity.FILL_HORIZONTAL)
                setLayerHeight(1, res.getDimensionPixelSize(R.dimen.divider_thin).coerceAtLeast(1))
                setLayerInsetLeft(1, inset)
                setLayerInsetRight(1, inset)
            }
        } else {
            background
        }
        val gap = res.getDimensionPixelSize(R.dimen.space_xs)
        (view.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
            lp.topMargin = if (isHeader) gap else 0
            lp.bottomMargin = if (closesGroup) gap else 0
            view.layoutParams = lp
        }
    }

    private fun toggle(key: String) {
        if (key in expanded) expanded.remove(key) else expanded.add(key)
        rebuild()
    }

    override fun getItemViewType(position: Int): Int = when (flat[position]) {
        is WidgetPickerEntry.Header -> TYPE_HEADER
        is WidgetPickerEntry.WidgetRow -> TYPE_WIDGET_ROW
        is WidgetPickerEntry.Shortcut -> TYPE_SHORTCUT
        // Never placed in the flat list on its own; rows carry widgets.
        is WidgetPickerEntry.Widget -> TYPE_WIDGET_ROW
    }

    override fun getItemCount(): Int = flat.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderViewHolder(ItemWidgetPickerHeaderBinding.inflate(inflater, parent, false))
            TYPE_SHORTCUT -> ShortcutViewHolder(ItemWidgetPickerShortcutBinding.inflate(inflater, parent, false))
            else -> RowViewHolder(ItemWidgetPickerRowBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val entry = flat[position]) {
            is WidgetPickerEntry.Header -> (holder as HeaderViewHolder).bind(entry)
            is WidgetPickerEntry.WidgetRow -> (holder as RowViewHolder).bind(entry)
            is WidgetPickerEntry.Shortcut -> (holder as ShortcutViewHolder).bind(entry)
            is WidgetPickerEntry.Widget -> Unit
        }
        shapeAsCard(holder.itemView, position)
    }

    inner class HeaderViewHolder(private val binding: ItemWidgetPickerHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: WidgetPickerEntry.Header) {
            val res = binding.root.context.resources
            binding.headerLabel.text = entry.label
            binding.headerIcon.setImageDrawable(entry.icon)
            val parts = buildList {
                if (entry.widgetCount > 0) {
                    add(res.getQuantityString(R.plurals.widget_count, entry.widgetCount, entry.widgetCount))
                }
                if (entry.shortcutCount > 0) {
                    add(res.getQuantityString(R.plurals.shortcut_count, entry.shortcutCount, entry.shortcutCount))
                }
            }
            binding.headerCount.text = parts.joinToString(", ")
            binding.headerChevron.rotation = if (entry.expanded) 180f else 0f
            binding.root.setOnClickListener { toggle(entry.key) }
        }
    }

    inner class RowViewHolder(private val binding: ItemWidgetPickerRowBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: WidgetPickerEntry.WidgetRow) {
            bindCell(binding.cellA, binding.previewA, binding.labelA, binding.spanA, entry.first)
            val second = entry.second
            if (second == null) {
                binding.cellB.visibility = View.INVISIBLE
                // A recycled row may still hold another widget's picture,
                // which would keep the empty cell tall and push the lone
                // preview down (the row lines its cells up at the bottom).
                binding.previewB.tag = null
                binding.previewB.setImageDrawable(null)
                binding.cellB.setOnClickListener(null)
            } else {
                binding.cellB.visibility = View.VISIBLE
                bindCell(binding.cellB, binding.previewB, binding.labelB, binding.spanB, second)
            }
        }

        private fun bindCell(
            cell: View,
            preview: ImageView,
            label: TextView,
            span: TextView,
            widget: WidgetPickerEntry.Widget
        ) {
            val context = cell.context
            label.text = widget.label
            span.text = context.getString(R.string.widget_span, widget.spanX, widget.spanY)

            // A preview is boxed by its widget's span and only ever shrunk
            // into that box, so it keeps the size it will take on the grid
            // relative to the others; the cell's own width caps it as well.
            val res = context.resources
            val perCell = res.getDimensionPixelSize(R.dimen.widget_preview_per_cell)
            val floor = res.getDimensionPixelSize(R.dimen.widget_preview_icon)
            preview.maxWidth = maxOf(widget.spanX * perCell, floor)
            preview.maxHeight = (widget.spanY * perCell).coerceIn(
                floor,
                res.getDimensionPixelSize(R.dimen.widget_preview_max_height)
            )

            val cacheKey = widget.info.provider.flattenToString()
            preview.tag = cacheKey
            val cached = previewCache.get(cacheKey)
            if (cached != null) {
                preview.setImageDrawable(cached)
            } else {
                // Loading opens the provider app's resources and decodes a
                // bitmap, which on the main thread made the picker stutter as
                // a group opened or a search matched many widgets.
                preview.setImageDrawable(null)
                val appContext = context.applicationContext
                previewLoader.execute {
                    // Providers that only declare a previewLayout hand back
                    // nothing usable here; fall back to the app icon, as the
                    // launcher draws it. The cell shows a preview at its own
                    // size, so the stand-in icon is drawn at a fixed one.
                    val loaded = runCatching { widget.info.loadPreviewImage(appContext, 0) }.getOrNull()
                        ?.takeIf { it.intrinsicWidth > 0 && it.intrinsicHeight > 0 }
                        ?: (
                            widget.appIcon
                                ?: runCatching {
                                    appContext.packageManager.getApplicationIcon(widget.info.provider.packageName)
                                }.getOrNull()
                                ?: AppCompatResources.getDrawable(appContext, R.drawable.ic_widget)
                            )?.let { icon ->
                            val size = appContext.resources.getDimensionPixelSize(R.dimen.widget_preview_icon)
                            runCatching {
                                icon.toBitmap(size, size).toDrawable(appContext.resources)
                            }.getOrNull()
                        }
                    mainHandler.post {
                        if (loaded != null) previewCache.put(cacheKey, loaded)
                        // The cell may show another widget by now.
                        if (preview.tag == cacheKey) preview.setImageDrawable(loaded)
                    }
                }
            }
            cell.setOnClickListener { onPickWidget(widget.info) }
            cell.setOnLongClickListener { onDragWidget?.invoke(widget.info, preview) ?: false }
        }
    }

    inner class ShortcutViewHolder(private val binding: ItemWidgetPickerShortcutBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: WidgetPickerEntry.Shortcut) {
            val context = binding.root.context
            binding.shortcutLabel.text = entry.label
            binding.shortcutIcon.setImageDrawable(entry.icon)
            binding.shortcutSubtitle.text = context.getString(
                when (entry.target) {
                    is ShortcutTarget.Action -> R.string.launcher_action_row_subtitle
                    ShortcutTarget.Activities -> R.string.activities_row_subtitle
                    is ShortcutTarget.Create -> R.string.shortcut_row_subtitle
                }
            )
            binding.root.setOnClickListener { onPickShortcut(entry.target) }
            binding.root.setOnLongClickListener {
                onDragShortcut?.invoke(entry.target, binding.shortcutIcon) ?: false
            }
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_WIDGET_ROW = 1
        private const val TYPE_SHORTCUT = 2

        // About 12% of the text colour: visible on both sheet themes, faint.
        private const val DIVIDER_ALPHA = 31

        // One background thread for every picker's previews, in the order
        // the cells asked for them.
        private val previewLoader: java.util.concurrent.ExecutorService by lazy {
            java.util.concurrent.Executors.newSingleThreadExecutor { task ->
                Thread(task, "widget-previews").apply { isDaemon = true }
            }
        }
        private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    }
}
