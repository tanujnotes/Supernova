package com.supernova.launcher.data

import com.supernova.launcher.data.AppItemEdits.hasIcon
import com.supernova.launcher.data.AppItemEdits.withoutIcon
import com.supernova.launcher.gesture.ActionShortcut

// "Reset custom icons": every icon the user changed one app, folder or
// shortcut at a time goes back to what the global icon settings draw, for
// the kinds ticked in its dialog. For an app that is the pack icon or photo,
// and the shape and style picked for it; for a folder or a launcher action,
// its pack icon or photo; for a shortcut, the icon, shape and style picked in
// its Edit. Names, hidden labels, swipe actions and icon sizes are not icons
// and stay. A shortcut's own iconBase64 is the only icon it has, not a
// custom one, so it stays too.
object CustomIcons {

    // The dialog's ticks. APPS is the drawer's app icons, which placed
    // copies with no icon of their own draw too; PLACED_APPS is the icons
    // given to app copies on the home screen, the dock and in folders.
    // FOLDERS covers placed folders here and drawer folders and tabs in the
    // view model; SHORTCUTS covers launcher actions and pinned shortcuts
    // (contacts among them).
    enum class Kind { APPS, PLACED_APPS, FOLDERS, SHORTCUTS }

    data class Count(val apps: Int, val folders: Int, val shortcuts: Int = 0, val placedApps: Int = 0) {
        val total: Int get() = apps + placedApps + folders + shortcuts

        fun of(kind: Kind): Int = when (kind) {
            Kind.APPS -> apps
            Kind.PLACED_APPS -> placedApps
            Kind.FOLDERS -> folders
            Kind.SHORTCUTS -> shortcuts
        }
    }

    fun count(model: LauncherModel): Count = Count(
        apps = model.appOverrides.values.count { it.hasIcon() },
        folders = model.items.count { it.itemType == ItemType.FOLDER && it.hasOwnIcon() },
        shortcuts = model.items.count { it.isAction() && it.hasOwnIcon() || it.shortcutEdit?.hasIcon() == true },
        placedApps = model.items.count { it.itemType == ItemType.APP && it.appEdit?.hasIcon() == true }
    )

    fun reset(model: LauncherModel, kinds: Set<Kind> = Kind.entries.toSet()): LauncherModel = model.copy(
        appOverrides = if (Kind.APPS !in kinds) {
            model.appOverrides
        } else {
            model.appOverrides
                .mapValues { (_, override) -> override.withoutIcon() }
                .filterValues { !it.isEmpty() }
        },
        items = model.items.map { item ->
            when {
                item.itemType == ItemType.FOLDER && item.hasOwnIcon() && Kind.FOLDERS in kinds -> item.withoutOwnIcon()
                item.isAction() && item.hasOwnIcon() && Kind.SHORTCUTS in kinds -> item.withoutOwnIcon()
                item.shortcutEdit?.hasIcon() == true && Kind.SHORTCUTS in kinds ->
                    item.copy(shortcutEdit = item.shortcutEdit.withoutIcon().takeUnless { it.isEmpty() })
                item.appEdit?.hasIcon() == true && Kind.PLACED_APPS in kinds ->
                    item.copy(appEdit = item.appEdit.withoutIcon().takeUnless { it.isEmpty() })
                else -> item
            }
        }
    )

    private fun LauncherItem.isAction(): Boolean = ActionShortcut.decode(intentUri) != null

    private fun LauncherItem.withoutOwnIcon(): LauncherItem =
        copy(customIconPack = null, customIconName = null, iconBase64 = null, iconRaw = false)

    private fun LauncherItem.hasOwnIcon(): Boolean =
        customIconPack != null || customIconName != null || !iconBase64.isNullOrEmpty()
}
