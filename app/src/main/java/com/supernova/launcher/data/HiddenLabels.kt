package com.supernova.launcher.data

// "Reset hidden labels": every app and shortcut whose label was hidden in its
// own Edit shows it again. Only the hidden flag goes; names, icons, shapes,
// styles and swipe actions stay, as Reset custom icons leaves labels alone.
// Folders and launcher actions have no per-item hidden label.
object HiddenLabels {

    data class Count(val apps: Int, val shortcuts: Int) {
        val total: Int get() = apps + shortcuts
    }

    fun count(model: LauncherModel): Count = Count(
        apps = model.appOverrides.values.count { it.hideLabel },
        shortcuts = model.items.count { it.shortcutEdit?.hideLabel == true }
    )

    fun reset(model: LauncherModel): LauncherModel = model.copy(
        appOverrides = model.appOverrides
            .mapValues { (_, override) -> override.copy(hideLabel = false) }
            .filterValues { !it.isEmpty() },
        items = model.items.map { item ->
            val edit = item.shortcutEdit
            if (edit?.hideLabel == true) {
                item.copy(shortcutEdit = edit.copy(hideLabel = false).takeUnless { it.isEmpty() })
            } else {
                item
            }
        }
    )
}
