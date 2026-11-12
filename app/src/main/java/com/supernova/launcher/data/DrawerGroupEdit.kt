package com.supernova.launcher.data

// One group renamed and, optionally, its Keep apps in main app tab choice
// changed, as a single edit of the stored list: a tab's Edit dialog saves both
// at once, and two edits would leave a moment where one is lost (the second
// looks the group up by a name the first just changed).
object DrawerGroupEdit {

    // The list with the group called from renamed to `to` and, when keepInMain
    // is set, that choice stored; null, and nothing changed, when another group
    // already goes by `to`, since saving would replace that group. Any edit
    // makes a smart folder the user's: regenerating the smart ones would
    // otherwise put it back without the edit.
    fun apply(groups: List<DrawerGroup>, from: String, to: String, keepInMain: Boolean?): List<DrawerGroup>? {
        if (to != from && groups.any { it.name == to }) return null
        return groups.map {
            if (it.name == from) it.copy(name = to, keepInMain = keepInMain ?: it.keepInMain, smart = false) else it
        }
    }
}
