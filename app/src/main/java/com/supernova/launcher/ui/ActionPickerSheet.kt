package com.supernova.launcher.ui

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.withStyledAttributes
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.supernova.launcher.R
import com.supernova.launcher.data.LaunchableApp
import com.supernova.launcher.gesture.GestureAction
import com.supernova.launcher.ui.settings.EnumLabels

// What the action picker hands back. A Legacy pick still has to run the
// app's own shortcut screen (ACTION_CREATE_SHORTCUT) for the intent it makes,
// which needs an activity result, so that part is the caller's.
sealed class ActionPick {
    data object None : ActionPick()
    data class Action(val action: GestureAction, val label: String) : ActionPick()
    data class Page(val index: Int, val label: String) : ActionPick()
    data class App(val app: LaunchableApp) : ActionPick()
    data class Shortcut(val app: LaunchableApp, val shortcutId: String, val label: String) : ActionPick()
    data class Legacy(val component: ComponentName, val label: String) : ActionPick()
}

// The action picker for gestures and icon swipes: three tabs, each a list
// with icons - the launcher's own actions, every app (hidden ones too; an app
// kept out of the drawer is exactly the one a gesture should reach), and the
// apps' shortcut screens (a direct dial, a Maps route, a Gmail label).
class ActionPickerSheet private constructor(
    private val context: Context,
    private val viewModel: LauncherViewModel,
    private val actions: List<GestureAction>,
    private val onPick: (ActionPick) -> Unit
) {

    private class Row(val icon: Drawable?, val label: CharSequence, val onClick: () -> Unit)

    private val sheet = Sheets.open(context)
    private val adapter = RowAdapter(context.getColor(R.color.textPrimary))

    private fun picked(pick: ActionPick) {
        sheet.dismiss()
        onPick(pick)
    }

    private fun show(title: CharSequence) {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, context.dp(BOTTOM_PADDING_DP))
        }
        LayoutInflater.from(context).inflate(R.layout.sheet_handle, root, true)
        root.addView(TextView(context, null, 0, R.style.SheetTitle).apply { text = title })
        val tabs = TabLayout(context).apply {
            tabMode = TabLayout.MODE_FIXED
            tabGravity = TabLayout.GRAVITY_FILL
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setTabTextColors(context.getColor(R.color.textSecondary), context.getColor(R.color.textPrimary))
            setSelectedTabIndicatorColor(context.getColor(R.color.accent))
            addTab(newTab().setText(R.string.app_name))
            addTab(newTab().setText(R.string.picker_tab_apps))
            addTab(newTab().setText(R.string.picker_tab_shortcuts))
        }
        root.addView(tabs)
        val list = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@ActionPickerSheet.adapter
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(LIST_HEIGHT_DP)))
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                adapter.rows = rowsFor(tab.position)
                list.scrollToPosition(0)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        adapter.rows = rowsFor(0)
        sheet.setContentView(root)
        sheet.show()
    }

    private fun rowsFor(tab: Int): List<Row> = when (tab) {
        0 -> launcherRows()
        1 -> appRows()
        else -> shortcutRows()
    }

    private fun launcherRows(): List<Row> {
        val none = Row(null, context.getString(R.string.swipe_none)) { picked(ActionPick.None) }
        return listOf(none) + actions.map { action ->
            val label = context.getString(EnumLabels.of(action))
            // The same shaped tile the action gets as a home icon.
            val tile = AppCompatResources.getDrawable(context, ActionIcons.of(action))
                ?.let { viewModel.appCatalog().renderThemedDrawable(it) ?: it }
            Row(tile, label) {
                if (action == GestureAction.JUMP_TO_PAGE) {
                    pickPage { index, pageLabel -> picked(ActionPick.Page(index, pageLabel)) }
                } else {
                    picked(ActionPick.Action(action, label))
                }
            }
        }
    }

    private fun appRows(): List<Row> =
        viewModel.appsForPicking().sortedBy { it.label.lowercase() }.map { app ->
            Row(viewModel.appCatalog().iconFor(app), app.label) { pickAppOrShortcut(app) }
        }

    // The app itself, or one of its own shortcuts when it has any.
    private fun pickAppOrShortcut(app: LaunchableApp) {
        val shortcuts = viewModel.shortcutsFor(app.packageName, app.userSerial)
        if (shortcuts.isEmpty()) {
            picked(ActionPick.App(app))
            return
        }
        val names = shortcuts.map { (it.shortLabel ?: it.longLabel ?: it.id).toString() }
        MaterialAlertDialogBuilder(context)
            .setTitle(app.label)
            .setItems((listOf(app.label) + names).toTypedArray()) { _, which ->
                if (which == 0) {
                    picked(ActionPick.App(app))
                } else {
                    val label = "${app.label}: ${names[which - 1]}"
                    picked(ActionPick.Shortcut(app, shortcuts[which - 1].id, label))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun shortcutRows(): List<Row> {
        val pm = context.packageManager
        val screens = runCatching { pm.queryIntentActivities(Intent(Intent.ACTION_CREATE_SHORTCUT), 0) }
            .getOrDefault(emptyList())
            .filter { it.activityInfo.exported && it.activityInfo.packageName != context.packageName }
        return screens
            .map { info -> info to runCatching { info.loadLabel(pm).toString() }.getOrDefault(info.activityInfo.name) }
            .sortedBy { (_, label) -> label.lowercase() }
            .map { (info, label) ->
                val component = ComponentName(info.activityInfo.packageName, info.activityInfo.name)
                Row(runCatching { info.loadIcon(pm) }.getOrNull(), label) {
                    picked(ActionPick.Legacy(component, label))
                }
            }
    }

    // Jump to page: one row per home page, 0-based.
    private fun pickPage(onPage: (Int, String) -> Unit) {
        val pageCount = viewModel.state.value.model.pageCount.coerceAtLeast(1)
        val labels = (1..pageCount).map { context.getString(R.string.page_number, it) }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.action_jump_to_page)
            .setItems(labels.toTypedArray()) { _, which -> onPage(which, labels[which]) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private class RowAdapter(private val textColor: Int) : RecyclerView.Adapter<RowAdapter.Holder>() {

        // A tab switch replaces every row at once.
        @SuppressLint("NotifyDataSetChanged")
        var rows: List<Row> = emptyList()
            set(value) {
                field = value
                notifyDataSetChanged()
            }

        class Holder(val root: LinearLayout, val icon: ImageView, val label: TextView) : RecyclerView.ViewHolder(root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val context = parent.context
            val icon = ImageView(context).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            val label = TextView(context).apply {
                textSize = LABEL_SP
                setTextColor(textColor)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val root = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = context.dp(ROW_MIN_HEIGHT_DP)
                val gutter = context.dp(ROW_GUTTER_DP)
                val inset = context.dp(ROW_INSET_DP)
                setPadding(gutter, inset, gutter, inset)
                context.withStyledAttributes(attrs = intArrayOf(android.R.attr.selectableItemBackground)) {
                    background = getDrawable(0)
                }
                val side = context.dp(ICON_DP)
                addView(icon, LinearLayout.LayoutParams(side, side))
                addView(
                    label,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = context.dp(LABEL_GAP_DP)
                    }
                )
            }
            root.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            return Holder(root, icon, label)
        }

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = rows[position]
            holder.label.text = row.label
            holder.icon.setImageDrawable(row.icon)
            holder.root.setOnClickListener { row.onClick() }
        }
    }

    companion object {
        private const val LIST_HEIGHT_DP = 440
        private const val BOTTOM_PADDING_DP = 8
        private const val ROW_MIN_HEIGHT_DP = 56
        private const val ROW_GUTTER_DP = 24
        private const val ROW_INSET_DP = 6
        private const val ICON_DP = 40
        private const val LABEL_GAP_DP = 20
        private const val LABEL_SP = 16f

        fun show(
            context: Context,
            viewModel: LauncherViewModel,
            title: CharSequence,
            actions: List<GestureAction>,
            onPick: (ActionPick) -> Unit
        ) = ActionPickerSheet(context, viewModel, actions, onPick).show(title)
    }
}
