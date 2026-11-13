package com.supernova.launcher.ui

import com.supernova.launcher.data.LaunchableApp
import org.junit.Assert.assertEquals
import org.junit.Test

// The drawer list's orders and the top row's.
class DrawerOrderTest {

    private fun app(label: String, installed: Long = 0L) = LaunchableApp(
        packageName = "pkg.$label", className = "Main", label = label, installTime = installed
    )

    private fun sorted(sort: DrawerSort, usage: AppUsage = usage()) = labels(DrawerOrder.sorted(apps, sort, usage))

    private fun row(sort: TopRowSort, usage: AppUsage, limit: Int) =
        labels(DrawerOrder.topRow(apps, sort, usage, limit))

    private val camera = app("Camera", installed = 30)
    private val maps = app("maps", installed = 10)
    private val bank = app("Bank", installed = 20)
    private val zoom = app("Zoom", installed = 20)
    private val apps = listOf(zoom, maps, camera, bank)

    private fun usage(counts: Map<LaunchableApp, Int> = emptyMap(), recent: List<LaunchableApp> = emptyList()) =
        AppUsage(counts.mapKeys { it.key.packageName }, recent.map { it.packageName }) { it.packageName }

    private fun labels(list: List<LaunchableApp>) = list.map { it.label }

    @Test
    fun nameOrderIgnoresCase() {
        assertEquals(listOf("Bank", "Camera", "maps", "Zoom"), sorted(DrawerSort.ALPHA))
        assertEquals(
            listOf("Zoom", "maps", "Camera", "Bank"),
            sorted(DrawerSort.ALPHA_REVERSE)
        )
    }

    @Test
    fun anUmlautSortsWithItsLetter() {
        val list = listOf(app("Zug"), app("Öffi"), app("Maps"))
        assertEquals(listOf("Maps", "Öffi", "Zug"), labels(DrawerOrder.sorted(list, DrawerSort.ALPHA, usage())))
    }

    @Test
    fun newestInstalledFirstThenByName() {
        assertEquals(
            listOf("Camera", "Bank", "Zoom", "maps"),
            sorted(DrawerSort.INSTALL_DATE)
        )
    }

    @Test
    fun mostUsedFirstThenTheRestByName() {
        val used = usage(counts = mapOf(zoom to 5, maps to 9))
        assertEquals(listOf("maps", "Zoom", "Bank", "Camera"), sorted(DrawerSort.MOST_USED, used))
    }

    // Apps past the recents go by launch count, then by name.
    @Test
    fun recentlyUsedFirstThenByCountThenByName() {
        val used = usage(counts = mapOf(camera to 3, zoom to 1, maps to 7), recent = listOf(zoom, maps))
        assertEquals(
            listOf("Zoom", "maps", "Camera", "Bank"),
            sorted(DrawerSort.RECENTLY_USED, used)
        )
    }

    @Test
    fun topRowTakesTheRecentAppsInRecencyOrder() {
        val used = usage(counts = mapOf(camera to 9), recent = listOf(zoom, bank, maps))
        assertEquals(listOf("Zoom", "Bank"), row(TopRowSort.RECENTLY_USED, used, limit = 2))
    }

    // Before anything was opened recently, the most opened stand in.
    @Test
    fun topRowFallsBackToMostUsedWithoutRecents() {
        val used = usage(counts = mapOf(camera to 2, maps to 5))
        assertEquals(listOf("maps", "Camera"), row(TopRowSort.RECENTLY_USED, used, limit = 4))
    }

    @Test
    fun topRowMostUsedSkipsNeverOpenedApps() {
        val used = usage(counts = mapOf(zoom to 1, camera to 4), recent = listOf(bank))
        assertEquals(listOf("Camera", "Zoom"), row(TopRowSort.MOST_USED, used, limit = 4))
    }

    @Test
    fun topRowNewestInstalled() {
        assertEquals(listOf("Camera", "Bank"), row(TopRowSort.INSTALL_DATE, usage(), limit = 2))
    }

    // Name order keeps the recent apps and sorts them, so the row holds still.
    @Test
    fun topRowByNameSortsTheRecentApps() {
        val used = usage(recent = listOf(zoom, maps, bank, camera))
        assertEquals(listOf("maps", "Zoom"), row(TopRowSort.ALPHA, used, limit = 2))
    }

    @Test
    fun anEmptyHistoryLeavesTheUsageRowEmpty() {
        assertEquals(emptyList<String>(), row(TopRowSort.RECENTLY_USED, usage(), limit = 4))
        assertEquals(emptyList<String>(), row(TopRowSort.MOST_USED, usage(), limit = 4))
    }
}
