package com.supernova.launcher

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Point
import android.view.accessibility.AccessibilityNodeInfo
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.supernova.launcher.data.AccessibleMoves
import com.supernova.launcher.data.ContainerType
import com.supernova.launcher.data.ItemType
import com.supernova.launcher.data.LauncherItem
import com.supernova.launcher.data.LauncherModel
import com.supernova.launcher.data.LauncherPrefs
import com.supernova.launcher.data.Pref
import com.supernova.launcher.data.WorkspaceGeometry
import com.supernova.launcher.data.supernovaStore
import com.supernova.launcher.widget.SupernovaWidgetHost
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeNotNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

// The edits a user makes to the home screen, made for real on a device: an
// icon dragged to a free cell, an icon moved by its screen reader action,
// and a backup restored over a changed layout.
//
// Unlike SmokeTest these change the layout and the settings. Each test
// starts by remembering the store that holds both, key for key, and ends by
// putting exactly that back, along with the automatic backups folder,
// whether it passed or not: the phone is left as it was found. Run them the
// way SmokeTest's header says, never with connectedDebugAndroidTest.
@RunWith(AndroidJUnit4::class)
class LayoutEditTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = instrumentation.targetContext
    private val appId = context.packageName
    private val graph = context.appGraph

    private lateinit var backup: String
    private lateinit var storeBefore: Preferences
    private lateinit var modelBefore: LauncherModel
    private lateinit var prefsBefore: LauncherPrefs
    private val autoBackups = File(context.filesDir, "auto_backups")
    private val autoBackupsKept = File(context.cacheDir, "layout-edit-test-auto-backups")

    @Before
    fun rememberTheLayout() {
        runBlocking {
            storeBefore = context.supernovaStore.data.first()
            prefsBefore = graph.preferences.snapshot()
            modelBefore = graph.repository.modelFlow.first()
            backup = graph.repository.exportBackup(prefsBefore)
        }
        autoBackupsKept.deleteRecursively()
        if (autoBackups.exists()) autoBackups.copyRecursively(autoBackupsKept, overwrite = true)

        // By class: this build needn't be the default home app.
        context.startActivity(
            Intent(context, LauncherActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        assertTrue("the home screen didn't draw", shows("workspacePager"))
        // The page is there before its icons are: a fresh process binds
        // them once they have been rendered.
        modelBefore.items.firstOrNull { it.container == ContainerType.WORKSPACE && it.label.isNotBlank() }
            ?.let { device.wait(Until.hasObject(By.desc(it.label)), WAIT_MS) }
        device.waitForIdle()
        // Started while already in front, the launcher takes this as the
        // home button pressed again, which may be set to open the drawer.
        if (device.hasObject(By.res(appId, "drawerSheet"))) {
            device.pressBack()
            device.wait(Until.gone(By.res(appId, "drawerSheet")), WAIT_MS)
        }
    }

    // The store as it was, key for key: not a backup import, which would
    // leave every setting stored where before only the changed ones were.
    @After
    fun putTheLayoutBack() {
        runBlocking {
            context.supernovaStore.edit { store ->
                store.clear()
                store += storeBefore
            }
            assertEquals("the layout wasn't restored", modelBefore, graph.repository.modelFlow.first())
            assertEquals("the settings weren't restored", prefsBefore, graph.preferences.snapshot())
        }
        autoBackups.deleteRecursively()
        if (autoBackupsKept.exists()) autoBackupsKept.copyRecursively(autoBackups, overwrite = true)
        autoBackupsKept.deleteRecursively()
    }

    @Test
    fun anIconDragsToAFreeCell() {
        assumeFalse("the desktop is locked", prefsBefore.desktopLocked)
        val cols = prefsBefore.gridCols
        val rows = prefsBefore.gridRows
        val page = modelBefore.defaultPage
        val icon = iconsOnPage(page).firstOrNull()
        val free = WorkspaceGeometry.findFreeCell(modelBefore, page, 1, 1, cols, rows)
        assumeNotNull("no icon to drag on the main page", icon)
        assumeNotNull("no free cell on the main page", free)
        val view = device.findObject(By.desc(icon!!.label))

        // The grid as the page lays it out, checked against where the icon
        // itself sits before anything is dragged by it.
        val pager = device.findObject(By.res(appId, "workspacePager")).visibleBounds
        val density = context.resources.displayMetrics.density
        val padH = (prefsBefore.desktopPaddingH * density).toInt()
        val padV = (prefsBefore.desktopPaddingV * density).toInt()
        val cellW = (pager.width() - padH * 2) / cols.toFloat()
        val cellH = (pager.height() - padV * 2) / rows.toFloat()
        fun onScreen(cellX: Float, cellY: Float) =
            Point((pager.left + padH + cellX * cellW).toInt(), (pager.top + padV + cellY * cellH).toInt())
        val expected = cellOf(icon).let { (cellX, cellY) -> onScreen(cellX + HALF, cellY + HALF) }
        val actual = view.visibleCenter
        assumeTrue(
            "the page's cells aren't where this test works them out to be",
            abs(actual.x - expected.x) < cellW / 2 && abs(actual.y - expected.y) < cellH / 2
        )

        view.drag(onScreen(free!!.first + HALF, free.second + HALF), DRAG_SPEED)

        val moved = awaitItem(icon.id) { it.page == page && sitsOn(it, free.first, free.second) }
        assertNotNull("the icon didn't land on the free cell ${free.first},${free.second}", moved)
        assertEquals(ContainerType.WORKSPACE, moved!!.container)
    }

    @Test
    fun aScreenReaderActionMovesAnIcon() {
        assumeFalse("the desktop is locked", prefsBefore.desktopLocked)
        val grid = AccessibleMoves.Grid(
            prefsBefore.gridCols,
            prefsBefore.gridRows,
            if (prefsBefore.dockEnabled) prefsBefore.dockCount * prefsBefore.dockPages else 0
        )
        val page = modelBefore.defaultPage
        // An icon with a free cell beside it, and the move that takes it there.
        val steps = listOf(
            AccessibleMoves.Move.RIGHT to R.string.a11y_move_right,
            AccessibleMoves.Move.LEFT to R.string.a11y_move_left,
            AccessibleMoves.Move.DOWN to R.string.a11y_move_down,
            AccessibleMoves.Move.UP to R.string.a11y_move_up
        )
        val choice = iconsOnPage(page).firstNotNullOfOrNull { icon ->
            steps.firstNotNullOfOrNull { (move, label) ->
                val target = AccessibleMoves.target(modelBefore, icon, move, grid, page)
                (target as? AccessibleMoves.Target.Cell)
                    ?.takeIf { move in AccessibleMoves.offered(modelBefore, icon, grid) }
                    ?.let { Triple(icon, label, it) }
            }
        }
        assumeNotNull("no icon on the main page has a free cell beside it", choice)
        val (icon, label, target) = choice!!

        // The way a screen reader reaches it: the icon's node, and the
        // action it lists by its label.
        val node = findNode(instrumentation.uiAutomation.rootInActiveWindow) { it.contentDescription == icon.label }
        assertNotNull("the icon has no accessibility node", node)
        val action = node!!.actionList.firstOrNull { it.label == context.getString(label) }
        assertNotNull("the icon doesn't offer \"${context.getString(label)}\"", action)
        assertTrue("the action wasn't taken", node.performAction(action!!.id))

        val moved = awaitItem(icon.id) { sitsOn(it, target.cellX, target.cellY) }
        assertNotNull("the icon didn't move to ${target.cellX},${target.cellY}", moved)
    }

    @Test
    fun aBackupRestoresTheLayoutAndSettingsItWasTakenFrom() = runBlocking {
        // A change to each half of what a backup holds.
        graph.repository.setAppOverride(PROBE_APP) { it.copy(customLabel = "layout edit test") }
        graph.preferences.set(Pref.dockLabels, !prefsBefore.dockLabels)
        assertNotEquals(modelBefore, graph.repository.modelFlow.first())
        assertNotEquals(prefsBefore, graph.preferences.snapshot())

        // Restored the way Settings restores one: apps that are gone and
        // widgets no longer bound are judged for real.
        val manager = AppWidgetManager.getInstance(context)
        val hostIds = SupernovaWidgetHost(context.applicationContext, SupernovaWidgetHost.HOST_ID).appWidgetIds.toSet()
        val result = graph.repository.importBackup(
            backup,
            graph.preferences.snapshot(),
            isInstalled = { pkg, serial -> graph.catalog.isInstalled(pkg, serial) },
            widgetBound = { id, provider ->
                id in hostIds && provider != null &&
                    manager.getAppWidgetInfo(id)?.provider?.flattenToString() == provider
            }
        )

        val restored = graph.repository.modelFlow.first()
        assertEquals(prefsBefore, graph.preferences.snapshot())
        assertEquals(null, restored.appOverrides[PROBE_APP])
        assertEquals(restored.items.size, result.restored)
        // With no app gone and no widget unbound since, which is the usual
        // case for a phone's own layout, it comes back whole.
        if (result.droppedMissingApps == 0 && result.widgetsNeedingRebind == 0) assertEquals(modelBefore, restored)
    }

    // The icons on the page that only one view on screen is called by.
    private fun iconsOnPage(page: Int): List<LauncherItem> = modelBefore.items.filter { item ->
        item.container == ContainerType.WORKSPACE && item.page == page &&
            (item.itemType == ItemType.APP || item.itemType == ItemType.SHORTCUT) &&
            item.label.isNotBlank() && device.findObjects(By.desc(item.label)).size == 1
    }

    // The cell an icon is shown in, by its top-left corner; whole numbers
    // for one sitting on the grid.
    private fun cellOf(item: LauncherItem): Pair<Float, Float> =
        WorkspaceGeometry.itemRect(item, prefsBefore.gridCols, prefsBefore.gridRows).let { it.left to it.top }

    private fun sitsOn(item: LauncherItem, cellX: Int, cellY: Int): Boolean =
        cellOf(item).let { (x, y) -> abs(x - cellX) < NEAR && abs(y - cellY) < NEAR }

    private fun awaitItem(id: String, landed: (LauncherItem) -> Boolean): LauncherItem? = runBlocking {
        repeat(POLLS) {
            val item = graph.repository.modelFlow.first().items.firstOrNull { it.id == id }
            if (item != null && landed(item)) return@runBlocking item
            delay(POLL_MS)
        }
        null
    }

    private fun findNode(
        root: AccessibilityNodeInfo?,
        matches: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        root ?: return null
        if (matches(root)) return root
        for (i in 0 until root.childCount) {
            findNode(root.getChild(i), matches)?.let { return it }
        }
        return null
    }

    private fun shows(viewId: String): Boolean =
        device.wait(Until.hasObject(By.res(appId, viewId)), WAIT_MS)

    private companion object {
        const val WAIT_MS = 5_000L
        const val POLL_MS = 100L
        const val POLLS = 50
        const val DRAG_SPEED = 1_500
        const val HALF = 0.5f

        // A free-positioned icon's cell is worked out from a fraction.
        const val NEAR = 0.05f

        // An override under a key no app has: it changes the layout document
        // without showing anywhere.
        const val PROBE_APP = "0/com.supernova.launcher.test.probe/Probe"
    }
}
