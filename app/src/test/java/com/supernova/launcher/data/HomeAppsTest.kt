package com.supernova.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeAppsTest {

    private fun app(name: String, userSerial: Long = 0L) = LaunchableApp(
        packageName = "pkg.$name",
        className = "Activity$name",
        label = name,
        userSerial = userSerial
    )

    private fun item(id: String, app: LaunchableApp, container: ContainerType, page: Int = 0) = LauncherItem(
        id = id,
        itemType = ItemType.APP,
        container = container,
        page = page,
        packageName = app.packageName,
        className = app.className,
        userSerial = app.userSerial
    )

    @Test
    fun removesUncheckedTopLevelAppsAndPreservesFolderMembers() {
        val unchecked = app("unchecked")
        val folderApp = app("folder")
        val folder = LauncherItem(id = "folder-item", itemType = ItemType.FOLDER)
        val member = item("folder-member", folderApp, ContainerType.FOLDER).copy(folderId = folder.id)
        val outsidePicker = item("outside", app("outside"), ContainerType.WORKSPACE)
        val model = LauncherModel(
            items = listOf(
                item("workspace", unchecked, ContainerType.WORKSPACE),
                item("dock", unchecked, ContainerType.DOCK),
                folder,
                member,
                outsidePicker
            )
        )

        val result = LauncherRepository.withHomeApps(
            model,
            available = listOf(unchecked, folderApp),
            selected = emptyList(),
            cols = 4,
            rows = 4
        )

        assertFalse(result.items.any { it.id == "workspace" || it.id == "dock" })
        assertTrue(result.items.any { it.id == member.id })
        assertTrue(result.items.any { it.id == folder.id })
        assertTrue(result.items.any { it.id == outsidePicker.id })
    }

    @Test
    fun addsSelectedAppsWithoutMovingExistingDockApps() {
        val dockApp = app("dock")
        val newApp = app("new")
        val dockItem = item("dock-item", dockApp, ContainerType.DOCK)
        val blocker = LauncherItem(id = "blocker", itemType = ItemType.WIDGET, cellX = 0, cellY = 0)
        val model = LauncherModel(items = listOf(dockItem, blocker))

        val result = LauncherRepository.withHomeApps(
            model,
            available = listOf(dockApp, newApp),
            selected = listOf(dockApp, newApp),
            cols = 2,
            rows = 2,
            createId = { "added" }
        )

        assertTrue(result.items.any { it.id == dockItem.id && it.container == ContainerType.DOCK })
        val added = result.items.single { it.id == "added" }
        assertEquals(ContainerType.WORKSPACE, added.container)
        assertNotEquals(blocker.cellX to blocker.cellY, added.cellX to added.cellY)
        assertEquals(1, result.pageCount)
    }

    @Test
    fun createsAnotherPageWhenTheWorkspaceIsFull() {
        val existing = app("existing")
        val selected = app("selected")
        val model = LauncherModel(
            items = listOf(item("full", existing, ContainerType.WORKSPACE)),
            pageCount = 1
        )

        val result = LauncherRepository.withHomeApps(
            model,
            available = listOf(existing, selected),
            selected = listOf(existing, selected),
            cols = 1,
            rows = 1,
            createId = { "new-page-app" }
        )

        val added = result.items.single { it.id == "new-page-app" }
        assertEquals(1, added.page)
        assertEquals(2, result.pageCount)
    }

    @Test
    fun placesMultipleNewAppsOnTheSameNewPage() {
        val existing = app("existing")
        val blocker = app("blocker")
        val first = app("first")
        val second = app("second")
        val model = LauncherModel(
            items = listOf(
                item("full-0", existing, ContainerType.WORKSPACE),
                item("full-1", blocker, ContainerType.WORKSPACE).copy(cellX = 1)
            ),
            pageCount = 1
        )

        val result = LauncherRepository.withHomeApps(
            model,
            available = listOf(existing, blocker, first, second),
            selected = listOf(existing, blocker, first, second),
            cols = 2,
            rows = 1,
            createId = { "added-${++id}" }
        )

        val added = result.items.filter { it.id.startsWith("added-") }
        assertEquals(2, added.size)
        assertTrue(added.all { it.page == 1 })
        assertEquals(setOf(0 to 0, 1 to 0), added.map { it.cellX to it.cellY }.toSet())
        assertEquals(2, result.pageCount)
    }

    @Test
    fun reusesCellFreedByAnUncheckedApp() {
        val removed = app("removed")
        val selected = app("selected")
        val model = LauncherModel(items = listOf(item("removed-item", removed, ContainerType.WORKSPACE)))

        val result = LauncherRepository.withHomeApps(
            model,
            available = listOf(removed, selected),
            selected = listOf(selected),
            cols = 2,
            rows = 1,
            createId = { "selected-item" }
        )

        val added = result.items.single()
        assertEquals("selected-item", added.id)
        assertEquals(0 to 0, added.cellX to added.cellY)
    }

    private var id = 0
}
