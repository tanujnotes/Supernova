package com.supernova.launcher

import android.app.Application
import android.util.Log
import com.supernova.launcher.data.DeviceProfile
import com.supernova.launcher.ui.theme.ThemeEngine
import com.supernova.launcher.ui.theme.ThemeMode
import kotlinx.coroutines.runBlocking
import java.io.File

class SupernovaApp : Application() {

    // The app's one set of services (AppGraph); every screen draws from it.
    val graph: AppGraph by lazy { AppGraph(this) }

    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
        applyStoredTheme()
    }

    // Night mode must be set before the first activity inflates anything, so
    // this one preference read is deliberately synchronous.
    private fun applyStoredTheme() {
        runCatching {
            val preferences = graph.preferences
            val prefs = runBlocking {
                // Before the first snapshot, so the launcher's first frame is
                // already on the grid this screen deserves.
                preferences.applyDeviceProfileOnce(DeviceProfile.forDevice(this@SupernovaApp))
                preferences.snapshot()
            }
            ThemeEngine.applyMode(this@SupernovaApp, prefs)
        }
    }

    private fun installCrashHandler() {
        val fallback = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                crashLog(this@SupernovaApp).appendText(
                    "\n$CRASH_ENTRY${java.time.Instant.now()} ===\n" +
                        "Version: ${versionLabel(this@SupernovaApp)}\n" +
                        "Thread: ${thread.name}\n" +
                        Log.getStackTraceString(throwable) +
                        "\n"
                )
            }
            fallback?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        // Every entry opens with this marker, so a count of lines starting
        // with it is the number of crashes on record.
        const val CRASH_ENTRY = "=== "

        // Settings > About shares it: without a crash reporter, it is the
        // only way a tester's stack traces get back to the developer.
        fun crashLog(context: android.content.Context): File =
            File(context.filesDir, "supernova_crash.log")

        fun crashCount(context: android.content.Context): Int = runCatching {
            crashLog(context).takeIf { it.exists() }
                ?.useLines { lines -> lines.count { it.startsWith(CRASH_ENTRY) } }
        }.getOrNull() ?: 0

        // "0.1 (1)": versionName and versionCode, as About shows them.
        fun versionLabel(context: android.content.Context): String? = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "${info.versionName} (${androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info)})"
        }.getOrNull()
    }
}
