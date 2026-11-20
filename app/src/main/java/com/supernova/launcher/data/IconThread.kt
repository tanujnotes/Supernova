package com.supernova.launcher.data

import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import com.supernova.launcher.runLogged
import java.util.concurrent.Executors

// Renders icons a bind found missing from the cache, off the main thread, and
// hands each over on the main thread (#277). One thread: renders serialize on
// the catalog's render lock anyway, and requests land in the order asked.
object IconThread {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "icon-render").apply { isDaemon = true }
    }

    // Several icons (a folder's preview, a row) rendered into the cache, then
    // one call on the main thread - only if any rendered, so a caller that
    // rebuilds on it can't loop on an icon that never renders.
    fun renderAll(catalog: AppCatalog, icons: List<Pair<LaunchableApp, AppOverride?>>, onReady: () -> Unit) =
        renderAll(icons.map { (app, edit) -> { catalog.iconFor(app, edit) } }, onReady)

    // The same for any renders: each puts its icon in the cache.
    fun renderAll(renders: List<() -> Drawable?>, onReady: () -> Unit) {
        if (renders.isEmpty()) return
        executor.execute {
            val rendered = renders.count { render -> runLogged("render icon") { render() }.getOrNull() != null }
            if (rendered > 0) mainHandler.post(onReady)
        }
    }

    // iconFor goes ahead of a running warm, as a bind's render did.
    fun render(catalog: AppCatalog, app: LaunchableApp, onReady: (Drawable?) -> Unit) {
        executor.execute {
            val icon = runLogged("render icon") { catalog.iconFor(app) }.getOrNull()
            mainHandler.post { onReady(icon) }
        }
    }
}
