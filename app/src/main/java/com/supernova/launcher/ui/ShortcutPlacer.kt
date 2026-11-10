package com.supernova.launcher.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.UserManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.supernova.launcher.R
import com.supernova.launcher.data.GalleryIconCodec
import com.supernova.launcher.data.LabelOrder
import com.supernova.launcher.data.LaunchableApp
import com.supernova.launcher.gesture.ActionShortcut
import com.supernova.launcher.runLogged
import com.supernova.launcher.ui.settings.EnumLabels

// A shortcut picked from the add sheet, placed as an icon on the first free
// cell of the page in view: one an app makes on its own shortcut screen, a
// launcher action, or any exported activity of an app. A field of the
// activity, so the shortcut screen's result is registered before it starts.
class ShortcutPlacer(
    private val activity: ComponentActivity,
    private val viewModel: () -> LauncherViewModel
) {
    private val createShortcutLauncher =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) result.data?.let(::handleCreateShortcutResult)
        }

    // A shortcut row from the add sheet: an app's CREATE_SHORTCUT flow, a
    // launcher action placed as an icon, or the any-activity picker.
    fun pick(target: ShortcutTarget) {
        when (target) {
            is ShortcutTarget.Create -> {
                val intent = Intent(Intent.ACTION_CREATE_SHORTCUT)
                    .setClassName(target.packageName, target.activityName)
                runLogged("open shortcut creator") { createShortcutLauncher.launch(intent) }
                    .onFailure { Toast.makeText(activity, R.string.launch_failed, Toast.LENGTH_SHORT).show() }
            }
            is ShortcutTarget.Action -> addIntentItemAtFreeCell(
                ActionShortcut.encode(target.action),
                activity.getString(EnumLabels.of(target.action)),
                iconBase64 = null
            )
            ShortcutTarget.Activities -> showActivitiesPicker()
        }
    }

    // Places an intent-backed item on the first free cell of the current page.
    private fun addIntentItemAtFreeCell(intentUri: String, label: String, iconBase64: String?) {
        val page = viewModel().state.value.currentPage
        val free = viewModel().firstFreeWorkspaceCell(page, 1, 1)
        if (free == null) {
            Toast.makeText(activity, R.string.no_room_on_page, Toast.LENGTH_SHORT).show()
            return
        }
        viewModel().addIntentItemToWorkspace(intentUri, label, iconBase64, page, free.first, free.second)
    }

    // Any exported activity as an icon. Pick an app, then one of its activities;
    // the launch intent is stored as an intentUri and the icon is snapshotted.
    private fun showActivitiesPicker() {
        val apps = LabelOrder.sorted(viewModel().visibleApps()) { it.label }
        if (apps.isEmpty()) return
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.add_category_activities)
            .setItems(apps.map { it.label }.toTypedArray()) { _, which ->
                showActivitiesForApp(apps[which])
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showActivitiesForApp(app: LaunchableApp) {
        val pm = activity.packageManager
        val activities = runCatching {
            pm.getPackageInfo(app.packageName, PackageManager.GET_ACTIVITIES)
                .activities.orEmpty()
                .filter { it.exported && it.isEnabled }
        }.getOrDefault(emptyList())
            .let { found -> LabelOrder.sorted(found) { it.loadLabel(pm) } }

        if (activities.isEmpty()) {
            Toast.makeText(activity, R.string.no_activities, Toast.LENGTH_SHORT).show()
            return
        }
        // Most activities inherit the app label; append the short class name
        // so multiple activities of one app are distinguishable.
        val names = activities.map { activity ->
            val activityLabel = activity.loadLabel(pm).toString()
            val shortClass = activity.name.substringAfterLast('.')
            if (activityLabel.isBlank() || activityLabel == app.label) {
                "${app.label}: $shortClass"
            } else {
                activityLabel
            }
        }.toTypedArray()
        MaterialAlertDialogBuilder(activity)
            .setTitle(app.label)
            .setItems(names) { _, which ->
                val activity = activities[which]
                val intent = Intent()
                    .setComponent(ComponentName(activity.packageName, activity.name))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val icon = runCatching { activity.loadIcon(pm) }.getOrNull()
                val icon64 = icon?.let { GalleryIconCodec.encode(it) }
                addIntentItemAtFreeCell(intent.toUri(Intent.URI_INTENT_SCHEME), names[which], icon64)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun handleCreateShortcutResult(data: Intent) {
        // Newer apps return a pinnable shortcut request; accepting it pins the
        // shortcut to us, so it launches reliably through startShortcut.
        val launcherApps = activity.getSystemService(LauncherApps::class.java)
        val pin = runCatching { launcherApps?.getPinItemRequest(data) }.getOrNull()
        if (pin != null && pin.isValid &&
            pin.requestType == LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT
        ) {
            val shortcut = pin.shortcutInfo
            if (shortcut != null && pin.accept()) {
                val label = (shortcut.shortLabel ?: shortcut.longLabel ?: shortcut.`package`).toString()
                val icon = runCatching {
                    launcherApps.getShortcutIconDrawable(shortcut, activity.resources.displayMetrics.densityDpi)
                }.getOrNull()
                val icon64 = icon?.let { GalleryIconCodec.encode(it) }
                val serial = activity.getSystemService(UserManager::class.java)
                    ?.getSerialNumberForUser(shortcut.userHandle) ?: 0L
                addPinnedShortcutAtFreeCell(
                    shortcut.`package`, shortcut.id, shortcut.activity?.className, serial, label, icon64
                )
            }
            return
        }

        // Legacy result: an intent to fire plus a name and an optional icon.
        @Suppress("DEPRECATION")
        val shortcutIntent = data.getParcelableExtra<Intent>(Intent.EXTRA_SHORTCUT_INTENT) ?: return
        val name = data.getStringExtra(Intent.EXTRA_SHORTCUT_NAME).orEmpty()
        addIntentItemAtFreeCell(
            shortcutIntent.toUri(Intent.URI_INTENT_SCHEME), name, extractLegacyShortcutIcon(data)
        )
    }

    private fun addPinnedShortcutAtFreeCell(
        packageName: String,
        shortcutId: String,
        className: String?,
        userSerial: Long,
        label: String,
        iconBase64: String?
    ) {
        val page = viewModel().state.value.currentPage
        val free = viewModel().firstFreeWorkspaceCell(page, 1, 1)
        if (free == null) {
            Toast.makeText(activity, R.string.no_room_on_page, Toast.LENGTH_SHORT).show()
            return
        }
        viewModel().addShortcutToWorkspace(
            LaunchableApp(packageName, className.orEmpty(), label, userSerial),
            shortcutId, label, page, free.first, free.second, iconBase64 = iconBase64, mergeFolders = false
        )
    }

    // The other app names its icon; there is no id of ours to look it up by.
    @android.annotation.SuppressLint("DiscouragedApi")
    @Suppress("DEPRECATION")
    private fun extractLegacyShortcutIcon(data: Intent): String? {
        data.getParcelableExtra<Bitmap>(Intent.EXTRA_SHORTCUT_ICON)?.let {
            return GalleryIconCodec.encode(it)
        }
        val iconRes = data.getParcelableExtra<Intent.ShortcutIconResource>(Intent.EXTRA_SHORTCUT_ICON_RESOURCE)
            ?: return null
        val drawable = runCatching {
            val res = activity.packageManager.getResourcesForApplication(iconRes.packageName)
            val id = res.getIdentifier(iconRes.resourceName, null, null)
            if (id != 0) androidx.core.content.res.ResourcesCompat.getDrawable(res, id, null) else null
        }.getOrNull() ?: return null
        return GalleryIconCodec.encode(drawable)
    }
}
