package com.supernova.launcher

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.supernova.launcher.data.BackupPayload
import com.supernova.launcher.gesture.Gesture
import com.supernova.launcher.gesture.GestureAction
import com.supernova.launcher.gesture.GestureDefaults
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

// The few things every session does, on a real device: the home screen
// draws, the drawer opens and closes, Settings opens, and the layout as it
// stands exports to a backup that reads back. They only look: nothing here
// adds, moves or removes anything, or writes a setting.
//
// WARNING: don't run these with connectedDebugAndroidTest on a phone you
// use. Gradle uninstalls the app when the run ends, and the home layout,
// every setting and the widget bindings go with it. Install both APKs and
// run them in place instead:
//
//   ./gradlew :app:installDebug :app:installDebugAndroidTest
//   adb shell am instrument -w \
//       com.supernova.launcher.debug.test/androidx.test.runner.AndroidJUnitRunner
@RunWith(AndroidJUnit4::class)
class SmokeTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = instrumentation.targetContext
    private val appId = context.packageName

    @Before
    fun showHome() {
        // By class: this build needn't be the default home app.
        context.startActivity(
            Intent(context, LauncherActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        assertTrue("the home screen didn't draw", shows("workspacePager"))
        device.waitForIdle()
        // Started while already in front, the launcher takes this as the
        // home button pressed again, which may be set to open the drawer.
        if (device.hasObject(By.res(appId, "drawerSheet"))) {
            device.pressBack()
            assertTrue("the drawer didn't close", gone("drawerSheet"))
        }
    }

    @Test
    fun theHomeScreenDraws() {
        assertTrue(device.hasObject(By.res(appId, "workspacePager")))
    }

    @Test
    fun aSwipeUpOpensTheDrawerAndBackClosesIt() {
        val prefs = runBlocking { context.appGraph.preferences.snapshot() }
        val swipeUp = GestureDefaults.decode(prefs.gestureBindings)[Gesture.SWIPE_UP]?.action
        assumeTrue("swipe up isn't bound to the drawer on this device", swipeUp == GestureAction.OPEN_DRAWER)

        val width = device.displayWidth
        val height = device.displayHeight
        device.swipe(width / 2, height * 2 / 3, width / 2, height / 5, SWIPE_STEPS)
        assertTrue("the drawer didn't open", shows("drawerSheet"))

        device.waitForIdle()
        device.pressBack()
        assertTrue("the drawer didn't close", gone("drawerSheet"))
    }

    @Test
    fun settingsOpens() {
        context.startActivity(
            Intent(context, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        assertTrue("Settings didn't open", shows("settingsCoordinator"))
        device.pressBack()
    }

    @Test
    fun theLayoutAsItStandsExportsToABackupThatReadsBack() = runBlocking {
        val graph = context.appGraph
        val prefs = graph.preferences.snapshot()
        val model = graph.repository.modelFlow.first()

        val raw = graph.repository.exportBackup(prefs)
        val payload = Json { ignoreUnknownKeys = true }.decodeFromString(BackupPayload.serializer(), raw)

        assertEquals(prefs, payload.prefs)
        assertEquals(model, payload.model)
    }

    private fun shows(viewId: String): Boolean =
        device.wait(Until.hasObject(By.res(appId, viewId)), WAIT_MS)

    private fun gone(viewId: String): Boolean =
        device.wait(Until.gone(By.res(appId, viewId)), WAIT_MS)

    private companion object {
        const val WAIT_MS = 5_000L
        const val SWIPE_STEPS = 20
    }
}
