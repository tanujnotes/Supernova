package com.supernova.launcher.ui.settings.groups

import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.supernova.launcher.R
import com.supernova.launcher.data.DrawerGroup
import com.supernova.launcher.data.DrawerGroupType
import com.supernova.launcher.databinding.PaneDrawerGroupsBinding
import com.supernova.launcher.ui.GroupChecklist
import com.supernova.launcher.ui.ItemIcons
import com.supernova.launcher.ui.LauncherViewModel

// The Drawer groups page: the tabs and folders in two cards under a preview
// of the tab bar, each group opening on a page of its own. It stands in for
// the settings list inside SettingsActivity, which keeps the toolbar, Back
// and the page's place in the settings; this holds everything under them.
//
// Nothing here keeps a copy of a group. Every edit is written at once and
// the page is drawn again from what was stored (refresh, on each state the
// activity collects), so it cannot drift from the drawer.
class DrawerGroupsPane(
    private val activity: AppCompatActivity,
    private val container: ViewGroup,
    private val viewModel: LauncherViewModel,
    private val itemIcons: ItemIcons,
    // The toolbar's title follows the page: a group's name on its own page.
    private val onTitleChanged: () -> Unit,
    // A drawer folder's Edit sheet, for its icon. The sheet can rename the
    // folder too; the page then finds its group gone and shows the list,
    // with the folder under its new name.
    private val editFolderIcon: (DrawerGroup) -> Unit
) : GroupListAdapter.Listener {

    private val binding = PaneDrawerGroupsBinding.inflate(activity.layoutInflater, container, true)
    private val icons = GroupIcons(activity, viewModel, itemIcons)
    private val builder = GroupListBuilder(activity, viewModel)
    private val adapter = GroupListAdapter(icons, this)
    private val cards = GroupsCardDecoration(activity, adapter)
    private val editPage = GroupEditPage(binding.groupEdit, viewModel, icons, editActions())

    // A drag holds the list: a state arriving mid-drag would put the rows
    // back under the finger.
    private var dragging = false
    private val touchHelper = ItemTouchHelper(
        GroupReorder(adapter, onDragging = { dragging = it }) { type ->
            if (type == null) refresh() else viewModel.setGroupOrder(type, adapter.names(type))
        }
    )

    // The group whose page is open (null: the list), by its stored name.
    var editing: String? = null
        private set

    // A name just written, a new group's or a renamed one's, that the state
    // has yet to show: the page waits for it rather than taking the group
    // for deleted.
    private var awaiting: String? = null

    // A new group opens on its Select apps, once.
    private var pickAppsFor: String? = null

    var cardColor: Int = 0
        set(value) {
            field = value
            cards.cardColor = value
            adapter.cardColor = value
            editPage.cardColor = value
            binding.groupsList.invalidateItemDecorations()
        }

    init {
        binding.groupsList.layoutManager = LinearLayoutManager(activity)
        binding.groupsList.adapter = adapter
        binding.groupsList.addItemDecoration(cards)
        touchHelper.attachToRecyclerView(binding.groupsList)
    }

    // On the page, or off it. A group's page restored after a recreate comes
    // back by its name.
    fun show(visible: Boolean, group: String? = editing) {
        container.isVisible = visible
        if (!visible) return
        editing = group
        refresh()
    }

    fun refresh() {
        if (!container.isVisible || dragging) return
        val name = editing
        val group = name?.let { wanted -> viewModel.drawerGroups().firstOrNull { it.name == wanted } }
        val onGroup = binding.groupEdit.root.isVisible
        when {
            group != null -> showGroup(group)
            name != null && name == awaiting -> if (!onGroup) adapter.submit(builder.rows())
            else -> {
                // On the list, or the open group was deleted.
                editing = null
                binding.groupEdit.root.isVisible = false
                binding.groupsList.isVisible = true
                adapter.submit(builder.rows())
                if (onGroup) onTitleChanged()
            }
        }
    }

    private fun showGroup(group: DrawerGroup) {
        awaiting = null
        if (!binding.groupEdit.root.isVisible) {
            binding.groupEdit.root.scrollTo(0, 0)
            binding.groupEdit.root.isVisible = true
            binding.groupsList.isVisible = false
            // The page, not its name field, has focus as it opens.
            binding.groupEdit.groupEditContent.requestFocus()
        }
        editPage.bind(group)
        onTitleChanged()
        if (pickAppsFor == group.name) {
            pickAppsFor = null
            GroupChecklist(activity, viewModel, itemIcons).show(group, viewModel.visibleApps())
        }
    }

    // Back from a group's page to the list, its typed name saved on the
    // way; false on the list, which Back leaves as any settings page.
    fun handleBack(): Boolean {
        if (editing == null) return false
        editPage.commitName()
        activity.getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(binding.root.windowToken, 0)
        editing = null
        awaiting = null
        refresh()
        return true
    }

    override fun open(group: DrawerGroup) {
        editing = group.name
        refresh()
    }

    override fun add(type: DrawerGroupType) {
        NewGroupSheet.show(activity, viewModel, type) { created ->
            editing = created
            awaiting = created
            pickAppsFor = created
            refresh()
        }
    }

    override fun startDrag(holder: RecyclerView.ViewHolder) = touchHelper.startDrag(holder)

    override fun step(group: DrawerGroup, delta: Int) = viewModel.moveGroup(group.name, delta)

    private fun editActions() = GroupEditPage.Actions(
        renamed = { name ->
            editing = name
            awaiting = name
            onTitleChanged()
        },
        selectApps = { group -> GroupChecklist(activity, viewModel, itemIcons).show(group, viewModel.visibleApps()) },
        editIcon = editFolderIcon,
        delete = ::delete
    )

    // Deleted at once, with Undo: the list as it stood goes with the
    // snackbar, to bring the group and its place in its tabs back.
    private fun delete(group: DrawerGroup) {
        val before = viewModel.drawerGroups()
        viewModel.removeGroup(group.name)
        editing = null
        awaiting = null
        refresh()
        Snackbar.make(container, activity.getString(R.string.group_deleted, group.name), Snackbar.LENGTH_LONG)
            .setAction(R.string.undo) { viewModel.restoreGroup(before, group.name) }
            .show()
    }
}
