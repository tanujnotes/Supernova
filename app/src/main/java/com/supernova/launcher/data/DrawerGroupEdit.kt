package com.supernova.launcher.data

// One group renamed and, optionally, its Keep apps in main app tab choice
// changed, as a single edit of the stored list: a tab's Edit dialog saves both
// at once. The group is found by its id, which a rename leaves alone; any
// other group may already go by the new name.
object DrawerGroupEdit {

    // The list with the group [id] named `to` and, when keepInMain is set,
    // that choice stored. Any edit makes a smart folder the user's:
    // regenerating the smart ones would otherwise put it back without the
    // edit.
    fun apply(groups: List<DrawerGroup>, id: String, to: String, keepInMain: Boolean?): List<DrawerGroup> =
        groups.map {
            if (it.id == id) it.copy(name = to, keepInMain = keepInMain ?: it.keepInMain, smart = false) else it
        }
}
