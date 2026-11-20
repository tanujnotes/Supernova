package com.supernova.launcher.data

import android.content.Context
import com.supernova.launcher.runLogged
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

// The drawer's app list as the last full listing left it, read back at the
// next start so the drawer has its apps at once; the fresh listing replaces
// it a moment later. Private space apps are left out: the space may be
// locked by then, and its apps must not show.
class AppListCache(context: Context) {
    private val file = File(context.noBackupFilesDir, "app_list.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(LaunchableApp.serializer())

    @Volatile
    private var saved: List<LaunchableApp>? = null

    fun load(): List<LaunchableApp> = runLogged("read app list") {
        if (!file.exists()) return@runLogged emptyList()
        json.decodeFromString(serializer, file.readText()).also { saved = it }
    }.getOrDefault(emptyList())

    // Off the main thread; skipped when nothing changed since the last save.
    fun save(apps: List<LaunchableApp>) {
        val kept = apps.filterNot { it.privateSpace }
        if (kept == saved) return
        saved = kept
        runLogged("save app list") {
            val partial = File(file.parentFile, "${file.name}.part")
            partial.writeText(json.encodeToString(serializer, kept))
            partial.renameTo(file)
        }
    }
}
