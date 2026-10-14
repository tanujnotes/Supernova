package com.supernova.launcher.baselineprofile

import android.content.Intent
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// WARNING: the generator uninstalls and reinstalls the launcher on the
// device it runs on, wiping its data; see the module's build file.
//
// Drives the launcher through what every session does first: a cold start
// onto the home screen, the drawer opened and scrolled, and back home. The
// classes and methods that run are what the profile pre-compiles, so a fresh
// install starts on AOT code instead of interpreting its way to the first
// frame.
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(
        packageName = PACKAGE,
        includeInStartupProfile = true
    ) {
        // The package's launch intent resolves to the Settings alias; the
        // home screen is what a start should mean here.
        val home = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .setPackage(PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivityAndWait(home)
        device.waitForIdle()

        val width = device.displayWidth
        val height = device.displayHeight
        // Swipe up from the lower half of the page: the drawer.
        device.swipe(width / 2, height * 2 / 3, width / 2, height / 5, 20)
        device.wait(Until.hasObject(By.res(PACKAGE, "drawerSheet")), 3_000)
        device.waitForIdle()
        // A couple of scrolls through the app list, then back home.
        repeat(2) {
            device.swipe(width / 2, height * 3 / 4, width / 2, height / 4, 15)
            device.waitForIdle()
        }
        device.pressBack()
        device.waitForIdle()
        device.pressBack()
        device.waitForIdle()
    }

    private companion object {
        const val PACKAGE = "com.supernova.launcher"
    }
}
