package com.supernova.launcher.ui.settings

import com.supernova.launcher.data.LauncherPrefs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsSearchIndexTest {

    private fun entry(label: String, row: SettingItem, description: String = "") = SettingsSearchIndex.Entry(
        sectionTitleRes = 1, row = row, sectionLabel = "Dock", itemLabel = label,
        haystack = "${label.lowercase()} dock", description = description
    )

    private fun toggle(visibleIf: (LauncherPrefs) -> Boolean = { true }) =
        SettingItem.Toggle(2, get = { false }, set = {}, visibleIf = visibleIf)

    @Test
    fun aRowASettingHidesIsStillFoundWithThatSetting() {
        val dockOff = SettingItem.HiddenBy(3, SettingItem.HiddenBy.Needs.ON)
        val index = SettingsSearchIndex(listOf(entry("Dock pages", toggle().hiddenWhen { dockOff })))

        val hit = index.search("pages", LauncherPrefs()).single().entry

        assertEquals(dockOff, hit.hiddenBy(LauncherPrefs()))
    }

    @Test
    fun aRowNoSettingCanBringBackIsLeftOut() {
        val index = SettingsSearchIndex(
            listOf(entry("Themed icons", toggle(visibleIf = { false })), entry("Icon pack", toggle()))
        )

        assertEquals(listOf("Icon pack"), index.search("icon", LauncherPrefs()).map { it.entry.itemLabel })
    }

    @Test
    fun aRowIsShownOnlyWhileNothingHidesIt() {
        val row = toggle()
        assertNull(row.hiddenBy(LauncherPrefs()))
        assertTrue(row.shownIn(LauncherPrefs()))

        row.hiddenWhen { SettingItem.HiddenBy(3, SettingItem.HiddenBy.Needs.OFF) }
        assertFalse(row.shownIn(LauncherPrefs()))
    }

    private val shadow = entry("Show shadow", toggle(), "A soft shade behind the status bar and the dock")
    private val shadeless = entry("Shade colour", toggle())

    @Test
    fun aWordOnlyInTheDescriptionFindsTheRow() {
        val hit = SettingsSearchIndex(listOf(shadow)).search("shade", LauncherPrefs()).single()

        assertEquals("Show shadow", hit.entry.itemLabel)
        assertTrue(hit.byDescription)
    }

    @Test
    fun titleHitsComeBeforeDescriptionHits() {
        val hits = SettingsSearchIndex(listOf(shadow, shadeless)).search("shade", LauncherPrefs())

        assertEquals(listOf("Shade colour", "Show shadow"), hits.map { it.entry.itemLabel })
        assertEquals(listOf(false, true), hits.map { it.byDescription })
    }

    @Test
    fun aTitleHitIsNotMarkedAsADescriptionHit() {
        assertFalse(SettingsSearchIndex(listOf(shadow)).search("shadow", LauncherPrefs()).single().byDescription)
    }

    @Test
    fun descriptionWordsMatchFromTheirStart() {
        val index = SettingsSearchIndex(listOf(shadow))

        assertEquals(1, index.search("stat", LauncherPrefs()).size)
        assertTrue(index.search("hade", LauncherPrefs()).isEmpty())
    }

    @Test
    fun shortAndFillerWordsDoNotMatchDescriptions() {
        val index = SettingsSearchIndex(listOf(shadow))

        assertTrue(index.search("the", LauncherPrefs()).isEmpty())
        assertTrue(index.search("be", LauncherPrefs()).isEmpty())
    }

    @Test
    fun wordsCanMixTitleAndDescription() {
        val index = SettingsSearchIndex(listOf(shadow))

        assertEquals(1, index.search("dock shade", LauncherPrefs()).size)
        assertTrue(index.search("dock lamp", LauncherPrefs()).isEmpty())
    }

    @Test
    fun theLimitKeepsTitleHits() {
        val hits = SettingsSearchIndex(listOf(shadow, shadeless)).search("shade", LauncherPrefs(), limit = 1)

        assertEquals(listOf("Shade colour"), hits.map { it.entry.itemLabel })
    }
}
