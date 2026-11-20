package com.supernova.launcher.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.LauncherUserInfo
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import android.util.LruCache
import androidx.core.graphics.drawable.toDrawable
import com.supernova.launcher.runLogged

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
    private val dynamicIcons = DynamicAppIcons(
        pm,
        context.getSystemService(android.app.ActivityManager::class.java).launcherLargeIconDensity
    )
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
    private val waiters = IconWaiters()
    private val appChanges = AppChanges(launcherApps, ::serialFor)
    private val profiles = ProfileState(launcherApps, userManager, ::serialFor)
    private val diskIcons = IconDiskCache(context)

    fun listLaunchableApps(): List<LaunchableApp> {
        val infoCache = mutableMapOf<String, LauncherActivityInfo>()
        val privateUser = privateSpaceUser()
        val self = android.os.Process.myUserHandle()
        val apps = launcherApps.profiles.flatMap { user ->
            val serial = serialFor(user)
            val isPrivate = user == privateUser
            val isWork = user != self && !isPrivate && profiles.isWorkUser(user)
            // A locked private space shows nothing anywhere, search included.
            if (isPrivate && userManager.isQuietModeEnabled(user)) return@flatMap emptyList()
            launcherApps.getActivityList(null, user).map { info ->
                val component = info.componentName
                infoCache["$serial/${component.packageName}/${component.className}"] = info
                LaunchableApp(
                    packageName = component.packageName,
                    className = component.className,
                    label = info.label?.toString().orEmpty(),
                    userSerial = serial,
                    installTime = info.firstInstallTime,
                    privateSpace = isPrivate,
                    work = isWork
                )
            }
        }.let { apps -> LabelOrder.sorted(apps) { it.label } }
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
            iconCache.remove("$it$LARGE_KEY")
            dominantColors.remove(it)
        }
        // A pack calendar icon is cached under its own day key, and a shape
        // change (or Reset custom icons) left the old one showing until the
        // day turned.
        val calendarKeys = iconCache.snapshot().keys.filter { key ->
            changed.any { key.startsWith("$it@cal") || key.startsWith("$it$LARGE_KEY@cal") }
        }
        calendarKeys.forEach(iconCache::remove)
        overrides = next
    }

    fun overrideFor(userSerial: Long, packageName: String?, className: String?): AppOverride? {
        if (packageName == null || className == null) return null
        return overrides["$userSerial/$packageName/$className"]
    }

    // Resolves a drawable by name out of an arbitrary installed package, or
    // an app's own icon for an AppIconSource pick.
    fun packDrawable(packPackage: String, drawableName: String): Drawable? = runCatching {
        if (AppIconSource.isAppIcon(packPackage)) return@runCatching appIconDrawable(drawableName)
        val res = pm.getResourcesForApplication(packPackage)
        val id = res.getIdentifier(drawableName, "drawable", packPackage)
        if (id == 0) null else res.getDrawable(id, null)
    }.getOrNull()

    // An installed app's own icon as the app ships it: no pack, no shape.
    private fun appIconDrawable(name: String): Drawable? {
        val app = AppIconSource.appOf(name) ?: return null
        return userForSerial(app.userSerial)?.let { user ->
            appOwnIcon(app.packageName, app.className, user)
        }
    }

    // An app's icon, rendered now if it isn't cached. Whoever waits here (a
    // bind, the first run's preview) goes ahead of the background warm.
    fun iconFor(app: LaunchableApp): Drawable? = waiters.ahead { renderIcon(app, null) }

    // A placed copy's icon: with an icon of its own (LauncherItem.appEdit,
    // see AppItemEdits) drawn from that, cached under the edit; else the
    // app's, as the drawer draws it.
    // `large`: the copy for a Large icon (2x2), sharp at twice the size;
    // only those render at that resolution, not every icon in the cache.
    fun iconFor(app: LaunchableApp, itemEdit: AppOverride?, large: Boolean = false): Drawable? {
        val own = ownIcon(itemEdit)
        if (own == null && !large) return iconFor(app)
        return waiters.ahead { renderIcon(app, own, large = large) }
    }

    // As iconFor, but only from the cache: never renders, safe on the main
    // thread.
    fun cachedIcon(app: LaunchableApp, itemEdit: AppOverride?, large: Boolean = false): Drawable? {
        val own = ownIcon(itemEdit)
        if (own == null && !large) return cachedIcon(app)
        return renderIcon(app, own, cachedOnly = true, large = large)
    }

    // A placed copy's icon edit, keyed by what draws it, or null for none.
    private fun ownIcon(itemEdit: AppOverride?): AppOverride? =
        itemEdit?.takeIf { with(AppItemEdits) { it.hasIcon() } }?.copy(customLabel = null, hideLabel = false)

    // An app's icon if it is already rendered, else null: never renders, so
    // it is safe on the main thread. The drawer binds with this and renders
    // the misses off it (IconThread) - a launcher restarted overnight opened
    // its first drawer with every visible icon drawn on the tap (#277).
    fun cachedIcon(app: LaunchableApp): Drawable? = renderIcon(app, null, cachedOnly = true)

    // `own` is a placed copy's icon edit, which stands in for the app's.
    // `large`: the copy a Large icon (2x2) draws, at its own resolution.
    private fun renderIcon(
        app: LaunchableApp,
        own: AppOverride?,
        cachedOnly: Boolean = false,
        large: Boolean = false
    ): Drawable? {
        val appKey = "${app.userSerial}/${app.packageName}/${app.className}"
        val baseKey = (if (own == null) appKey else "$appKey#${own.hashCode()}") + if (large) LARGE_KEY else ""
        // A cached icon is found before anything asks the system: every
        // drawer tile and home icon bind comes through here on the main
        // thread, and the resolve below is a binder call when the app isn't
        // in the enumeration yet. An icon is cached under its own key, or a
        // date icon under today's (yesterday's no longer matches).
        (iconCache.get(baseKey) ?: iconCache.get("$baseKey@cal${today()}"))?.let { return it }
        if (cachedOnly) return null
        val user = userForSerial(app.userSerial) ?: return null
        // An app's own date icon (#266) is cached under the day too, so the
        // day rollover's sweep of "@cal" keys draws it afresh.
        val info = resolveActivity(app.packageName, app.className, user)
        val dated = info?.let(dynamicIcons::has) == true
        val cacheKey = if (dated) "$baseKey@cal${today()}" else baseKey
        iconCache.get(cacheKey)?.let { return it }

        synchronized(renderLock) {
            // A pre-warm may have rendered it while we waited for the lock.
            iconCache.get(cacheKey)?.let { return it }
            val job = RenderJob(app, own, user, cacheKey, dated, info)
            if (!large) return renderLocked(job)
            val normal = iconRenderer.resolutionScale
            iconRenderer.resolutionScale = largeResolution
            try {
                return renderLocked(job)
            } finally {
                iconRenderer.resolutionScale = normal
            }
        }
    }

    // One icon to render: the app, a placed copy's own edit, its profile,
    // and the cache entry it goes into.
    private class RenderJob(
        val app: LaunchableApp,
        val own: AppOverride?,
        val user: UserHandle,
        val cacheKey: String,
        val dated: Boolean,
        val info: LauncherActivityInfo?
    ) {
        val appKey = "${app.userSerial}/${app.packageName}/${app.className}"
    }

    // renderIcon's render, under renderLock, into the job's cache entry.
    private fun renderLocked(job: RenderJob): Drawable? {
        val override = job.own ?: overrides[job.appKey]
        val ready = packCalendarIcon(job, override) ?: storedIcon(job, override)
        return ready ?: drawEdited(job.app, job.user, override)?.let { rendered ->
            storeIcon(job, override, rendered)
            if (job.own == null && LARGE_KEY !in job.cacheKey) {
                dominantColorOf(IconRenderer.bitmapOf(rendered))?.let { dominantColors[job.appKey] = it }
            }
            runLogged("badge icon for its profile") { pm.getUserBadgedIcon(rendered, job.user) }
                .getOrNull()?.also { iconCache.put(job.cacheKey, it) }
        }
    }

    // A rendered icon from the disk cache (IconDiskCache), badged and in
    // memory as a render's would be; null when there is none to use. Date
    // icons change by the day and never go there.
    private fun storedIcon(job: RenderJob, override: AppOverride?): Drawable? {
        val stamp = diskStamp(job, override)?.takeUnless { job.dated } ?: return null
        return diskIcons.read(diskSignature(), job.cacheKey, stamp)?.let { stored ->
            if (job.own == null && LARGE_KEY !in job.cacheKey) {
                dominantColorOf(IconRenderer.bitmapOf(stored))?.let { dominantColors[job.appKey] = it }
            }
            (runLogged("badge icon for its profile") { pm.getUserBadgedIcon(stored, job.user) }.getOrNull() ?: stored)
                .also { iconCache.put(job.cacheKey, it) }
        }
    }

    private fun storeIcon(job: RenderJob, override: AppOverride?, rendered: Drawable) {
        if (job.dated) return
        diskStamp(job, override)?.let { diskIcons.write(diskSignature(), job.cacheKey, it, rendered) }
    }

    // What one icon was drawn from: the app's installed package (its path
    // moves, or its file changes, with every update), its edits, the
    // resolution, and the themed colors where they reach it. Null when the
    // app's package can't be read: then it isn't kept.
    private fun diskStamp(job: RenderJob, override: AppOverride?): String? {
        val sourceDir = job.info?.applicationInfo?.sourceDir ?: return null
        val themed = themedIcons || override?.iconVariant == "THEMED"
        return "$sourceDir|${java.io.File(sourceDir).lastModified()}|${override?.hashCode()}|" +
            "${iconRenderer.resolutionScale}|" + (if (themed) "$themedIconBg/$themedIconFg" else "")
    }

    // Every setting the icons share; a change starts a new disk folder.
    private fun diskSignature(): String =
        "$DISK_FORMAT|${context.resources.displayMetrics.densityDpi}|$iconShape|${customShapeSpec?.encode()}|" +
            "${iconRenderer.reshapeLegacy}|${iconRenderer.shadow}|${iconRenderer.glass}|$themedIcons|" +
            "$themedIconsAutogen|${iconPacks.loadedPack()}|$packStamp"

    // The loaded pack's own install time: an updated pack draws afresh.
    private var packStamp = 0L

    // Dynamic calendar icons: an isolated day-keyed cache entry, so the
    // date is always current and no normal icon key is touched. Null (the
    // standard icon) when the pack has no entry for today. Only for an app
    // whose icon the user left alone - a photo, a pack icon or a variant
    // picked in Edit used to be ignored for it - and drawn as the pack's own
    // art, the way its other themed icons are, not on the iconback and cut by
    // the mask meant for unthemed ones.
    private fun packCalendarIcon(job: RenderJob, override: AppOverride?): Drawable? {
        val app = job.app
        val iconEdited = override?.iconBase64 != null || override?.iconDrawableName != null ||
            override?.iconVariant != null
        if (iconEdited || !iconPacks.hasCalendar(app.packageName, app.className)) return null
        val day = today()
        val calKey = if (job.dated) job.cacheKey else "${job.cacheKey}@cal$day"
        return iconCache.get(calKey) ?: iconPacks.calendarDrawable(app.packageName, app.className, day)
            ?.let { calendar ->
                runLogged("render calendar icon") {
                    iconRenderer.renderPackArt(calendar, shapeFor(override), customShapeSpec)
                }.getOrNull()
            }
            ?.let { rendered ->
                (runLogged("badge icon for its profile") { pm.getUserBadgedIcon(rendered, job.user) }.getOrNull()
                    ?: rendered).also { iconCache.put(calKey, it) }
            }
    }

    // An app's icon as it would look with `override` saved, for Edit's
    // preview: drawn now, never cached.
    fun previewIcon(app: LaunchableApp, override: AppOverride): Drawable? {
        val user = userForSerial(app.userSerial) ?: return null
        return synchronized(renderLock) { drawEdited(app, user, override) }
    }

    // ORIGINAL shows the app's own icon unshaped: the launcher's shape is
    // left off, but one picked for this app in Edit still applies.
    private fun shapeFor(override: AppOverride?): IconShape =
        override?.iconShape?.let(IconShape::fromName)
            ?: if (override?.iconVariant == "ORIGINAL") IconShape.SYSTEM else iconShape

    // The app's icon drawn with an edit's icon, style and shape, before the
    // profile badge. Called under renderLock.
    private fun drawEdited(app: LaunchableApp, user: UserHandle, override: AppOverride?): Drawable? {
        val variant = override?.iconVariant
        // Keys the pack's iconback choice, so an app keeps the same plate
        // between launches instead of shuffling on every cache rebuild.
        val packComponent = "${app.packageName}/${app.className}"
        val effectiveShape = shapeFor(override)
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
        // Another app's icon, picked in Edit, is drawn as that app's own
        // icon is (shape, glass and all), not as a pack's finished art.
        val pickedAppIcon = gallery == null && custom != null && AppIconSource.isAppIcon(override?.iconPackPackage)

        val source: Drawable
        val decoration: IconPackDecoration?
        // A pack's own art or a picked picture is finished as it is:
        // glass leaves it alone.
        var finishedArt = false
        // The app's own icon, left as it comes: no pack, no pick in Edit.
        var ownIcon = false
        if (custom != null) {
            // Another app's icon takes the themed look as the app's own
            // would: with Themed icons on, or Themed picked for this app.
            val mono = custom.takeIf { pickedAppIcon && variant != "ORIGINAL" }
                ?.let { themedIcon(it, force = variant == "THEMED") }
            source = mono ?: custom
            decoration = null
            finishedArt = !pickedAppIcon
            ownIcon = pickedAppIcon && mono == null
        } else if (variant == "ORIGINAL") {
            // The app's own icon with no pack plate and no shape clip.
            source = appOwnIcon(app.packageName, app.className, user) ?: return null
            decoration = null
        } else if (variant == "THEMED") {
            // Force the system monochrome look for just this app.
            val mono = monochromeThemedIcon(app.packageName, app.className, user, force = true)
            source = mono ?: appOwnIcon(app.packageName, app.className, user) ?: return null
            decoration = if (mono == null) iconPacks.decoration(packComponent) else null
        } else {
            // A themed icon from the active pack already looks final; only
            // unthemed apps get the pack's back/mask/upon treatment.
            val themed = iconPacks.themedIcon(app.packageName, app.className)
            // Pack theming wins; else the system monochrome layer when the
            // themed-icons pref is on; else the raw icon with pack plate.
            // The app's icon is loaded once: with Themed icons off the
            // monochrome look is null, and it was loaded a second time.
            val own = if (themed == null) appOwnIcon(app.packageName, app.className, user) else null
            val mono = own?.let { themedIcon(it, force = false) }
            source = themed ?: mono ?: own ?: return null
            decoration = if (themed == null && mono == null) iconPacks.decoration(packComponent) else null
            finishedArt = themed != null
            ownIcon = themed == null && mono == null && decoration == null
        }

        // ORIGINAL is the app's own icon, glass and all left off.
        val glass = if (variant == "ORIGINAL" || finishedArt) IconGlass.OFF else iconRenderer.glass
        return runLogged("render icon") {
            when {
                gallery != null -> pickedPicture(gallery.bitmap, effectiveShape)
                ownIcon -> iconRenderer.renderOwnIcon(source, effectiveShape, customShapeSpec, glass)
                finishedArt -> iconRenderer.renderPackArt(source, effectiveShape, customShapeSpec)
                else -> iconRenderer.render(source, decoration, effectiveShape, customShapeSpec, glass)
            }
        }.getOrNull()
    }

    // The cached dominant color for DYNAMIC badge tinting; null until the
    // icon has rendered at least once (callers fall back to the pref color).
    fun dominantColorFor(userSerial: Long, packageName: String?, className: String?): Int? {
        if (packageName == null || className == null) return null
        return dominantColors["$userSerial/$packageName/$className"]
    }

    // The Circle launch animation's and the motion previews' color.
    fun dominantColorOf(bitmap: android.graphics.Bitmap?): Int? = DominantColor.of(bitmap)

    // Renders every app's icon into the cache off the main thread, so binds
    // become cache-hit-only. Safe to call repeatedly; hits return instantly.
    // It pauses between icons while anyone waits in iconFor: a change of
    // icon settings starts a warm of every app, and the icons on screen
    // queued behind it for the render lock, a whole warm late.
    fun warmIcons(apps: List<LaunchableApp>) {
        for (app in apps) {
            waiters.stepAside()
            renderIcon(app, null)
        }
    }

    fun applyIconPack(packageName: String?) = synchronized(renderLock) {
        // Every view model applies the pack it reads from the store; a second
        // one applying the pack already loaded must not throw away the icons
        // the first has warmed.
        if (iconPacks.isLoaded(packageName)) return
        iconPacks.load(packageName)
        packStamp = packageName?.let { runCatching { pm.getPackageInfo(it, 0).lastUpdateTime }.getOrNull() } ?: 0L
        iconCache.evictAll()
        dominantColors.clear()
    }

    // Labs' Clear icon cache: the pack is read again, since it may have
    // been updated since it was parsed, and every icon renders afresh.
    fun clearIconCache() = synchronized(renderLock) {
        iconPacks.reload()
        diskIcons.clear()
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

    fun applyIconGlass(value: IconGlass) = synchronized(renderLock) {
        if (iconRenderer.glass == value) return
        iconRenderer.glass = value
        iconCache.evictAll()
        dominantColors.clear()
    }

    // Themed monochrome icons (API 33+). Colors are recomputed by the VM from
    // the palette; a change evicts the icon cache like any other icon pref.
    // Day rollover: drop the day-keyed calendar entries so the next bind
    // re-renders today's date. Cheap - only calendar apps have such keys.
    // True when there were any, so date icons have to redraw.
    fun onDayChanged(): Boolean = synchronized(renderLock) {
        val stale = iconCache.snapshot().keys.filter { it.contains("@cal") }
        stale.forEach { iconCache.remove(it) }
        stale.isNotEmpty()
    }

    // True when cached icons were dropped, so the screen has to redraw.
    //
    // With Themed icons off before and after, the colors only reach icons
    // set to Themed one by one in Edit: only those go. The colors follow the
    // dark or light theme, so a system theme switch (with Schedule or Auto,
    // the first use each morning and evening) used to drop every icon and
    // render them all again, the home screen's on the main thread.
    fun applyThemedIcons(
        enabled: Boolean,
        background: Int,
        foreground: Int,
        autogen: Boolean = false
    ): Boolean = synchronized(renderLock) {
        if (enabled == themedIcons && background == themedIconBg && foreground == themedIconFg &&
            autogen == themedIconsAutogen
        ) return false
        val wasEnabled = themedIcons
        themedIcons = enabled
        themedIconBg = background
        themedIconFg = foreground
        themedIconsAutogen = autogen
        if (!wasEnabled && !enabled) return evictForcedThemed()
        iconCache.evictAll()
        dominantColors.clear()
        true
    }

    // The icons drawn Themed whatever the switch: apps set to Themed in
    // Edit, and placed copies with an icon edit of their own ("#" keys,
    // few, and their edit may be Themed). Called under renderLock.
    private fun evictForcedThemed(): Boolean {
        val forced = overrides.filterValues { it.iconVariant == "THEMED" }.keys
        val stale = iconCache.snapshot().keys.filter { key ->
            '#' in key || forced.any { key == it || key.startsWith("$it@") || key.startsWith("$it$LARGE_KEY") }
        }
        stale.forEach(iconCache::remove)
        forced.forEach(dominantColors::remove)
        return stale.isNotEmpty()
    }

    private fun monochromeThemedIcon(
        packageName: String,
        className: String,
        user: UserHandle,
        force: Boolean = false
    ): Drawable? = appOwnIcon(packageName, className, user)?.let { themedIcon(it, force) }

    // The icon an app ships for its launcher entry: today's date icon for
    // one that has them (Google Calendar), else its launcher icon.
    private fun appOwnIcon(packageName: String, className: String, user: UserHandle): Drawable? {
        val info = resolveActivity(packageName, className, user) ?: return null
        return dynamicIcons.forDay(info, today()) ?: info.getIcon(0)
    }

    private fun today(): Int = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_MONTH)

    // An icon's themed look (API 33+), when Themed icons is on or `force`
    // asks for it; null when it is left as it is. Autogen (D8): one that
    // ships no monochrome layer gets a synthetic one, as a per-app force
    // does (see ThemedIconPainter).
    private fun themedIcon(icon: Drawable, force: Boolean): Drawable? {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
            (!force && !themedIcons)
        ) return null
        val shipsMono = (icon as? android.graphics.drawable.AdaptiveIconDrawable)?.monochrome != null
        return if (shipsMono || themedIconsAutogen || force) {
            runLogged("tint themed icon") {
                val painter = ThemedIconPainter(context.resources, themedIconBg, themedIconFg)
                painter.themedLayer(icon, ThemedGlyph::forApp) ?: painter.tintedCopy(icon)
            }.getOrNull()
        } else {
            null
        }
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

    // Large icons' resolution: only their own copies go when it moves.
    @Volatile private var largeResolution: Float = 1f

    fun applyLargeIconResolution(scale: Float) = synchronized(renderLock) {
        if (largeResolution == scale) return
        largeResolution = scale
        iconCache.snapshot().keys.filter { LARGE_KEY in it }.forEach(iconCache::remove)
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

    fun packMatches(packPackage: String, packageName: String, className: String): List<String> =
        iconPacks.matchesFor(packPackage, packageName, className)

    // A pack drawable pushed through the normal pipeline so it matches the
    // global shape; used for custom folder icons.
    fun renderPackDrawable(packPackage: String, drawableName: String): Drawable? {
        val source = packDrawable(packPackage, drawableName) ?: return null
        return if (AppIconSource.isAppIcon(packPackage)) {
            renderPickedAppIcon(source)
        } else {
            runLogged("render pack drawable") {
                iconRenderer.renderPackArt(source, iconShape, customShapeSpec)
            }.getOrNull()
        }
    }

    // Any drawable pushed through the shape pipeline so launcher-action tiles
    // and snapshotted activity/shortcut icons match every other icon's shape.
    fun renderThemedDrawable(source: Drawable): Drawable? = synchronized(renderLock) {
        runLogged("render themed drawable") {
            iconRenderer.render(source, null, iconShape, customShapeSpec)
        }.getOrNull()
    }

    fun renderActionTile(tile: Drawable): Drawable = renderThemedDrawable(tile) ?: tile

    // An app's icon as the launcher shows it everywhere else, for lists that
    // name an app rather than one of its activities (the widget sheet): its
    // launcher activity's icon with every customization - pack, shape, glass,
    // Edit. An app with nothing to launch (a widget-only package) gets its
    // plain icon through the same shape and glass.
    fun appIconFor(packageName: String, userSerial: Long): Drawable? =
        mainActivityFor(packageName, userSerial)?.let(::iconFor) ?: unlaunchableAppIcon(packageName, userSerial)

    private fun unlaunchableAppIcon(packageName: String, userSerial: Long): Drawable? {
        val user = userForSerial(userSerial) ?: return null
        return runCatching { launcherApps.getApplicationInfo(packageName, 0, user).loadIcon(pm) }
            .getOrNull()
            ?.let { raw ->
                val rendered = renderThemedDrawable(raw) ?: raw
                runCatching { pm.getUserBadgedIcon(rendered, user) }.getOrDefault(rendered)
            }
    }

    // Art picked for a folder, under the icons' glass when that is on.
    fun renderFolderPicture(picture: android.graphics.Bitmap): Drawable? = synchronized(renderLock) {
        runLogged("render shortcut picture") {
            if (iconRenderer.glass == IconGlass.OFF) {
                pickedPicture(picture, iconShape)
            } else {
                iconRenderer.renderFolderArt(picture.toDrawable(context.resources), iconShape, customShapeSpec)
            }
        }.getOrNull()
    }

    fun renderFolderPack(packPackage: String, drawableName: String): Drawable? = synchronized(renderLock) {
        if (iconRenderer.glass == IconGlass.OFF || AppIconSource.isAppIcon(packPackage)) {
            return renderPackDrawable(packPackage, drawableName)
        }
        val source = packDrawable(packPackage, drawableName) ?: return null
        runLogged("render folder art") { iconRenderer.renderFolderArt(source, iconShape, customShapeSpec) }.getOrNull()
    }

    // An app's icon picked for a folder or a launcher action, drawn as the
    // app's own icon is on the home screen.
    private fun renderPickedAppIcon(source: Drawable): Drawable? = synchronized(renderLock) {
        runLogged("render picked app icon") {
            iconRenderer.renderOwnIcon(source, iconShape, customShapeSpec, iconRenderer.glass)
        }.getOrNull()
    }

    // A picture picked for a launcher action, as one picked for an app is drawn.
    fun renderPickedPicture(picture: android.graphics.Bitmap): Drawable? = synchronized(renderLock) {
        runLogged("render picked picture") { pickedPicture(picture, iconShape) }.getOrNull()
    }

    // A picked picture is finished as it is: drawn across the icon shape when
    // it fills its frame, whatever its pixels are, and without glass either way.
    // One with a clear background goes through the shape pipeline like a pack's art.
    private fun pickedPicture(picture: android.graphics.Bitmap, shape: IconShape): Drawable =
        iconRenderer.renderPicture(picture, shape, customShapeSpec) ?: iconRenderer.render(
            picture.toDrawable(context.resources), null, shape, customShapeSpec, IconGlass.OFF
        )

    // An app shortcut's own picture (a contact's photo) across the icon
    // shape; null when it isn't one that fills its frame.
    fun renderShortcutPicture(picture: android.graphics.Bitmap): Drawable? = synchronized(renderLock) {
        runLogged("render picture") { iconRenderer.renderPicture(picture, iconShape, customShapeSpec) }.getOrNull()
    }

    // A shortcut's icon as its Edit left it (see ShortcutIcons), from its
    // stored picture `own`; null when the edit changes no icon, or there is
    // nothing to draw. Themed asks the app for the shortcut's icon: the
    // stored picture is flattened, so it has no layer to theme.
    fun renderShortcutIcon(item: LauncherItem, own: android.graphics.Bitmap?): Drawable? {
        val edit = item.shortcutEdit?.takeIf(ShortcutIcons::changesIcon) ?: return null
        val live = if (edit.iconVariant == "THEMED" && !ShortcutIcons.hasPickedIcon(edit)) liveIcon(item) else null
        return synchronized(renderLock) {
            runLogged("render shortcut icon") {
                ShortcutIcons(iconRenderer, customShapeSpec, iconShape, ::packDrawable, ::pickedPicture) {
                    ThemedIconPainter(context.resources, themedIconBg, themedIconFg)
                        .themedLayer(it, ThemedGlyph::forShortcut) ?: it
                }.draw(edit, live ?: own?.toDrawable(context.resources), own.takeIf { item.shortcutId != null })
            }.getOrNull()
        }
    }

    private fun liveIcon(item: LauncherItem): Drawable? =
        item.packageName?.takeIf { item.shortcutId != null }
            ?.let { pkg -> shortcutsFor(pkg, item.userSerial).firstOrNull { it.id == item.shortcutId } }
            ?.let(::shortcutIcon)

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

    // The activity an icon brought in from elsewhere should launch: the one
    // it names while the app still has it, else the app's first launcher
    // activity. An app that is not here keeps what it names.
    fun launchActivity(packageName: String, className: String?, userSerial: Long): String? {
        val user = userForSerial(userSerial) ?: return className
        val activities = runCatching { launcherApps.getActivityList(packageName, user) }.getOrDefault(emptyList())
            .map { it.componentName.className }
        return className?.takeIf { activities.isEmpty() || it in activities } ?: activities.firstOrNull()
    }

    fun isInstalled(packageName: String, userSerial: Long): Boolean {
        val user = userForSerial(userSerial) ?: return false
        return runCatching { launcherApps.isPackageEnabled(packageName, user) }.getOrDefault(false)
    }

    // Whether the app is surely gone for that user (see InstalledApps).
    fun isUninstalled(packageName: String, userSerial: Long): Boolean =
        userForSerial(userSerial)?.let { InstalledApps.isGone(launcherApps, userManager, packageName, it) } == true

    // The private space and work profiles (ProfileState).
    fun privateSpaceUser(): UserHandle? = profiles.privateSpaceUser()

    fun hasPrivateSpace(): Boolean = profiles.privateSpaceUser() != null

    fun privateSpace(): PrivateSpace? = profiles.privateSpace()

    fun setPrivateSpaceLocked(locked: Boolean): Boolean = profiles.setPrivateSpaceLocked(locked)

    fun pausedWorkProfiles(): Set<Long> = profiles.pausedWorkProfiles()

    fun setWorkPaused(paused: Boolean) = profiles.setWorkPaused(paused)

    fun privateSpaceSettings(): IntentSender? = profiles.privateSpaceSettings()

    fun privateSpaceInstall(): IntentSender? = profiles.privateSpaceInstall()

    fun userForSerial(serial: Long): UserHandle? {
        usersBySerial[serial]?.let { return it }
        val user = userManager.getUserForSerialNumber(serial) ?: return null
        usersBySerial[serial] = user
        return user
    }

    fun registerAppChangeListener(onChange: (AppChange) -> Unit) = appChanges.register(onChange)

    fun unregisterAppChangeListener(onChange: (AppChange) -> Unit) = appChanges.unregister(onChange)

    private fun resolveActivity(packageName: String, className: String, user: UserHandle): LauncherActivityInfo? {
        activityInfoByKey["${serialFor(user)}/$packageName/$className"]?.let { return it }
        // Not in the enumeration snapshot (e.g. freshly installed): one binder
        // call, then the next refresh folds it into the snapshot.
        return runCatching { launcherApps.getActivityList(packageName, user) }
            .getOrDefault(emptyList())
            .firstOrNull { it.componentName.className == className }
    }

    // A user's serial never changes while it exists; asking UserManager is
    // a binder call (Android 14 and below), once per icon resolve before.
    private val serialsByUser = java.util.concurrent.ConcurrentHashMap<UserHandle, Long>()

    private fun serialFor(user: UserHandle): Long =
        serialsByUser.getOrPut(user) { userManager.getSerialNumberForUser(user) }

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
            dynamicIcons.forget(packageName)
            iconCache.snapshot().keys.filter { it.contains(marker) }.forEach(iconCache::remove)
            dominantColors.keys.filter { it.contains(marker) }.forEach(dominantColors::remove)
            // An app wearing this app's icon (picked in Edit) draws it afresh too.
            overrides.filterValues { override ->
                AppIconSource.isAppIcon(override.iconPackPackage) &&
                    override.iconDrawableName?.let(AppIconSource::appOf)?.packageName == packageName
            }.keys.forEach { key ->
                iconCache.remove(key)
                dominantColors.remove(key)
            }
            // So may a placed copy with an icon of its own: few, so all of them.
            iconCache.snapshot().keys.filter { '#' in it }.forEach(iconCache::remove)
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
        // Bumped when a render changes how an icon looks for the same
        // settings, so the disk cache starts over.
        const val DISK_FORMAT = 1

        // Marks a Large icon's copy in a cache key.
        const val LARGE_KEY = "^large"

        // The size charged for a cached icon that reports none of its own.
        private const val FALLBACK_ICON_PX = 192
    }
}
