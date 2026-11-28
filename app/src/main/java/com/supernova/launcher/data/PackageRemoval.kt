package com.supernova.launcher.data

// An uninstalled app's items go. A folder that lost members to it
// gives way as one an app was dragged out of does: one app left
// takes its place, none and it goes. Only a real uninstall comes
// here - an update or an app out of reach for a while never does.
internal object PackageRemoval {
    fun applied(
        current: LauncherModel,
        packageName: String,
        userSerial: Long,
        ownUserSerial: Long
    ): LauncherModel {
        val removed = current.items.filter { item ->
            when (item.itemType) {
                ItemType.APP,
                ItemType.SHORTCUT -> item.packageName == packageName && item.userSerial == userSerial
                // So only the launcher's own user losing the app takes
                // its widgets: the work copy of an app going leaves the
                // personal widget alone.
                ItemType.WIDGET -> userSerial == ownUserSerial &&
                    item.widgetProvider?.substringBefore('/') == packageName
                ItemType.FOLDER -> false
            }
        }
        if (removed.isEmpty()) return current
        val removedIds = removed.mapTo(HashSet()) { it.id }
        val touchedFolders = removed.mapNotNullTo(LinkedHashSet()) { it.folderId }

        var next = current.copy(
            items = current.items.filterNot { it.id in removedIds },
            // Overrides for the removed app would otherwise accrete forever.
            appOverrides = current.appOverrides.filterKeys {
                !it.startsWith("$userSerial/$packageName/")
            }
        )
        // Innermost first: a folder inside another that gives way changes
        // what the outer one holds (#369).
        touchedFolders.sortedByDescending { NestedFolders.holders(next.items, it).size }
            .forEach { next = LauncherRepository.withSparseFolderDissolved(next, it) }
        // Any other folder found empty goes too, as it always has, and so
        // does one that held nothing but such folders.
        return next.copy(items = NestedFolders.withEmptyFoldersRemoved(next.items))
    }
}
