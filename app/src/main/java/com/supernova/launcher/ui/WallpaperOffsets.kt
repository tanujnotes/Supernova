package com.supernova.launcher.ui

import android.app.WallpaperManager
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import com.supernova.launcher.runLogged

// Hands the wallpaper its scroll offset off the main thread. Each call is a
// binder call into the window manager, made per frame of a page swipe; on the
// main thread it took frame time and could wait on a busy system or a live
// wallpaper. Only the latest offset is sent: a frame's value replaces one
// still waiting, as Launcher3 does.
object WallpaperOffsets {

    private class Offset(val token: IBinder, val x: Float, val step: Float?)

    private val handler by lazy {
        Handler(HandlerThread("wallpaper-offsets").apply { start() }.looper)
    }

    @Volatile
    private var pending: Offset? = null
    private var manager: WallpaperManager? = null
    private var sentStep: Float? = null

    // `step` is the offset step for the page count, or null when unchanged.
    fun send(context: Context, token: IBinder, x: Float, step: Float?) {
        if (manager == null) manager = WallpaperManager.getInstance(context.applicationContext)
        val wanted = Offset(token, x, step ?: pending?.step)
        val queued = pending != null
        pending = wanted
        if (!queued) handler.post(::flush)
    }

    private fun flush() {
        val offset = pending ?: return
        pending = null
        val wallpaper = manager ?: return
        runLogged("set wallpaper offset") {
            offset.step?.takeIf { it != sentStep }?.let {
                wallpaper.setWallpaperOffsetSteps(it, 0f)
                sentStep = it
            }
            wallpaper.setWallpaperOffsets(offset.token, offset.x, 0f)
        }
    }
}
