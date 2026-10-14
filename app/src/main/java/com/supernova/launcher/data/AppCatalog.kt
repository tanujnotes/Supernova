package com.supernova.launcher.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import android.util.LruCache

sealed interface AppChange {
    data class Removed(val packageName: String, val userSerial: Long) : AppChange
    data class Added(val packageName: String, val userSerial: Long) : AppChange
    data class Updated(val userSerial: Long, val packageNames: List<String> = emptyList()) : AppChange
}

class AppCatalog(
    private val context: Context,
    private val iconPacks: IconPackRepository = IconPackRepository(context)
) {

    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)
    private val pm = context.packageManager
    private val iconRenderer = IconRenderer(context)
    private var iconShape: IconShape = IconShape.SYSTEM
    // Decoded once per pref change; render never re-parses the JSON.
    private var customShapeSpec: CustomShapeSpec? = null
    // Themed (monochrome) icons on API 33+: draw each adaptive icon's
    // monochrome layer, tinted foreground on a tonal background, so unthemed
    // apps match the system's themed-icon look. Colors come from the palette.
    @Volatile private var themedIcons: Boolean = false
    @Volatile private var themedIconsAutogen: Boolean = false
    private var themedIconBg: Int = 0
    private var themedIconFg: Int = 0
    // An eighth of the heap holds every icon of a large app list; the old
    // fixed 4 MB thrashed at ~55 icons and turned scrolling into re-renders.
    private val iconCacheKb =
        ((Runtime.getRuntime().maxMemory() / 8) / 1024).toInt().coerceIn(8 * 1024, 64 * 1024)
    private val iconCache = object : LruCache<String, Drawable>(iconCacheKb) {
        override fun sizeOf(key: String, value: Drawable): Int {
            val bitmap = IconRenderer.bitmapOf(value) ?: return estimatedKb(value)
            return (bitmap.byteCount / 1024).coerceAtLeast(1)
        }
    }

    // A badged icon may come back wrapped, its bitmap out of reach: it is
    // charged by its own size. That has to stay the same for as long as the
    // entry is cached - LruCache throws when sizeOf changes its answer - so
    // it can't follow the render size, which the icon size and the display
    // density now move.
    private fun estimatedKb(value: Drawable): Int {
        val width = value.intrinsicWidth.takeIf { it > 0 } ?: FALLBACK_ICON_PX
        val height = value.intrinsicHeight.takeIf { it > 0 } ?: FALLBACK_ICON_PX
        return (width * height * 4 / 1024).coerceAtLeast(1)
    }
    // Dominant icon colors for DYNAMIC badges, filled at render time and
    // evicted in lockstep with the icon cache. Ints only, so no LRU needed.
    private val dominantColors = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val usersBySerial = java.util.concurrent.ConcurrentHashMap<Long, UserHandle>()
    // LauncherActivityInfo captured during enumeration, so a cache-miss icon
    // render doesn't need its own getActivityList binder round-trip.
    @Volatile
    private var activityInfoByKey: Map<String, LauncherActivityInfo> = emptyMap()
    // Serializes icon composition: the pack drawables and renderer state are
    // shared and mutated during render, and pre-warm runs off the main thread.
    private val renderLock = Any()
    private var changeCallback: LauncherApps.Callback? = null
    // One listener per live view model; the system callback behind them is
    // registered once and stays for as long as any of them does.
    private val changeListeners = java.util.concurrent.CopyOnWriteArraySet<(AppChange) -> Unit>()

    fun listLaunchableApps(): List<LaunchableApp> {
        val infoCache = mutableMapOf<String, LauncherActivityInfo>()
        val apps = launcherApps.profiles.flatMap { user ->
            val serial = serialFor(user)
            launcherApps.getActivityList(null, user).map { info ->
                val component = info.componentName
                infoCache["$serial/${component.packageName}/${component.className}"] = info
                LaunchableApp(
                    packageName = component.packageName,
                    className = component.className,
                    label = info.label?.toString().orEmpty(),
                    userSerial = serial,
                    installTime = info.firstInstallTime
                )
            }
        }.sortedBy { it.label.lowercase() }
        activityInfoByKey = infoCache
        return apps
    }

    // Per-app overrides pushed from the model; cache keys stay pure identity
    // and only changed keys are evicted on an update.
    private var overrides: Map<String, AppOverride> = emptyMap()

    fun applyOverrides(next: Map<String, AppOverride>) = synchronized(renderLock) {
        val changed = (overrides.keys + next.keys).filter { overrides[it] != next[it] }
        if (changed.isEmpty()) return
        changed.forEach {
            iconCache.remove(it)
            dominantColors.remove(it)
        }
        // A pack calendar icon is cached under its own day key, and a shape
        // change (or Reset custom icons) left the old one showing until the
        // day turned.
        val calendarKeys = iconCache.snapshot().keys.filter { key ->
            changed.any { key.startsWith("$it@cal") }
        }
        calendarKeys.forEach(iconCache::remove)
        overrides = next
    }

    fun overrideFor(userSerial: Long, packageName: String?, className: String?): AppOverride? {
        if (packageName == null || className == null) return null
        return overrides["$userSerial/$packageName/$className"]
    }

    // Resolves a drawable by name out of an arbitrary installed package.
    fun packDrawable(packPackage: String, drawableName: String): Drawable? = runCatching {
        val res = pm.getResourcesForApplication(packPackage)
        val id = res.getIdentifier(drawableName, "drawable", packPackage)
        if (id == 0) null else res.getDrawable(id, null)
    }.getOrNull()

    fun iconFor(app: LaunchableApp): Drawable? {
        val cacheKey = "${app.userSerial}/${app.packageName}/${app.className}"
        iconCache.get(cacheKey)?.let { return it }
        val user = userForSerial(app.userSerial) ?: return null

        synchronized(renderLock) {
            // A pre-warm may have rendered it while we waited for the lock.
            iconCache.get(cacheKey)?.let { return it }

            val override = overrides[cacheKey]
            val variant = override?.iconVariant
            // Keys the pack's iconback choice, so an app keeps the same plate
            // between launches instead of shuffling on every cache rebuild.
            val packComponent = "${app.packageName}/${app.className}"
            // ORIGINAL shows the app's own icon unshaped.
            val effectiveShape = if (variant == "ORIGINAL") {
                IconShape.SYSTEM
            } else {
                override?.iconShape?.let(IconShape::fromName) ?: iconShape
            }

            // Dynamic calendar icons: an isolated day-keyed cache entry, so the
            // date is always current and no normal icon key is touched. Falls
            // through to the standard icon when the pack has no entry for today.
            // Only for an app whose icon the user left alone - a photo, a pack
            // icon or a variant picked in Edit used to be ignored for it - and
            // drawn as the pack's own art, the way its other themed icons are,
            // not on the iconback and cut by the mask meant for unthemed ones.
            val iconEdited = override?.iconBase64 != null || override?.iconDrawableName != null || variant != null
            if (!iconEdited && iconPacks.hasCalendar(app.packageName, app.className)) {
                val day = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_MONTH)
                val calKey = "$cacheKey@cal$day"
                iconCache.get(calKey)?.let { return it }
                val calendar = iconPacks.calendarDrawable(app.packageName, app.className, day)
                if (calendar != null) {
                    val rendered = runCatching {
                        iconRenderer.render(calendar, null, effectiveShape, customShapeSpec)
                    }.getOrNull()
                    if (rendered != null) {
                        val badged = runCatching { pm.getUserBadgedIcon(rendered, user) }.getOrNull() ?: rendered
                        iconCache.put(calKey, badged)
                        return badged
                    }
                }
            }

            // A gallery image outranks a pack icon (they are mutually exclusive
            // by construction); decoding happens only here, on a cache miss.
            val gallery = override?.iconBase64?.takeIf { it.isNotEmpty() }?.let { data ->
                GalleryIconCodec.decode(data)?.let { BitmapDrawable(context.resources, it) }
            }

            // A per-app custom icon skips the pack pipeline entirely; if its
            // pack vanished, fall back silently and keep the override recorded.
            val custom = gallery ?: override?.iconPackPackage?.let { pack ->
                override.iconDrawableName?.let { name -> packDrawable(pack, name) }
            }

            val source: Drawable
            val decoration: IconPackDecoration?
            if (custom != null) {
                source = custom
                decoration = null
            } else if (variant == "ORIGINAL") {
                // The app's own icon with no pack plate and no shape clip.
                source = resolveActivity(app.packageName, app.className, user)?.getIcon(0) ?: return null
                decoration = null
            } else if (variant == "THEMED") {
                // Force the system monochrome look for just this app.
                val mono = monochromeThemedIcon(app.packageName, app.className, user, force = true)
                source = mono ?: resolveActivity(app.packageName, app.className, user)?.getIcon(0) ?: return null
                decoration = if (mono == null) iconPacks.decoration(packComponent) else null
            } else {
                // A themed icon from the active pack already looks final; only
                // unthemed apps get the pack's back/mask/upon treatment.
                val themed = iconPacks.themedIcon(app.packageName, app.className)
                // Pack theming wins; else the system monochrome layer when the
                // themed-icons pref is on; else the raw icon with pack plate.
                val mono = if (themed == null) monochromeThemedIcon(app.packageName, app.className, user) else null
                source = themed ?: mono ?: resolveActivity(app.packageName, app.className, user)?.getIcon(0)
                    ?: return null
                decoration = if (themed == null && mono == null) iconPacks.decoration(packComponent) else null
            }

            val rendered = runCatching {
                iconRenderer.render(source, decoration, effectiveShape, customShapeSpec)
            }.getOrNull() ?: return null
            dominantColorOf(IconRenderer.bitmapOf(rendered))?.let { dominantColors[cacheKey] = it }
            val icon = runCatching { pm.getUserBadgedIcon(rendered, user) }.getOrNull() ?: return null
            iconCache.put(cacheKey, icon)
            return icon
        }
    }

    // The cached dominant color for DYNAMIC badge tinting; null until the
    // icon has rendered at least once (callers fall back to the pref color).
    fun dominantColorFor(userSerial: Long, packageName: String?, className: String?): Int? {
        if (packageName == null || className == null) return null
        return dominantColors["$userSerial/$packageName/$className"]
    }

    // Cheap dominant color: downsample to 8x8 and average the saturated
    // pixels, falling back to every opaque pixel for grayscale icons. Also
    // the Circle launch animation's color.
    fun dominantColorOf(bitmap: android.graphics.Bitmap?): Int? {
        bitmap ?: return null
        val sample = android.graphics.Bitmap.createScaledBitmap(bitmap, 8, 8, true)
        var r = 0L
        var g = 0L
        var b = 0L
        var saturated = 0
        var fr = 0L
        var fg = 0L
        var fb = 0L
        var opaque = 0
        val hsv = FloatArray(3)
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                val pixel = sample.getPixel(x, y)
                if ((pixel ushr 24) < 128) continue
                fr += (pixel shr 16) and 0xFF
                fg += (pixel shr 8) and 0xFF
                fb += pixel and 0xFF
                opaque++
                android.graphics.Color.colorToHSV(pixel, hsv)
                if (hsv[1] >= 0.35f && hsv[2] >= 0.15f) {
                    r += (pixel shr 16) and 0xFF
                    g += (pixel shr 8) and 0xFF
                    b += pixel and 0xFF
                    saturated++
                }
            }
        }
        sample.recycle()
        return when {
            saturated > 0 -> android.graphics.Color.rgb(
                (r / saturated).toInt(), (g / saturated).toInt(), (b / saturated).toInt()
            )
            opaque > 0 -> android.graphics.Color.rgb(
                (fr / opaque).toInt(), (fg / opaque).toInt(), (fb / opaque).toInt()
            )
            else -> null
        }
    }

    // Renders every app's icon into the cache off the main thread, so binds
    // become cache-hit-only. Safe to call repeatedly; hits return instantly.
    fun warmIcons(apps: List<LaunchableApp>) {
        apps.forEach { iconFor(it) }
    }

    fun applyIconPack(packageName: String?) = synchronized(renderLock) {
        // Every view model applies the pack it reads from the store; a second
        // one applying the pack already loaded must not throw away the icons
        // the first has warmed.
        if (iconPacks.isLoaded(packageName)) return
        iconPacks.load(packageName)
        iconCache.evictAll()
        dominantColors.clear()
    }

    // Labs' Clear icon cache: the pack is read again, since it may have
    // been updated since it was parsed, and every icon renders afresh.
    fun clearIconCache() = synchronized(renderLock) {
        iconPacks.reload()
        iconCache.evictAll()
        dominantColors.clear()
    }

    fun applyIconShape(shape: IconShape, custom: CustomShapeSpec? = null) = synchronized(renderLock) {
        if (shape == iconShape && custom == customShapeSpec) return
        iconShape = shape
        customShapeSpec = custom
        iconCache.evictAll()
        dominantColors.clear()
    }

    fun applyReshapeLegacy(value: ReshapeLegacy) = synchronized(renderLock) {
        if (iconRenderer.reshapeLegacy == value) return
        iconRenderer.reshapeLegacy = value
        iconCache.evictAll()
        dominantColors.clear()
    }

    // Themed monochrome icons (API 33+). Colors are recomputed by the VM from
    // the palette; a change evicts the icon cache like any other icon pref.
    // Day rollover: drop the day-keyed calendar entries so the next bind
    // re-renders today's date. Cheap - only calendar apps have such keys.
    fun onDayChanged() = synchronized(renderLock) {
        val stale = iconCache.snapshot().keys.filter { it.contains("@cal") }
        stale.forEach { iconCache.remove(it) }
    }

    fun applyThemedIcons(
        enabled: Boolean,
        background: Int,
        foreground: Int,
        autogen: Boolean = false
    ) = synchronized(renderLock) {
        if (enabled == themedIcons && background == themedIconBg && foreground == themedIconFg &&
            autogen == themedIconsAutogen
        ) return
        themedIcons = enabled
        themedIconBg = background
        themedIconFg = foreground
        themedIconsAutogen = autogen
        iconCache.evictAll()
        dominantColors.clear()
    }

    private fun monochromeThemedIcon(
        packageName: String,
        className: String,
        user: UserHandle,
        force: Boolean = false
    ): Drawable? {
        if ((!force && !themedIcons) ||
            android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU
        ) return null
        val icon = resolveActivity(packageName, className, user)?.getIcon(0) ?: return null
        val mono = (icon as? android.graphics.drawable.AdaptiveIconDrawable)?.monochrome
        if (mono != null) {
            return runCatching {
                val fg = (mono.constantState?.newDrawable()?.mutate() ?: mono).apply { setTint(themedIconFg) }
                android.graphics.drawable.AdaptiveIconDrawable(
                    android.graphics.drawable.ColorDrawable(themedIconBg), fg
                )
            }.getOrNull()
        }
        // Autogen (D8): no monochrome layer shipped - synthesize one from the
        // icon's silhouette. A per-app force also gets the synthetic layer.
        if (!themedIconsAutogen && !force) return null
        return runCatching { autogenMonochrome(icon) }.getOrNull()
    }

    // Rasterizes the adaptive foreground (or the whole legacy icon, inset
    // toward the safe zone so the mask doesn't crop it), keeps only its
    // alpha, and tints that silhouette like a real monochrome layer.
    private fun autogenMonochrome(icon: Drawable): Drawable {
        val adaptive = icon as? android.graphics.drawable.AdaptiveIconDrawable
        val source = adaptive?.foreground ?: icon
        val size = 192
        val silhouette = android.graphics.Bitmap.createBitmap(
            size, size, android.graphics.Bitmap.Config.ARGB_8888
        )
        val canvas = android.graphics.Canvas(silhouette)
        if (adaptive == null) {
            val inset = (size * 0.2f).toInt()
            source.setBounds(inset, inset, size - inset, size - inset)
        } else {
            source.setBounds(0, 0, size, size)
        }
        source.draw(canvas)
        val alpha = silhouette.extractAlpha()
        val tinted = android.graphics.Bitmap.createBitmap(
            size, size, android.graphics.Bitmap.Config.ARGB_8888
        )
        android.graphics.Canvas(tinted).drawBitmap(
            alpha, 0f, 0f,
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = themedIconFg }
        )
        return android.graphics.drawable.AdaptiveIconDrawable(
            android.graphics.drawable.ColorDrawable(themedIconBg),
            android.graphics.drawable.BitmapDrawable(context.resources, tinted)
        )
    }

    fun applyIconShadow(value: Boolean) = synchronized(renderLock) {
        if (iconRenderer.shadow == value) return
        iconRenderer.shadow = value
        iconCache.evictAll()
        dominantColors.clear()
    }

    // Icons render at the largest icon size in use; see IconRenderer.
    fun applyIconResolution(scale: Float) = synchronized(renderLock) {
        if (iconRenderer.resolutionScale == scale) return
        iconRenderer.resolutionScale = scale
        iconCache.evictAll()
        dominantColors.clear()
    }

    // The display's density changed (Display size): every cached icon was
    // rendered for the old one. True when it did.
    fun applyDensity(densityDpi: Int): Boolean = synchronized(renderLock) {
        if (densityDpi == renderedDensityDpi) return false
        val first = renderedDensityDpi == 0
        renderedDensityDpi = densityDpi
        if (first) return false
        iconCache.evictAll()
        dominantColors.clear()
        true
    }

    private var renderedDensityDpi = 0

    fun shortcutIcon(shortcut: android.content.pm.ShortcutInfo): Drawable? = runCatching {
        launcherApps.getShortcutIconDrawable(shortcut, context.resources.displayMetrics.densityDpi)
    }.getOrNull()

    // Packages that can be the home app: Supernova, One UI Home and
    // the rest. Settings' FallbackHome (the direct-boot placeholder) also
    // answers HOME, but at a negative priority, so it isn't counted.
    fun homePackages(): Set<String> = runCatching {
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .filter { it.priority >= 0 }
            .mapTo(HashSet()) { it.activityInfo.packageName }
    }.getOrDefault(emptySet())

    fun shortcutsFor(packageName: String, userSerial: Long): List<android.content.pm.ShortcutInfo> {
        val user = userForSerial(userSerial) ?: return emptyList()
        val query = LauncherApps.ShortcutQuery()
            .setPackage(packageName)
            .setQueryFlags(
                LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
            )
        return runCatching { launcherApps.getShortcuts(query, user) ?: emptyList() }
            .getOrElse { emptyList() }
    }

    // The package's first launchable activity, for auto-adding new installs,
    // or the one named `className` when the package has several.
    fun mainActivityFor(packageName: String, userSerial: Long, className: String? = null): LaunchableApp? {
        val user = userForSerial(userSerial) ?: return null
        val info = runCatching { launcherApps.getActivityList(packageName, user) }
            .getOrDefault(emptyList())
            .firstOrNull { className == null || it.componentName.className == className } ?: return null
        return LaunchableApp(
            packageName = info.componentName.packageName,
            className = info.componentName.className,
            label = info.label?.toString().orEmpty(),
            userSerial = userSerial,
            installTime = info.firstInstallTime
        )
    }

    // ApplicationInfo.category for Smart Folders; UNDEFINED when the package
    // is missing (e.g. a work-profile-only app not visible to plain PM).
    fun appCategory(packageName: String): Int = runCatching {
        pm.getApplicationInfo(packageName, 0).category
    }.getOrDefault(android.content.pm.ApplicationInfo.CATEGORY_UNDEFINED)

    fun availableIconPacks(): List<IconPack> = iconPacks.availablePacks()

    fun iconPackLabel(packPackage: String): String = iconPacks.packLabel(packPackage)

    fun listPackDrawables(packPackage: String): List<String> = iconPacks.listDrawables(packPackage)

    // A pack drawable pushed through the normal pipeline so it matches the
    // global shape; used for custom folder icons.
    fun renderPackDrawable(packPackage: String, drawableName: String): Drawable? {
        val source = packDrawable(packPackage, drawableName) ?: return null
        return runCatching { iconRenderer.render(source, null, iconShape, customShapeSpec) }.getOrNull()
    }

    // Any drawable pushed through the shape pipeline so launcher-action tiles
    // and snapshotted activity/shortcut icons match every other icon's shape.
    fun renderThemedDrawable(source: Drawable): Drawable? = synchronized(renderLock) {
        runCatching { iconRenderer.render(source, null, iconShape, customShapeSpec) }.getOrNull()
    }

    fun launch(
        app: LaunchableApp,
        sourceBounds: android.graphics.Rect? = null,
        options: android.os.Bundle? = null
    ) {
        val user = requireNotNull(userForSerial(app.userSerial)) { "Unknown user ${app.userSerial}" }
        launcherApps.startMainActivity(
            ComponentName(app.packageName, app.className), user, sourceBounds, options
        )
    }

    fun openAppInfo(packageName: String, className: String?, userSerial: Long) {
        val user = userForSerial(userSerial)
        if (user != null && className != null) {
            launcherApps.startAppDetailsActivity(ComponentName(packageName, className), user, null, null)
        } else {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            )
        }
    }

    fun uninstallIntent(packageName: String, userSerial: Long): Intent {
        return Intent(Intent.ACTION_DELETE).apply {
            data = Uri.parse("package:$packageName")
            userForSerial(userSerial)?.let { putExtra(Intent.EXTRA_USER, it) }
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    fun isInstalled(packageName: String, userSerial: Long): Boolean {
        val user = userForSerial(userSerial) ?: return false
        return runCatching { launcherApps.isPackageEnabled(packageName, user) }.getOrDefault(false)
    }

    fun userForSerial(serial: Long): UserHandle? {
        usersBySerial[serial]?.let { return it }
        val user = userManager.getUserForSerialNumber(serial) ?: return null
        usersBySerial[serial] = user
        return user
    }

    fun registerAppChangeListener(onChange: (AppChange) -> Unit) {
        changeListeners += onChange
        if (changeCallback != null) return
        val callback = object : LauncherApps.Callback() {
            override fun onPackageRemoved(packageName: String, user: UserHandle) {
                dispatch(AppChange.Removed(packageName, serialFor(user)))
            }

            override fun onPackageAdded(packageName: String, user: UserHandle) {
                dispatch(AppChange.Added(packageName, serialFor(user)))
            }

            // The callbacks run on the main thread, and the render lock can
            // be held there for a whole icon pack read: invalidating is left
            // to the listener, off the main thread (invalidatePackages).
            override fun onPackageChanged(packageName: String, user: UserHandle) {
                dispatch(AppChange.Updated(serialFor(user), listOf(packageName)))
            }

            override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) {
                dispatch(AppChange.Updated(serialFor(user), packageNames.toList()))
            }

            override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) {
                dispatch(AppChange.Updated(serialFor(user)))
            }
        }
        changeCallback = callback
        launcherApps.registerCallback(callback, Handler(Looper.getMainLooper()))
    }

    fun unregisterAppChangeListener(onChange: (AppChange) -> Unit) {
        changeListeners -= onChange
        if (changeListeners.isNotEmpty()) return
        changeCallback?.let(launcherApps::unregisterCallback)
        changeCallback = null
    }

    private fun dispatch(change: AppChange) {
        changeListeners.forEach { it(change) }
    }

    private fun resolveActivity(packageName: String, className: String, user: UserHandle): LauncherActivityInfo? {
        activityInfoByKey["${serialFor(user)}/$packageName/$className"]?.let { return it }
        // Not in the enumeration snapshot (e.g. freshly installed): one binder
        // call, then the next refresh folds it into the snapshot.
        return runCatching { launcherApps.getActivityList(packageName, user) }
            .getOrDefault(emptyList())
            .firstOrNull { it.componentName.className == className }
    }

    private fun serialFor(user: UserHandle): Long = userManager.getSerialNumberForUser(user)

    // An app's icons render again after an update or removal, from the app
    // as it is now: its cached icons go, and so does its enumerated activity
    // info, which a bind before the next scan otherwise rendered the
    // pre-update icon from - and cached for good. All under the render lock,
    // so a render already under way can't put the old icon back; so off the
    // main thread, as the lock can be held through an icon pack read.
    fun invalidatePackages(packageNames: List<String>) = synchronized(renderLock) {
        packageNames.forEach { packageName ->
            val marker = "/$packageName/"
            activityInfoByKey = activityInfoByKey.filterKeys { !it.contains(marker) }
            iconCache.snapshot().keys.filter { it.contains(marker) }.forEach(iconCache::remove)
            dominantColors.keys.filter { it.contains(marker) }.forEach(dominantColors::remove)
        }
    }

    // The icon pack in use was updated: it is read again, and every icon
    // renders afresh from it, where before only the pack app's own icon did
    // and its new icons waited for a restart. Off the main thread - reading
    // a big pack takes a while.
    fun onPackagesUpdated(packageNames: List<String>): Boolean = synchronized(renderLock) {
        if (packageNames.none(iconPacks::isLoaded)) return false
        iconPacks.reload()
        iconCache.evictAll()
        dominantColors.clear()
        true
    }

    companion object {
        // The size charged for a cached icon that reports none of its own.
        private const val FALLBACK_ICON_PX = 192
    }
}
