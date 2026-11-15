package com.supernova.launcher.ui.settings

import com.supernova.launcher.data.LauncherPrefs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsSearchIndexTest {

    private fun entry(label: String, row: SettingItem) = SettingsSearchIndex.Entry(
        sectionTitleRes = 1, row = row, sectionLabel = "Dock", itemLabel = label,
        haystack = "${label.lowercase()} dock"
    )

    private fun toggle(visibleIf: (LauncherPrefs) -> Boolean = { true }) =
        SettingItem.Toggle(2, get = { false }, set = {}, visibleIf = visibleIf)

    @Test
    fun aRowASettingHidesIsStillFoundWithThatSetting() {
        val dockOff = SettingItem.HiddenBy(3, SettingItem.HiddenBy.Needs.ON)
        val index = SettingsSearchIndex(listOf(entry("Dock pages", toggle().hiddenWhen { dockOff })))

        val hit = index.search("pages", LauncherPrefs()).single()

        assertEquals(dockOff, hit.hiddenBy(LauncherPrefs()))
    }

    @Test
    fun aRowNoSettingCanBringBackIsLeftOut() {
        val index = SettingsSearchIndex(
            listOf(entry("Themed icons", toggle(visibleIf = { false })), entry("Icon pack", toggle()))
        )

        assertEquals(listOf("Icon pack"), index.search("icon", LauncherPrefs()).map { it.itemLabel })
    }

    @Test
    fun aRowIsShownOnlyWhileNothingHidesIt() {
        val row = toggle()
        assertNull(row.hiddenBy(LauncherPrefs()))
        assertTrue(row.shownIn(LauncherPrefs()))

        row.hiddenWhen { SettingItem.HiddenBy(3, SettingItem.HiddenBy.Needs.OFF) }
        assertFalse(row.shownIn(LauncherPrefs()))
    }
}
