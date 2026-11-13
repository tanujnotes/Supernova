package com.supernova.launcher.ui.settings.groups

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.GridLayout
import android.widget.Toast
import androidx.core.view.isVisible
import com.supernova.launcher.R
import com.supernova.launcher.data.DrawerGroup
import com.supernova.launcher.data.DrawerGroupType
import com.supernova.launcher.data.LabelOrder
import com.supernova.launcher.data.Pref
import com.supernova.launcher.data.TabFolders
import com.supernova.launcher.databinding.ItemGroupAppBinding
import com.supernova.launcher.databinding.ItemSettingToggleBinding
import com.supernova.launcher.databinding.PageGroupEditBinding
import com.supernova.launcher.ui.LauncherViewModel
import com.supernova.launcher.ui.dp

// One drawer group's page: its name, tab or folder, a folder's icon, its
// apps, and its two choices. Every control commits as it changes and the page
// is bound again from what was stored, so nothing here holds a value of its
// own; the name commits when its field is left (Done, a tap elsewhere, Back).
class GroupEditPage(
    private val binding: PageGroupEditBinding,
    private val viewModel: LauncherViewModel,
    private val icons: GroupIcons,
    private val actions: Actions
) {
    // What the page hands back to the pane that shows it.
    class Actions(
        // The group goes by a new name from here on.
        val renamed: (String) -> Unit,
        val selectApps: (DrawerGroup) -> Unit,
        val editIcon: (DrawerGroup) -> Unit,
        val delete: (DrawerGroup) -> Unit
    )

    private var group: DrawerGroup? = null

    // True while a bind sets the controls, so that is not taken for a tap.
    private var syncing = false

    // What the Apps grid shows, to leave it alone when nothing changed.
    private var shownCells: List<Cell>? = null

    private class Cell(val label: String, val icon: () -> android.graphics.drawable.Drawable?) {
        override fun equals(other: Any?): Boolean = other is Cell && other.label == label
        override fun hashCode(): Int = label.hashCode()
    }

    var cardColor: Int = 0
        set(value) {
            field = value
            val tint = ColorStateList.valueOf(value)
            listOf(binding.groupMainCard, binding.groupAppsCard, binding.groupOptionsCard, binding.groupDelete)
                .forEach { it.backgroundTintList = tint }
        }

    init {
        // Press ripples keep to the cards' rounded corners.
        listOf(binding.groupMainCard, binding.groupAppsCard, binding.groupOptionsCard, binding.groupDelete)
            .forEach { it.clipToOutline = true }
        wireName()
        wireControls()
    }

    private fun wireName() {
        val field = binding.groupNameField
        field.setOnEditorActionListener { view, actionId, _ ->
            val done = actionId == EditorInfo.IME_ACTION_DONE
            if (done) {
                view.clearFocus()
                view.context.getSystemService(InputMethodManager::class.java)
                    ?.hideSoftInputFromWindow(view.windowToken, 0)
            }
            done
        }
        field.setOnFocusChangeListener { _, focused -> if (!focused) commitName() }
    }

    private fun wireControls() {
        binding.groupType.addOnButtonCheckedListener { _, checkedId, checked ->
            if (checked && !syncing) {
                val type = if (checkedId == R.id.groupTypeFolder) DrawerGroupType.FOLDER else DrawerGroupType.TAB
                named()?.let { setType(it, type) }
            }
        }
        binding.groupIconButton.setOnClickListener { named()?.let(actions.editIcon) }
        binding.groupApps.setOnClickListener { named()?.let(actions.selectApps) }
        binding.groupSelectApps.setOnClickListener { named()?.let(actions.selectApps) }
        binding.groupDelete.setOnClickListener { named()?.let(actions.delete) }
        wireToggle(binding.groupKeep, R.string.group_keep_in_main, R.string.group_keep_in_main_summary) { on ->
            named()?.let { viewModel.setGroupKeepInMain(it, on) }
        }
        wireToggle(binding.groupDefault, R.string.drawer_default_tab, R.string.group_default_tab_summary) { on ->
            named()?.let { viewModel.set(Pref.drawerDefaultTab, if (on) it.name else "") }
        }
    }

    // The group for a control to act on, a name still in the field saved
    // first: the name is how the edit after it finds the group.
    private fun named(): DrawerGroup? {
        commitName()
        return group
    }

    // A settings checkbox row, as the list pages bind theirs.
    private fun wireToggle(row: ItemSettingToggleBinding, title: Int, summary: Int, set: (Boolean) -> Unit) {
        row.settingTitle.setText(title)
        row.settingSubtitle.setText(summary)
        row.settingSubtitle.visibility = View.VISIBLE
        row.settingSwitch.setOnCheckedChangeListener { _, checked -> if (!syncing) set(checked) }
        row.root.setOnClickListener { row.settingSwitch.toggle() }
    }

    // A tab turned into a folder is no tab to open the drawer on.
    private fun setType(current: DrawerGroup, type: DrawerGroupType) {
        viewModel.setGroupType(current, type)
        val opensOnIt = viewModel.state.value.prefs.drawerDefaultTab == current.name
        if (type == DrawerGroupType.FOLDER && opensOnIt) viewModel.set(Pref.drawerDefaultTab, "")
    }

    fun bind(next: DrawerGroup) {
        syncing = true
        val field = binding.groupNameField
        // A name being typed is left alone; another group's, or one saved
        // elsewhere, replaces what the field shows.
        val another = group?.name != next.name
        if ((another || !field.hasFocus()) && field.text?.toString() != next.name) field.setText(next.name)
        group = next
        val tab = next.type == DrawerGroupType.TAB
        binding.groupType.check(if (tab) R.id.groupTypeTab else R.id.groupTypeFolder)
        binding.groupTypeHint.setText(if (tab) R.string.group_type_tab_hint else R.string.group_type_folder_hint)
        binding.groupIconRow.isVisible = !tab
        if (!tab) binding.groupIconPreview.setImageDrawable(icons.folder(next))
        bindApps(next)
        binding.groupKeep.settingSwitch.isChecked = !viewModel.leavesMainList(next)
        binding.groupDefault.root.isVisible = tab
        binding.groupDefaultDivider.isVisible = tab
        binding.groupDefault.settingSwitch.isChecked = viewModel.state.value.prefs.drawerDefaultTab == next.name
        binding.groupDelete.setText(if (tab) R.string.delete_tab else R.string.delete_folder_group)
        syncing = false
    }

    // The group's apps in a grid, after the drawer folders a tab holds; past
    // three rows the last cell counts the rest.
    private fun bindApps(next: DrawerGroup) {
        val folders = if (next.type == DrawerGroupType.TAB) {
            TabFolders.inTab(next, viewModel.drawerFolderGroups())
        } else {
            emptyList()
        }
        val apps = LabelOrder.sorted(viewModel.appsInGroup(next)) { it.label }
        val cells = folders.map { folder -> Cell(folder.name) { icons.folder(folder) } } +
            apps.map { app -> Cell(app.label) { icons.app(app) } }
        binding.groupAppsCount.text = if (cells.isEmpty()) null else cells.size.toString()
        binding.groupApps.isVisible = cells.isNotEmpty()
        binding.groupAppsEmpty.isVisible = cells.isEmpty()
        if (cells == shownCells) return
        shownCells = cells
        val context = binding.root.context
        val inflater = LayoutInflater.from(context)
        val more = cells.size - MAX_CELLS + 1
        val shown = if (cells.size > MAX_CELLS) {
            cells.take(MAX_CELLS - 1) + Cell(context.getString(R.string.group_more_apps, more)) {
                androidx.appcompat.content.res.AppCompatResources
                    .getDrawable(context, R.drawable.bg_settings_tile_outline)
            }
        } else {
            cells
        }
        binding.groupApps.removeAllViews()
        // Blank cells fill the last row out: a column with nothing in it
        // would close up, and two apps would spread across the card.
        val blanks = (COLUMNS - shown.size % COLUMNS) % COLUMNS
        (shown + List(blanks) { null }).forEach { cell ->
            val view = ItemGroupAppBinding.inflate(inflater, binding.groupApps, false)
            if (cell == null) {
                view.root.visibility = View.INVISIBLE
            } else {
                view.appIcon.setImageDrawable(cell.icon())
                view.appLabel.text = cell.label
            }
            // Equal columns, whatever the names' widths.
            val column = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            val params = GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), column).apply {
                width = 0
                bottomMargin = context.dp(CELL_GAP_DP)
            }
            binding.groupApps.addView(view.root, params)
        }
    }

    // The typed name stored. An empty one, or one another group has, puts the
    // old name back in the field.
    fun commitName() {
        val current = group ?: return
        val typed = binding.groupNameField.text?.toString().orEmpty().trim()
        val name = if (typed.isEmpty()) current.name else viewModel.renameGroup(current, typed)
        if (name == null) {
            Toast.makeText(binding.root.context, R.string.group_name_taken, Toast.LENGTH_SHORT).show()
        }
        val kept = name ?: current.name
        if (binding.groupNameField.text?.toString() != kept) binding.groupNameField.setText(kept)
        if (kept != current.name) {
            group = current.copy(name = kept)
            actions.renamed(kept)
        }
    }

    private companion object {
        // Three rows of four.
        const val COLUMNS = 4
        const val MAX_CELLS = 12
        const val CELL_GAP_DP = 12
    }
}
