package com.supernova.launcher.ui

import android.content.IntentSender
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import com.supernova.launcher.R
import com.supernova.launcher.data.ItemType
import com.supernova.launcher.data.LabelOrder
import com.supernova.launcher.data.LaunchableApp
import com.supernova.launcher.data.LauncherItem
import com.supernova.launcher.runLogged
import com.supernova.launcher.ui.theme.Styles
import com.supernova.launcher.ui.theme.SurfaceId
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// The Private space action (#75): the space's apps in a folder card over the
// home screen, styled by the folder settings, with Lock on its title row and
// Install apps / Private space settings in its menu. A locked space asks for
// its own unlock first, through the system's prompt, and the card opens once
// the space has unlocked and the home screen is in front again.
class PrivateSpaceCard(
    private val activity: ComponentActivity,
    private val viewModel: LauncherViewModel,
    private val folderPopup: FolderPopup,
    private val host: Host
) {
    interface Host {
        // Where a card with no icon of its own grows from (a gesture's).
        val homeAnchor: View

        // Nothing else is open over the home screen: a card waiting on an
        // unlock gives way to whatever the user opened meanwhile.
        fun homeIsClear(): Boolean

        // Another folder card open (the action's icon can sit in a folder)
        // closes before this one opens: one card at a time.
        fun closeOtherCards()

        fun launchApp(app: LaunchableApp)

        // An app held in the card: the drawer's menu for it, and no drag,
        // as nothing of the private space goes home.
        fun holdApp(view: View, app: LaunchableApp, card: PopupWindow?)

        fun decorate(icon: Drawable?, item: LauncherItem): Drawable?

        // The title row's menu, opened modal from its button.
        fun showMenu(anchor: View, rows: (SupernovaPopupMenu) -> Unit)

        fun onShown(card: PopupWindow, adapter: FolderMemberAdapter)

        fun onClosed(card: PopupWindow?)
    }

    private var unlockWait: Job? = null

    // The card showing, and its apps by member id: one card at a time.
    private var card: PopupWindow? = null
    private var appsById: Map<String, LaunchableApp> = emptyMap()

    fun open(anchor: View?) {
        cancelPending()
        val space = viewModel.privateSpace()
        if (space == null) {
            Toast.makeText(activity, R.string.private_space_missing, Toast.LENGTH_SHORT).show()
            return
        }
        if (!space.locked) {
            show(anchor ?: host.homeAnchor)
            return
        }
        // The icon is found again by its item: the unlock lists the space's
        // apps, and the page can be bound afresh under the one tapped.
        val itemId = anchor?.getTag(R.id.tag_item_id) as? String
        viewModel.setPrivateSpaceLocked(false)
        unlockWait = activity.lifecycleScope.launch {
            withTimeoutOrNull(UNLOCK_WAIT_MS) {
                viewModel.state.first { it.privateSpace?.locked == false }
            } ?: return@launch
            // The apps come with the rescan the unlock sets off, a moment
            // behind the space's own state.
            withTimeoutOrNull(APPS_WAIT_MS) { viewModel.state.first { s -> s.apps.any { it.privateSpace } } }
            // The prompt is an activity of its own: the card waits for the
            // home screen to be back in front.
            activity.lifecycle.withResumed {
                unlockWait = null
                if (host.homeIsClear()) {
                    val target = itemId?.let { findItemView(host.homeAnchor.rootView, it) }
                        ?: anchor?.takeIf { it.isAttachedToWindow }
                        ?: host.homeAnchor
                    show(target)
                }
            }
        }
    }

    // A home press or the screen going off calls off a card still waiting
    // on its unlock.
    fun cancelPending() {
        unlockWait?.cancel()
        unlockWait = null
    }

    private fun show(anchor: View) {
        host.closeOtherCards()
        val prefs = viewModel.state.value.prefs
        val catalog = viewModel.appCatalog()
        appsById = privateAppsById()
        val adapter = newAdapter()
        val hasMenu = catalog.privateSpaceInstall() != null || catalog.privateSpaceSettings() != null
        val surface = Styles.resolve(activity, prefs).of(SurfaceId.FOLDER)
        var self: PopupWindow? = null
        var watch: Job? = null
        val shown = folderPopup.show(
            anchor = anchor,
            title = activity.getString(R.string.action_private_space),
            members = items(appsById),
            adapter = adapter,
            columnsOverride = prefs.folderColumns,
            sortAz = prefs.folderSortAz,
            mode = prefs.folderMode,
            scroll = prefs.folderScroll,
            surfaceColor = surface.surface,
            cornerRadiiDp = surface.cornerRadiiDp,
            // Whatever the folder setting: the title row carries the
            // space's Lock and menu.
            showTitle = true,
            showCount = prefs.folderShowCount,
            shadow = prefs.folderShadow,
            animate = Animations.enabled(prefs),
            speed = Animations.factor(prefs),
            titleEditable = false,
            actionLabel = activity.getString(R.string.private_space_lock),
            onAction = {
                viewModel.setPrivateSpaceLocked(true)
                self?.dismiss()
            },
            onRename = {},
            onMenu = if (hasMenu) { button -> host.showMenu(button, ::addMenuRows) } else null,
            onDismiss = {
                watch?.cancel()
                host.onClosed(self)
                if (card === self) card = null
            }
        )
        self = shown
        card = shown
        host.onShown(shown, adapter)
        watch = keepCurrent(shown, adapter)
    }

    private fun newAdapter(): FolderMemberAdapter {
        val prefs = viewModel.state.value.prefs
        return FolderMemberAdapter(
            appCatalog = viewModel.appCatalog(),
            showLabels = prefs.folderLabels,
            labelPrefs = prefs,
            iconScale = if (prefs.folderMatchDesktop) prefs.iconScale else prefs.folderIconScale,
            onClick = { item ->
                appsById[item.id]?.let { app ->
                    if (viewModel.state.value.prefs.folderCloseOnLaunch) card?.dismiss()
                    host.launchApp(app)
                }
            },
            onLongPress = { view, item -> appsById[item.id]?.let { host.holdApp(view, it, card) } }
        ).apply { decorateIcon = host::decorate }
    }

    // Kept to the space while open: an app installed or uninstalled shows
    // or goes, and the card closes when the space locks or goes.
    private fun keepCurrent(shown: PopupWindow, adapter: FolderMemberAdapter): Job = activity.lifecycleScope.launch {
        viewModel.state.map { it.privateSpace to it.apps }.distinctUntilChanged().collect { (space, _) ->
            if (space == null || space.locked) {
                shown.dismiss()
                return@collect
            }
            val now = privateAppsById()
            if (now.keys.toList() != appsById.keys.toList()) {
                appsById = now
                adapter.submit(items(now))
            }
        }
    }

    private fun addMenuRows(menu: SupernovaPopupMenu) {
        val catalog = viewModel.appCatalog()
        catalog.privateSpaceInstall()?.let { sender ->
            menu.addRow(activity.getString(R.string.private_space_install_apps), R.drawable.ic_menu_add) {
                start(sender)
            }
        }
        catalog.privateSpaceSettings()?.let { sender ->
            menu.addRow(activity.getString(R.string.private_space_open_settings), R.drawable.ic_tab_settings) {
                start(sender)
            }
        }
    }

    private fun start(sender: IntentSender) {
        runLogged("start private space sender") { activity.startIntentSender(sender, null, 0, 0, 0) }
    }

    // In the order the card shows them: the drawer's, or A-Z with the
    // folder setting.
    private fun privateAppsById(): Map<String, LaunchableApp> {
        val apps = viewModel.privateApps()
        val ordered = if (viewModel.state.value.prefs.folderSortAz) LabelOrder.sorted(apps) { it.label } else apps
        return ordered.associateBy { "private/${it.userSerial}/${it.packageName}/${it.className}" }
    }

    private fun items(apps: Map<String, LaunchableApp>): List<LauncherItem> = apps.map { (id, app) ->
        LauncherItem(
            id = id,
            itemType = ItemType.APP,
            label = app.label,
            packageName = app.packageName,
            className = app.className,
            userSerial = app.userSerial
        )
    }

    // The home icon or dock icon bound to a desktop item, if it is showing.
    private fun findItemView(root: View, itemId: String): View? = when {
        root.getTag(R.id.tag_item_id) == itemId && root.isShown -> root
        root is ViewGroup ->
            (0 until root.childCount).firstNotNullOfOrNull { findItemView(root.getChildAt(it), itemId) }
        else -> null
    }

    private companion object {
        // Long enough to type a PIN or pattern, short enough that a prompt
        // walked away from doesn't open the card much later.
        const val UNLOCK_WAIT_MS = 60_000L
        const val APPS_WAIT_MS = 2_000L
    }
}
