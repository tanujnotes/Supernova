package com.supernova.launcher.data.imports

import com.supernova.launcher.data.DrawerGroup
import com.supernova.launcher.data.DrawerGroupType
import com.supernova.launcher.data.TabFolders

// Another launcher's drawer tabs and folders as Supernova's drawer groups:
// each holding the apps of its own that are on this phone, a tab holding
// its folders as it holds apps. A folder left with no app is left out; a
// tab stays, as one made empty would.
internal object DrawerGroupConverter {

    // The stored groups with the backup's in place: one of the same name is
    // replaced, and the smart folders go, which would sort the backup's apps
    // a second time; every other stays.
    fun merged(groups: List<ForeignGroup>, stored: Set<String>, phone: LayoutConverter.Phone): Set<String> {
        val made = convert(groups, phone)
        val names = made.map { it.name }.toSet()
        val kept = stored.filterNot { encoded ->
            DrawerGroup.decode(encoded)?.let { it.smart || it.name in names } == true
        }
        return kept.toSet() + made.map { it.encode() }
    }

    fun convert(groups: List<ForeignGroup>, phone: LayoutConverter.Phone): List<DrawerGroup> {
        val apps = groups.associate { group -> group.id to group.apps.mapNotNull { key(it, phone) }.distinct() }
        val folders = ordered(groups.filter { it.folder && apps[it.id].orEmpty().isNotEmpty() })
        val tabs = ordered(groups.filterNot { it.folder })
        // Each group under an id of its own: names may repeat, as there.
        val folderIds = folders.associate { it.id to DrawerGroup.newId() }
        val madeFolders = folders.mapIndexed { order, folder ->
            DrawerGroup(
                name = name(folder.title, FOLDER),
                members = apps[folder.id].orEmpty().toSet(),
                order = order,
                type = DrawerGroupType.FOLDER,
                keepInMain = folder.keepInMain,
                iconBase64 = folder.icon?.let(phone.encodeIcon),
                id = folderIds.getValue(folder.id)
            )
        }
        val madeTabs = tabs.mapIndexed { order, tab ->
            val held = tab.folders.mapNotNull { folderIds[it] }.map(TabFolders::keyOf)
            DrawerGroup(
                name = name(tab.title, TAB),
                members = (apps[tab.id].orEmpty() + held).toSet(),
                order = order,
                type = DrawerGroupType.TAB,
                keepInMain = tab.keepInMain,
                id = DrawerGroup.newId()
            )
        }
        return madeFolders + madeTabs
    }

    // The launcher's order, ties as the backup lists them.
    private fun ordered(groups: List<ForeignGroup>): List<ForeignGroup> =
        groups.withIndex().sortedWith(compareBy({ it.value.order }, { it.index })).map { it.value }

    // An app's member key, "serial/package/class", for an app on this phone.
    private fun key(app: ForeignApp, phone: LayoutConverter.Phone): String? {
        val cls = app.takeIf { phone.installed(it.packageName, it.userSerial) }
            ?.let { phone.activityFor(it.packageName, it.className, it.userSerial) }
        val key = cls?.let { "${app.userSerial}/${app.packageName}/$it" }
        // A key is stored between ',' and '|'.
        return key?.takeIf { k -> k.none { it == ',' || it == '|' } }
    }

    // A group's name: no '|' (the storage separator), never blank.
    private fun name(title: String, fallback: String): String = title.replace('|', '/').trim().ifEmpty { fallback }

    private const val FOLDER = "Folder"
    private const val TAB = "Tab"
}
