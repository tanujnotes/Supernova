package com.supernova.launcher.data

import com.supernova.launcher.gesture.ActionShortcut

// "Reset custom icons": every icon the user changed one app, folder or
// shortcut at a time goes back to what the global icon settings draw. For an
// app that is the pack icon or photo, and the shape and style picked for it;
// for a folder or a launcher action, its pack icon or photo; for a shortcut,
// the icon, shape and style picked in its Edit. Names, hidden labels and swipe actions are not
// icons and stay. A shortcut's own iconBase64 is the only icon it has, not a
// custom one, so it stays too.
object CustomIcons {

    data class Count(val apps: Int, val folders: Int, val shortcuts: Int = 0) {
        val total: Int get() = apps + folders + shortcuts
    }

    fun count(model: LauncherModel): Count = Count(
        apps = model.appOverrides.values.count { it.hasCustomIcon() },
        folders = model.items.count { it.itemType == ItemType.FOLDER && it.hasOwnIcon() },
        shortcuts = model.items.count { it.isAction() && it.hasOwnIcon() || it.shortcutEdit?.hasCustomIcon() == true }
    )

    fun reset(model: LauncherModel): LauncherModel = model.copy(
        appOverrides = model.appOverrides
            .mapValues { (_, override) -> override.withoutIcon() }
            .filterValues { !it.isEmpty() },
        items = model.items.map { item ->
            when {
                (item.itemType == ItemType.FOLDER || item.isAction()) && item.hasOwnIcon() ->
                    item.copy(customIconPack = null, customIconName = null, iconBase64 = null, iconRaw = false)
                item.shortcutEdit?.hasCustomIcon() == true ->
                    item.copy(shortcutEdit = item.shortcutEdit.withoutIcon().takeUnless { it.isEmpty() })
                else -> item
            }
        }
    )

    private fun AppOverride.hasCustomIcon(): Boolean =
        iconPackPackage != null || iconDrawableName != null || iconBase64 != null ||
            iconShape != null || iconVariant != null

    private fun AppOverride.withoutIcon(): AppOverride = copy(
        iconPackPackage = null, iconDrawableName = null, iconBase64 = null,
        iconShape = null, iconVariant = null
    )

    private fun LauncherItem.isAction(): Boolean = ActionShortcut.decode(intentUri) != null

    private fun LauncherItem.hasOwnIcon(): Boolean =
        customIconPack != null || customIconName != null || !iconBase64.isNullOrEmpty()
}
