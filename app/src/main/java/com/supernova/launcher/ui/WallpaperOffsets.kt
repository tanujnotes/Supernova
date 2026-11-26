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

    // A wallpaper command - a tap on the home screen (#379), say. A live
    // wallpaper that takes touches, a KLWP preset among them, only hears a tap
    // the launcher hands it: the standard COMMAND_TAP, with the tap's position
    // on screen, as Launcher3 sends it. Every command goes, none replaces
    // another, and like the offsets they go off the main thread: the engine
    // handling one is not the home screen's wait.
    fun command(context: Context, token: IBinder, name: String, x: Int, y: Int) {
        if (manager == null) manager = WallpaperManager.getInstance(context.applicationContext)
        val wallpaper = manager ?: return
        handler.post {
            runLogged("send wallpaper command") {
                wallpaper.sendWallpaperCommand(token, name, x, y, 0, null)
            }
        }
    }

    private class Zoom(
        val token: IBinder,
        val method: java.lang.reflect.Method,
        val value: Float,
        val onFail: () -> Unit
    )

    @Volatile
    private var pendingZoom: Zoom? = null

    // The wallpaper zoom behind the drawer, on the same thread: a binder call
    // per frame of the drawer's open and close. `onFail` runs on the main
    // thread when the wallpaper service refuses it.
    fun zoom(context: Context, token: IBinder, method: java.lang.reflect.Method, value: Float, onFail: () -> Unit) {
        if (manager == null) manager = WallpaperManager.getInstance(context.applicationContext)
        val queued = pendingZoom != null
        pendingZoom = Zoom(token, method, value, onFail)
        if (!queued) handler.post(::flushZoom)
    }

    private fun flushZoom() {
        val zoom = pendingZoom ?: return
        pendingZoom = null
        val wallpaper = manager ?: return
        @Suppress("TooGenericExceptionCaught")
        try {
            zoom.method.invoke(wallpaper, zoom.token, zoom.value)
        } catch (_: Exception) {
            // Some OEM wallpaper services throw; the caller stops asking.
            android.os.Handler(android.os.Looper.getMainLooper()).post(zoom.onFail)
        }
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
