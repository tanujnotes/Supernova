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
    // replaced, every other stays.
    fun merged(groups: List<ForeignGroup>, stored: Set<String>, phone: LayoutConverter.Phone): Set<String> {
        val made = convert(groups, phone)
        val names = made.map { it.name }.toSet()
        return stored.filterNot { DrawerGroup.decode(it)?.name in names }.toSet() + made.map { it.encode() }
    }

    fun convert(groups: List<ForeignGroup>, phone: LayoutConverter.Phone): List<DrawerGroup> {
        val apps = groups.associate { group -> group.id to group.apps.mapNotNull { key(it, phone) }.distinct() }
        val folders = ordered(groups.filter { it.folder && apps[it.id].orEmpty().isNotEmpty() })
        val tabs = ordered(groups.filterNot { it.folder })
        val names = Names()
        val folderNames = folders.associate { it.id to names.take(it.title, FOLDER) }
        val madeFolders = folders.mapIndexed { order, folder ->
            DrawerGroup(
                name = folderNames.getValue(folder.id),
                members = apps[folder.id].orEmpty().toSet(),
                order = order,
                type = DrawerGroupType.FOLDER,
                keepInMain = folder.keepInMain,
                iconBase64 = folder.icon?.let(phone.encodeIcon)
            )
        }
        val madeTabs = tabs.mapIndexed { order, tab ->
            val held = tab.folders.mapNotNull { folderNames[it] }.map(TabFolders::keyOf)
            DrawerGroup(
                name = names.take(tab.title, TAB),
                members = (apps[tab.id].orEmpty() + held).toSet(),
                order = order,
                type = DrawerGroupType.TAB,
                keepInMain = tab.keepInMain
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

    // Group names, unique as Supernova keeps them: no '|', never blank, a
    // second of a name numbered.
    private class Names {
        private val taken = HashSet<String>()

        fun take(title: String, fallback: String): String {
            val base = title.replace('|', '/').trim().ifEmpty { fallback }
            val name = generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base $it" }
                .first { it.lowercase() !in taken }
            taken += name.lowercase()
            return name
        }
    }

    private const val FOLDER = "Folder"
    private const val TAB = "Tab"
}
