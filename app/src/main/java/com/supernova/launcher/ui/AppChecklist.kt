package com.supernova.launcher.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckedTextView
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import androidx.core.widget.doAfterTextChanged
import com.supernova.launcher.R
import com.supernova.launcher.data.LaunchableApp
import com.supernova.launcher.search.AppMatcher

// Select apps' list, for home, dock and drawer folders: a search field over
// the apps, the folder's own apps first, then a divider and the rest. The
// order is set as the list opens, so a tick doesn't move its row. checked is
// indexed as apps is, and holds every tick, filtered out of view or not.
class AppChecklist(
    private val context: Context,
    private val apps: List<LaunchableApp>,
    private val checked: BooleanArray,
    private val iconFor: (LaunchableApp) -> Drawable?,
    private val onToggle: (index: Int) -> Unit
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
        val list = ListView(context).apply {
            adapter = this@AppChecklist.adapter
            divider = null
            setOnItemClickListener { _, _, position, _ ->
                val index = rows[position]
                if (index != DIVIDER) {
                    checked[index] = !checked[index]
                    this@AppChecklist.adapter.notifyDataSetChanged()
                    onToggle(index)
                }
            }
        }
        // The dialog doesn't bound a custom view's height: the list takes a
        // share of the screen and scrolls within it.
        val height = (context.resources.displayMetrics.heightPixels * LIST_SHARE).toInt()
        addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height))
    }

    private fun arrange(query: String?): List<Int> {
        fun shown(group: List<Int>) =
            if (query == null) group else group.filter { AppMatcher.matches(apps[it].label, query) }
        val first = shown(inFolder)
        val rest = shown(others)
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
            }
            return row
        }
    }

    private companion object {
        const val DIVIDER = -1
        const val LIST_SHARE = 0.55f
        const val BELOW_FIELD_DP = 8
        const val FIELD_PAD_H_DP = 16
        const val FIELD_PAD_V_DP = 14
    }
}
