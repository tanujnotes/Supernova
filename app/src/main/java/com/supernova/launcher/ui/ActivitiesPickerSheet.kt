package com.supernova.launcher.ui

import android.annotation.SuppressLint
import android.content.ComponentName
import android.graphics.drawable.Drawable
import android.os.Process
import android.os.UserManager
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.supernova.launcher.R
import com.supernova.launcher.data.ActivityApp
import com.supernova.launcher.data.ActivityCatalog
import com.supernova.launcher.data.ActivityChoice
import com.supernova.launcher.databinding.ItemWidgetPickerHeaderBinding
import com.supernova.launcher.databinding.ItemWidgetPickerShortcutBinding
import com.supernova.launcher.databinding.SheetActivitiesPickerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// The activity an Activities pick lands as: started by its component, and
// labelled as the picker showed it.
data class ActivityPick(val component: ComponentName, val label: String)

// The Activities picker (the widget sheet's Activities row): every
// installed app, launcher icon or not, as the widget sheet's collapsible
// cards with its count of activities; a card opens into the activities, each
// with its icon, its name and its class. A tap on one hands it back. The
// search field above narrows the list as the widget sheet's does.
class ActivitiesPickerSheet private constructor(
    private val activity: ComponentActivity,
    private val icons: WidgetPickerIcons,
    private val onPick: (ActivityPick) -> Unit
) {

    private val sheet = Sheets.open(activity)
    private val binding = SheetActivitiesPickerBinding.inflate(LayoutInflater.from(sheet.context))
    private val adapter = RowAdapter()
    private val pm = activity.packageManager
    private val mySerial = runCatching {
        activity.getSystemService(UserManager::class.java)?.getSerialNumberForUser(Process.myUserHandle())
    }.getOrNull() ?: 0L
    private val iconCache = LruCache<String, Drawable>(ICON_CACHE_SIZE)

    private fun show() {
        // Most of the window: the list runs to every installed package.
        val window = activity.resources.displayMetrics.heightPixels
        val listHeight = (window - activity.dp(CHROME_DP)).coerceAtLeast(activity.dp(MIN_LIST_DP))
        binding.activitiesFrame.updateLayoutParams { height = listHeight }
        fitAboveKeyboard(listHeight)
        binding.activitiesSearch.doAfterTextChanged { adapter.filter(it?.toString().orEmpty()) }
        binding.activitiesRecycler.layoutManager = LinearLayoutManager(sheet.context)
        binding.activitiesRecycler.adapter = adapter
        binding.activitiesFastScroller.attach(binding.activitiesRecycler, binding.activitiesFastScrollBubble)
        binding.activitiesFastScroller.sections = { ActivityRows.sections(adapter.rows, LabelSections::of) }
        val text = sheet.context.getColor(R.color.textPrimary)
        binding.activitiesFastScroller.setColors(
            thumb = MaterialColors.getColor(binding.root, androidx.appcompat.R.attr.colorAccent),
            track = (FAST_SCROLL_TRACK_ALPHA shl 24) or (text and 0xFFFFFF),
            bubbleText = sheet.context.getColor(R.color.onAccent)
        )
        sheet.setContentView(binding.root)
        sheet.show()

        activity.lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) { ActivityCatalog.load(pm) }
            if (!sheet.isShowing) return@launch
            binding.activitiesProgress.visibility = View.GONE
            adapter.submit(apps)
        }
    }

    // A new list in place: back to its top for a new query, and "No
    // activities" for a list with none or a search that matched none.
    private fun listChanged(newQuery: Boolean) {
        if (newQuery) binding.activitiesRecycler.scrollToPosition(0)
        val empty = adapter.rows.isEmpty()
        binding.activitiesEmpty.isVisible = empty
        binding.activitiesFastScroller.isVisible = !empty
    }

    // The window shrinks over the keyboard (the widget sheet's way), and the
    // list with it, so the search field stays in sight while typing.
    private fun fitAboveKeyboard(listHeight: Int) {
        sheet.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val parent = sheet.findViewById<View>(com.google.android.material.R.id.coordinator) ?: return
        parent.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top == oldBottom - oldTop) return@addOnLayoutChangeListener
            val frame = binding.activitiesFrame
            val chrome = binding.root.height - frame.height
            val fits = (bottom - top - chrome - activity.dp(KEYBOARD_GAP_DP))
                .coerceIn(activity.dp(MIN_TYPING_LIST_DP), listHeight)
            if (frame.layoutParams.height != fits) frame.post { frame.updateLayoutParams { height = fits } }
        }
    }

    private fun picked(app: ActivityApp, choice: ActivityChoice) {
        sheet.dismiss()
        onPick(ActivityPick(ComponentName(app.packageName, choice.className), choice.label))
    }

    // An app's icon as the launcher draws it, or the system's for a package
    // with no drawer icon; an activity's own icon through the same shape and
    // glass, and its app's when it has none.
    private fun loadIcon(app: ActivityApp, choice: ActivityChoice?): Drawable? {
        if (choice != null && choice.hasOwnIcon) {
            runCatching { pm.getActivityIcon(ComponentName(app.packageName, choice.className)) }
                .getOrNull()?.let { return icons.render(it) }
        }
        return icons.app(app.packageName, mySerial)
            ?: runCatching { pm.getApplicationIcon(app.packageName) }.getOrNull()
    }

    private fun bindIcon(view: ImageView, app: ActivityApp, choice: ActivityChoice?) {
        val key = if (choice != null && choice.hasOwnIcon) "${app.packageName}/${choice.className}" else app.packageName
        view.tag = key
        iconCache.get(key)?.let {
            view.setImageDrawable(it)
            return
        }
        // Icons decode off the main thread, as the widget sheet's previews
        // do, so scrolling the long list or opening a card doesn't wait on
        // them.
        view.setImageDrawable(null)
        iconLoader.execute {
            val icon = loadIcon(app, choice)
            mainHandler.post {
                if (icon != null) iconCache.put(key, icon)
                // The row may show another app by now.
                if (view.tag == key) view.setImageDrawable(icon)
            }
        }
    }

    private inner class RowAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private var search = ActivityRows.Search(emptyList())
        private var query = ""
        // What the list shows: every app, or what the search left of them.
        private var apps: List<ActivityApp> = emptyList()
        // Cards opened while browsing; and cards closed while searching,
        // where every match starts open, as in the widget sheet. A new query
        // opens them all again.
        private val expanded = mutableSetOf<String>()
        private val closedInSearch = mutableSetOf<String>()
        var rows: List<ActivityRows.Row> = emptyList()
            private set

        private var pending: Job? = null

        // The whole list arrives at once, after the load.
        fun submit(list: List<ActivityApp>) {
            search = ActivityRows.Search(list)
            rebuild(newQuery = false)
        }

        fun filter(text: String) {
            val q = text.trim()
            if (q == query) return
            query = q
            closedInSearch.clear()
            rebuild(newQuery = true)
        }

        // The match runs off the main thread, so typing stays smooth over a
        // few thousand activities; a newer keystroke's match replaces an
        // older one still running.
        @SuppressLint("NotifyDataSetChanged")
        private fun rebuild(newQuery: Boolean) {
            pending?.cancel()
            val search = search
            val q = query
            val browseOpen = expanded.toSet()
            val searchClosed = closedInSearch.toSet()
            pending = activity.lifecycleScope.launch {
                val (matched, flat) = withContext(Dispatchers.Default) {
                    val matched = search.matching(q)
                    matched to ActivityRows.flatten(matched, openCards(q, matched, browseOpen, searchClosed))
                }
                apps = matched
                rows = flat
                notifyDataSetChanged()
                listChanged(newQuery)
            }
        }

        private fun openCards(q: String, shown: List<ActivityApp>, browseOpen: Set<String>, searchClosed: Set<String>) =
            if (q.isEmpty()) browseOpen else shown.mapTo(HashSet()) { it.packageName } - searchClosed

        private fun flatten(): List<ActivityRows.Row> =
            ActivityRows.flatten(apps, openCards(query, apps, expanded, closedInSearch))

        // Opens or closes one card, its activities sliding in or out under it.
        // A tap while a match is still running waits for its list.
        fun toggle(position: Int) {
            if (pending?.isActive == true) return
            val row = rows.getOrNull(position) as? ActivityRows.Row.App ?: return
            val count = row.app.activities.size
            val flipped = if (query.isEmpty()) expanded else closedInSearch
            if (!flipped.remove(row.app.packageName)) flipped += row.app.packageName
            rows = flatten()
            notifyItemChanged(position)
            if (row.expanded) {
                notifyItemRangeRemoved(position + 1, count)
            } else {
                notifyItemRangeInserted(position + 1, count)
            }
        }

        override fun getItemViewType(position: Int): Int = when (rows[position]) {
            is ActivityRows.Row.App -> TYPE_APP
            is ActivityRows.Row.Activity -> TYPE_ACTIVITY
        }

        override fun getItemCount(): Int = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_APP) {
                AppHolder(ItemWidgetPickerHeaderBinding.inflate(inflater, parent, false))
            } else {
                ActivityHolder(ItemWidgetPickerShortcutBinding.inflate(inflater, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is ActivityRows.Row.App -> (holder as AppHolder).bind(row)
                is ActivityRows.Row.Activity -> (holder as ActivityHolder).bind(row)
            }
        }

        inner class AppHolder(private val binding: ItemWidgetPickerHeaderBinding) :
            RecyclerView.ViewHolder(binding.root) {

            fun bind(row: ActivityRows.Row.App) {
                val count = row.app.activities.size
                binding.headerLabel.text = row.app.label
                binding.headerCount.text =
                    binding.root.resources.getQuantityString(R.plurals.activity_count, count, count)
                binding.headerChevron.rotation = if (row.expanded) EXPANDED_CHEVRON else 0f
                bindIcon(binding.headerIcon, row.app, null)
                binding.root.setOnClickListener {
                    bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let(::toggle)
                }
            }
        }

        inner class ActivityHolder(private val binding: ItemWidgetPickerShortcutBinding) :
            RecyclerView.ViewHolder(binding.root) {

            fun bind(row: ActivityRows.Row.Activity) {
                binding.shortcutLabel.text = row.choice.label
                binding.shortcutSubtitle.text =
                    ActivityCatalog.shortClassName(row.app.packageName, row.choice.className)
                bindIcon(binding.shortcutIcon, row.app, row.choice)
                binding.root.setOnClickListener { picked(row.app, row.choice) }
            }
        }
    }

    companion object {
        private const val TYPE_APP = 0
        private const val TYPE_ACTIVITY = 1
        private const val EXPANDED_CHEVRON = 180f
        private const val ICON_CACHE_SIZE = 120
        // The window less the status bar, the sheet's handle, title and search
        // field, and a gap above the sheet; and the least the list gets, in landscape.
        private const val CHROME_DP = 266
        private const val MIN_LIST_DP = 240
        // Over the keyboard: the gap left above the sheet, and the least the
        // list keeps.
        private const val KEYBOARD_GAP_DP = 24
        private const val MIN_TYPING_LIST_DP = 120
        // The drawer's track: its text colour at Launcher3's 30/255.
        private const val FAST_SCROLL_TRACK_ALPHA = 30

        // One background thread for the picker's icons, in the order the
        // rows asked for them.
        private val iconLoader: java.util.concurrent.ExecutorService by lazy {
            java.util.concurrent.Executors.newSingleThreadExecutor { task ->
                Thread(task, "activity-icons").apply { isDaemon = true }
            }
        }
        private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

        fun show(activity: ComponentActivity, icons: WidgetPickerIcons, onPick: (ActivityPick) -> Unit) =
            ActivitiesPickerSheet(activity, icons, onPick).show()
    }
}
