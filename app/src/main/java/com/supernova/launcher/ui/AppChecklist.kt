package com.supernova.launcher.ui

import android.content.Context
import androidx.core.content.edit
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
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
import com.supernova.launcher.search.AppMatcher

// Every app checklist (Select apps for home, dock and drawer folders, a
// drawer group's apps and folders, Hidden apps, Badged apps): a search field
// over the rows, the ticked ones first, then a divider and the rest. The
// order is set as the list opens, so a tick doesn't move its row. checked is
// indexed as apps is, and holds every tick, filtered out of view or not.
class AppChecklist<T>(
    private val context: Context,
    private val apps: List<T>,
    private val checked: BooleanArray,
    private val iconFor: (T) -> Drawable?,
    private val labelOf: (T) -> String,
    // A drawer group's list: whether an unticked row already sits in another
    // group. A switch over the list hides those (#257); null for no switch.
    private val placedElsewhere: ((T) -> Boolean)? = null
) {
    private val inFolder = apps.indices.filter { checked[it] }
    private val others = apps.indices.filterNot { checked[it] }
    private val placed: Set<Int> = placedElsewhere?.let { test -> others.filter { test(apps[it]) }.toSet() }.orEmpty()
    private var hidePlaced = placed.isNotEmpty() && readHidePlaced()
    private var query: String? = null
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
                query = text?.toString()?.takeIf { it.isNotBlank() }
                rows = arrange(query)
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
        if (placed.isNotEmpty()) addView(placedSwitch())
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
    fun show(title: CharSequence, onSave: (BooleanArray) -> Unit): AlertDialog? =
        open(title, renamable = false) { ticks, _ -> onSave(ticks) }

    // The same, with the title a field: a drawer group's name, typed over
    // in place and handed to Save with the ticks (trimmed, maybe empty).
    fun showRenamable(title: String, onSave: (BooleanArray, String) -> Unit): AlertDialog? =
        open(title, renamable = true, onSave)

    private fun open(title: CharSequence, renamable: Boolean, onSave: (BooleanArray, String) -> Unit): AlertDialog? {
        if (apps.isEmpty()) return null
        val builder = MaterialAlertDialogBuilder(context)
        val titleField = if (renamable) titleField(builder.context, title) else null
        if (titleField != null) {
            builder.setCustomTitle(titleField.parent as View)
        } else {
            builder.setTitle(title)
        }
        val dialog = builder
            .setView(view)
            .setBackgroundInsetTop(context.dp(INSET_V_DP))
            .setBackgroundInsetBottom(context.dp(INSET_V_DP))
            .setPositiveButton(R.string.save) { _, _ ->
                onSave(checked, titleField?.text?.toString()?.trim() ?: title.toString())
            }
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.show()
        OpenDialogs.track(dialog)
        return dialog
    }

    // The title as a one-line field in the title's own type, on the line it
    // would sit on; its underline is what says it can be typed over. The
    // keyboard waits for a tap: the search field is what most opens are for.
    private fun titleField(themed: Context, title: CharSequence): EditText {
        val field = EditText(themed).apply {
            setTextAppearance(R.style.TextAppearance_Supernova_DialogTitle)
            setText(title)
            hint = themed.getString(R.string.group_name)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_DONE
            LabelLimit.apply(this)
            setPadding(0, context.dp(TITLE_FIELD_PAD_V_DP), 0, context.dp(TITLE_FIELD_PAD_V_DP))
        }
        LinearLayout(themed).apply {
            setPadding(context.dp(TITLE_PAD_H_DP), context.dp(TITLE_PAD_TOP_DP), context.dp(TITLE_PAD_H_DP), 0)
            addView(field, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        return field
    }

    // "Hide apps in other groups", on by default and remembered.
    private fun placedSwitch(): View = androidx.appcompat.widget.SwitchCompat(context).apply {
        text = context.getString(R.string.checklist_hide_placed)
        isChecked = hidePlaced
        val padH = context.dp(SWITCH_PAD_H_DP)
        setPadding(padH, 0, padH, context.dp(BELOW_FIELD_DP))
        setOnCheckedChangeListener { _, on ->
            hidePlaced = on
            writeHidePlaced(on)
            rows = arrange(query)
            adapter.notifyDataSetChanged()
        }
    }

    private fun prefs() = context.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE)

    private fun readHidePlaced(): Boolean =
        runCatching { prefs().getBoolean(KEY_HIDE_PLACED, true) }.getOrDefault(true)

    private fun writeHidePlaced(on: Boolean) {
        runCatching { prefs().edit { putBoolean(KEY_HIDE_PLACED, on) } }
    }

    // Matched across all apps at once, so a typo match in one group drops
    // out when the other has a real one.
    private fun arrange(query: String?): List<Int> {
        val matched = query?.let { AppMatcher.filter(apps.indices.toList(), it) { i -> labelOf(apps[i]) }.toHashSet() }
        val first = if (matched == null) inFolder else inFolder.filter { it in matched }
        val shown = if (hidePlaced) others.filterNot { it in placed } else others
        val rest = if (matched == null) shown else shown.filter { it in matched }
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
                text = labelOf(app)
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
            // Its full width, always: the dialog measures it "at most" on a
            // keyboard's resize, which the rows kept (each label shrank to
            // its text, the box beside it instead of at the end).
            val width = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.AT_MOST) {
                MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY)
            } else {
                widthMeasureSpec
            }
            super.onMeasure(width, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        }
    }

    private companion object {
        const val DIVIDER = -1
        const val SWITCH_PAD_H_DP = 24
        const val UI_PREFS = "checklist_ui_state"
        const val KEY_HIDE_PLACED = "hide_placed"
        const val LIST_SHARE = 0.55f
        const val BELOW_FIELD_DP = 8
        const val FIELD_PAD_H_DP = 16
        const val FIELD_PAD_V_DP = 14
        // The dialog's own 80dp leaves a band above and below the card
        // once the keyboard shrinks the window.
        const val INSET_V_DP = 24
        // The editable title's place: the dialog title's 24dp sides, a
        // little less on top for the field's own room under the text.
        const val TITLE_PAD_H_DP = 24
        const val TITLE_PAD_TOP_DP = 16
        const val TITLE_FIELD_PAD_V_DP = 8
    }
}
