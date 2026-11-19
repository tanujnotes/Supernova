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

    // iconFor goes ahead of a running warm, as a bind's render did.
    fun render(catalog: AppCatalog, app: LaunchableApp, onReady: (Drawable?) -> Unit) {
        executor.execute {
            val icon = runLogged("render icon") { catalog.iconFor(app) }.getOrNull()
            mainHandler.post { onReady(icon) }
        }
    }
}
