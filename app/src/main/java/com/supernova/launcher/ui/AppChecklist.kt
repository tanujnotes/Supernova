package com.supernova.launcher.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.CheckedTextView
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.supernova.launcher.R
import com.supernova.launcher.data.LaunchableApp
import com.supernova.launcher.search.AppMatcher

// Every app checklist (Select apps for home, dock and drawer folders, a
// drawer group's apps, Hidden apps, Badged apps): a search field over the
// apps, the ticked ones first, then a divider and the rest. The order is set
// as the list opens, so a tick doesn't move its row. checked is indexed as
// apps is, and holds every tick, filtered out of view or not.
class AppChecklist(
    private val context: Context,
    private val apps: List<LaunchableApp>,
    private val checked: BooleanArray,
    private val iconFor: (LaunchableApp) -> Drawable?
) {
    private val inFolder = apps.indices.filter { checked[it] }
    private val others = apps.indices.filterNot { checked[it] }
    // Indices into apps; DIVIDER between the two groups.
    private var rows: List<Int> = arrange(null)
    private val adapter = RowAdapter()

    val view: View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val search = EditText(context).apply {
            hint = context.getString(R.string.search_apps)
            isSingleLine = true
            // The outlined box draws no inner space of its own for a plain
            // EditText: without it the text sits on the border.
            val padH = context.dp(FIELD_PAD_H_DP)
            val padV = context.dp(FIELD_PAD_V_DP)
            setPadding(padH, padV, padH, padV)
            doAfterTextChanged { text ->
                rows = arrange(text?.toString()?.takeIf { it.isNotBlank() })
                adapter.notifyDataSetChanged()
            }
        }
        // DialogField brings the dialog's own 24dp sides.
        addView(
            DialogField.wrap(search),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = context.dp(BELOW_FIELD_DP)
            }
        )
        val maxHeight = (context.resources.displayMetrics.heightPixels * LIST_SHARE).toInt()
        val list = BoundedListView(context, maxHeight).apply {
            adapter = this@AppChecklist.adapter
            divider = null
            setOnItemClickListener { _, _, position, _ ->
                val index = rows[position]
                if (index != DIVIDER) {
                    checked[index] = !checked[index]
                    this@AppChecklist.adapter.notifyDataSetChanged()
                }
            }
        }
        // Weighted, so the keyboard squeezes the list rather than cutting
        // off its bottom.
        addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    // In a dialog with a Save button. Ticks are kept until Save, which hands
    // them over; a tap outside or back drops them.
    fun show(title: CharSequence, onSave: (BooleanArray) -> Unit): AlertDialog? {
        if (apps.isEmpty()) return null
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(view)
            .setBackgroundInsetTop(context.dp(INSET_V_DP))
            .setBackgroundInsetBottom(context.dp(INSET_V_DP))
            .setPositiveButton(R.string.save) { _, _ -> onSave(checked) }
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.show()
        OpenDialogs.track(dialog)
        return dialog
    }

    // Matched across all apps at once, so a typo match in one group drops
    // out when the other has a real one.
    private fun arrange(query: String?): List<Int> {
        val matched = query?.let { AppMatcher.filter(apps.indices.toList(), it) { i -> apps[i].label }.toHashSet() }
        val first = if (matched == null) inFolder else inFolder.filter { it in matched }
        val rest = if (matched == null) others else others.filter { it in matched }
        return if (first.isNotEmpty() && rest.isNotEmpty()) first + DIVIDER + rest else first + rest
    }

    private inner class RowAdapter : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = rows[position].toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (rows[position] == DIVIDER) 1 else 0
        override fun isEnabled(position: Int) = rows[position] != DIVIDER

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val index = rows[position]
            if (index == DIVIDER) {
                return convertView
                    ?: LayoutInflater.from(context).inflate(R.layout.row_folder_check_divider, parent, false)
            }
            val row = convertView ?: LayoutInflater.from(context).inflate(R.layout.row_folder_check, parent, false)
            val app = apps[index]
            row.findViewById<ImageView>(R.id.folderCheckIcon).setImageDrawable(iconFor(app))
            row.findViewById<CheckedTextView>(R.id.folderCheckLabel).apply {
                text = app.label
                isChecked = checked[index]
                // A row reused for another app shows its tick at once: the
                // box's own animation would play the old app's untick.
                if (tag != index) jumpDrawablesToCurrentState()
                tag = index
            }
            return row
        }
    }

    // Takes its full share of the screen while there is room, and whatever
    // is left once the keyboard is up.
    private class BoundedListView(context: Context, private val maxHeight: Int) : ListView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val size = MeasureSpec.getSize(heightMeasureSpec)
            val height = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
                maxHeight
            } else {
                minOf(maxHeight, size)
            }
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        }
    }

    private companion object {
        const val DIVIDER = -1
        const val LIST_SHARE = 0.55f
        const val BELOW_FIELD_DP = 8
        const val FIELD_PAD_H_DP = 16
        const val FIELD_PAD_V_DP = 14
        // The dialog's own 80dp leaves a band above and below the card
        // once the keyboard shrinks the window.
        const val INSET_V_DP = 24
    }
}
