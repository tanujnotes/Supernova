package com.supernova.launcher

import android.app.Application
import android.content.Context
import com.supernova.launcher.data.AppCatalog
import com.supernova.launcher.data.IconPackRepository
import com.supernova.launcher.data.LauncherPreferences
import com.supernova.launcher.data.LauncherRepository

// The app's one instance of each service, built on first use and held by
// SupernovaApp for the life of the process. The launcher, Settings and the
// wizard each keep their own LauncherViewModel (right for their lifecycles),
// but the layer beneath is shared: one app catalog with one icon cache, one
// repository, one preferences reader. Before this, opening Settings over the
// launcher built a second catalog and rendered every icon again into it.
//
// Construction only. Nothing in here does work, and nothing reads it but the
// activities and the view model factory: views, adapters and controllers take
// what they need through their constructors.
class AppGraph(val application: Application) {
    val preferences: LauncherPreferences by lazy { LauncherPreferences(application) }
    val repository: LauncherRepository by lazy { LauncherRepository(application) }
    val iconPacks: IconPackRepository by lazy { IconPackRepository(application) }
    val catalog: AppCatalog by lazy { AppCatalog(application, iconPacks) }
}

val Context.appGraph: AppGraph
    get() = (applicationContext as SupernovaApp).graph
