package com.supernova.launcher.data

// "Reset custom icons": every icon the user changed one app or folder
// at a time goes back to what the global icon settings draw. For an app that
// is the pack icon or photo, and the shape and style picked for it; for a
// folder, its pack icon or photo. Names, hidden labels and swipe actions are
// not icons and stay. A shortcut's own iconBase64 is the only icon it has,
// not a custom one, so only folders lose theirs.
object CustomIcons {

    data class Count(val apps: Int, val folders: Int) {
        val total: Int get() = apps + folders
    }

    fun count(model: LauncherModel): Count = Count(
        apps = model.appOverrides.values.count { it.hasCustomIcon() },
        folders = model.items.count { it.hasCustomIcon() }
    )

    fun reset(model: LauncherModel): LauncherModel = model.copy(
        appOverrides = model.appOverrides
            .mapValues { (_, override) -> override.withoutIcon() }
            .filterValues { !it.isEmpty() },
        items = model.items.map { item ->
            if (item.hasCustomIcon()) {
                item.copy(customIconPack = null, customIconName = null, iconBase64 = null)
            } else {
                item
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

    private fun LauncherItem.hasCustomIcon(): Boolean =
        itemType == ItemType.FOLDER &&
            (customIconPack != null || customIconName != null || !iconBase64.isNullOrEmpty())
}
