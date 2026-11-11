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
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.core.view.updateLayoutParams
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// The activity an Activities pick lands as: started by its component, and
// labelled as the picker showed it.
data class ActivityPick(val component: ComponentName, val label: String)

// The Activities picker (the widget sheet's Activities row): every
// installed app, launcher icon or not, as the widget sheet's collapsible
// cards with its count of activities; a card opens into the activities, each
// with its icon, its name and its class. A tap on one hands it back.
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
        binding.activitiesFrame.updateLayoutParams {
            height = (window - activity.dp(CHROME_DP)).coerceAtLeast(activity.dp(MIN_LIST_DP))
        }
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
            binding.activitiesEmpty.visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE
            binding.activitiesFastScroller.visibility = if (apps.isEmpty()) View.GONE else View.VISIBLE
            adapter.submit(apps)
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

        private var apps: List<ActivityApp> = emptyList()
        private val expanded = mutableSetOf<String>()
        var rows: List<ActivityRows.Row> = emptyList()
            private set

        // The whole list arrives at once, after the load.
        @SuppressLint("NotifyDataSetChanged")
        fun submit(list: List<ActivityApp>) {
            apps = list
            rows = ActivityRows.flatten(apps, expanded)
            notifyDataSetChanged()
        }

        // Opens or closes one card, its activities sliding in or out under it.
        fun toggle(position: Int) {
            val row = rows.getOrNull(position) as? ActivityRows.Row.App ?: return
            val count = row.app.activities.size
            if (!expanded.remove(row.app.packageName)) expanded += row.app.packageName
            rows = ActivityRows.flatten(apps, expanded)
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
        // The window less the status bar, the sheet's handle and title, and a
        // gap above the sheet; and the least the list gets, in landscape.
        private const val CHROME_DP = 200
        private const val MIN_LIST_DP = 240
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
