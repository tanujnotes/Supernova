package com.supernova.launcher.ui

import android.app.WallpaperManager
import android.os.Build
import java.lang.reflect.Method

// The wallpaper zoom-out behind an opening drawer. setWallpaperZoomOut
// (IBinder, float) exists since Android 11 but is not in the SDK, so it is
// looked up once, reflectively; newer releases block it for apps outright
// (the non-SDK list marks it blocked), and then there is no zoom to offer:
// null, and settings leaves the toggle out instead of showing one that
// does nothing.
object WallpaperZoom {

    val method: Method? by lazy {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@lazy null
        runCatching {
            WallpaperManager::class.java.getMethod(
                "setWallpaperZoomOut", android.os.IBinder::class.java, Float::class.javaPrimitiveType
            )
        }.getOrNull()
    }

    val supported: Boolean get() = method != null
}
