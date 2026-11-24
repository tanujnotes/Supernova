package com.supernova.launcher.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.util.DisplayMetrics
import android.util.TypedValue
import com.supernova.launcher.runLogged
import org.xmlpull.v1.XmlPullParser

data class IconPack(
    val packageName: String,
    val label: String
)

// Parses the de-facto Apex/ADW icon pack format: appfilter.xml maps a
// flattened ComponentName to a drawable name inside the pack's resources.
class IconPackRepository(private val context: Context) {

    private val pm: PackageManager = context.packageManager

    private var loadedPackage: String? = null
    private var componentMap: Map<String, String> = emptyMap()
    // Package -> drawable for the first entry a package contributes. Packs are
    // written against whatever activity an app shipped when the pack was made,
    // and apps rename those constantly, so an exact component miss falls back
    // here rather than leaving a themed app looking unthemed. First entry
    // wins: packs list one icon per app almost without exception, and where
    // one doesn't, some icon from the right app beats none.
    // Dynamic calendar icons: component -> drawable-name prefix; the icon for
    // today is `prefix + dayOfMonth` (1..31), the de-facto pack convention.
    private var calendarMap: Map<String, String> = emptyMap()
    private var packageFallback: Map<String, String> = emptyMap()
    private var packResources: Resources? = null
    private var maskDrawable: Drawable? = null
    private var backDrawables: List<Drawable> = emptyList()
    private var uponDrawable: Drawable? = null
    private var iconScale: Float = 1f

    // Dark or light, as the launcher is: day/night packs ship their dark
    // icons as -night resources (drawables, or the colours their vectors
    // use), so the pack is read through a configuration of our own rather
    // than the process's, which is the system's and can change under a
    // render. Set before load and on every theme flip (setNight).
    @Volatile private var night: Boolean = false
    // Whether the loaded pack's icons look different by night: only then
    // does a theme flip throw the icons away (see setNight).
    private var followsNight: Boolean = false

    // The pack's own app label, for anywhere a raw package name would be
    // shown to a user. Falls back to the package name when the pack has been
    // uninstalled behind our back and the pref still names it.
    fun packLabel(packageName: String): String =
        runCatching { pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString() }
            .getOrDefault(packageName)

    fun availablePacks(): List<IconPack> {
        return PACK_INTENTS
            .flatMap { intent ->
                runCatching { pm.queryIntentActivities(intent, 0) }.getOrDefault(emptyList())
            }
            .map { it.activityInfo.packageName }
            .distinct()
            .mapNotNull { pkg ->
                runCatching {
                    IconPack(pkg, pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString())
                }.getOrNull()
            }
            .let { packs -> LabelOrder.sorted(packs) { it.label } }
    }

    // Parse the loaded pack again, for one updated in place.
    @Synchronized
    fun isLoaded(packageName: String?): Boolean = packageName == loadedPackage

    fun loadedPack(): String? = loadedPackage

    fun reload() {
        val pack = loadedPackage ?: return
        loadedPackage = null
        load(pack)
    }

    // The launcher turned dark or light. True when the loaded pack's icons
    // change with it: its resources are opened afresh for the new mode, and
    // the caller has to draw every icon again. A pack with no night
    // resources keeps its icons, so a theme flip costs it nothing.
    @Synchronized
    fun setNight(value: Boolean): Boolean {
        val changed = value != night
        night = value
        val resources = loadedPackage?.takeIf { changed && followsNight }?.let(::openResources) ?: return false
        packResources = resources
        loadDecoration()
        return true
    }

    // The pack's plates, drawn from its resources as they now are.
    private fun loadDecoration() {
        backDrawables = backNames.mapNotNull { drawableByName(it) }
        maskDrawable = maskName?.let { drawableByName(it) }
        uponDrawable = uponName?.let { drawableByName(it) }
    }

    // What the loaded pack's icons were drawn under, for the disk cache's
    // signature: the mode for a day/night pack, else nothing.
    fun themeKey(): String = if (followsNight) (if (night) "night" else "day") else ""

    // Another package's resources under the launcher's dark or light; the
    // loaded pack's are kept, for the per-app picks drawn from it.
    fun resourcesFor(packageName: String): Resources? =
        packResources?.takeIf { packageName == loadedPackage } ?: openResources(packageName)

    // The package manager's copy follows the system's mode instead.
    private fun openResources(packageName: String): Resources? = runCatching {
        val base = context.resources.configuration
        val config = Configuration(base).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        context.createPackageContext(packageName, 0).createConfigurationContext(config).resources
    }.getOrNull() ?: runCatching { pm.getResourcesForApplication(packageName) }.getOrNull()

    @Synchronized
    fun load(packageName: String?) {
        if (packageName == loadedPackage) return
        forget()
        loadedPackage = packageName
        if (packageName.isNullOrBlank()) return

        val resources = openResources(packageName) ?: return
        packResources = resources

        val map = mutableMapOf<String, String>()
        val byPackage = mutableMapOf<String, String>()
        val calendars = mutableMapOf<String, String>()
        val backs = mutableListOf<String>()
        var mask: String? = null
        var upon: String? = null

        readAppFilter(resources, packageName) { parser -> runLogged("parse icon pack $packageName") {
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType != XmlPullParser.START_TAG) continue
                when (parser.name) {
                    "item" -> {
                        val component = parser.getAttributeValue(null, "component")
                        val drawable = parser.getAttributeValue(null, "drawable")
                        if (component != null && drawable != null) {
                            normalizeComponent(component)?.let {
                                map[it] = drawable
                                byPackage.putIfAbsent(it.substringBefore('/'), drawable)
                            }
                        }
                    }
                    "calendar" -> {
                        val component = parser.getAttributeValue(null, "component")
                        val prefix = parser.getAttributeValue(null, "prefix")
                        if (component != null && prefix != null) {
                            normalizeComponent(component)?.let { calendars[it] = prefix }
                        }
                    }
                    "iconback" -> parser.getAttributeValue(null, "img1")?.let { backs.add(it) }
                    "iconmask" -> mask = parser.getAttributeValue(null, "img1")
                    "iconupon" -> upon = parser.getAttributeValue(null, "img1")
                    "scale" -> iconScale = parser.getAttributeValue(null, "factor")?.toFloatOrNull() ?: 1f
                }
            }
        } } ?: return

        componentMap = map
        packageFallback = byPackage
        calendarMap = calendars
        backNames = backs
        maskName = mask
        uponName = upon
        decodeDensity = bitmapDensity(map.values, resources)
        loadDecoration()
        followsNight = declaresNight(packageName) ||
            (backDrawables + listOfNotNull(maskDrawable, uponDrawable)).any(::changesByNight) ||
            sampleChangesByNight(map.values, resources)
    }

    // Nothing loaded: what load starts from.
    private fun forget() {
        componentMap = emptyMap()
        packageFallback = emptyMap()
        calendarMap = emptyMap()
        packResources = null
        maskDrawable = null
        backDrawables = emptyList()
        uponDrawable = null
        backNames = emptyList()
        maskName = null
        uponName = null
        followsNight = false
        decodeDensity = 0
        iconScale = 1f
    }

    private var backNames: List<String> = emptyList()
    private var maskName: String? = null
    private var uponName: String? = null

    // The flag day/night packs put in their manifest (on the application or
    // the activity launchers find them by). Not every pack has it.
    private fun declaresNight(packageName: String): Boolean = runCatching {
        @Suppress("DEPRECATION")
        val info = pm.getPackageInfo(packageName, PackageManager.GET_META_DATA or PackageManager.GET_ACTIVITIES)
        val bundles = listOfNotNull(info.applicationInfo?.metaData) +
            info.activities.orEmpty().mapNotNull { it.metaData }
        bundles.any { it.containsKey(NIGHT_FLAG) }
    }.getOrDefault(false)

    // Packs without the flag: a few of its icons, spread over the appfilter,
    // asked whether they vary by night - a -night drawable, or a vector
    // whose colours have -night values, both report it.
    @android.annotation.SuppressLint("DiscouragedApi")
    private fun sampleChangesByNight(names: Collection<String>, resources: Resources): Boolean {
        val distinct = names.distinct()
        if (distinct.isEmpty()) return false
        val step = (distinct.size / NIGHT_SAMPLES).coerceAtLeast(1)
        return distinct.indices.step(step).take(NIGHT_SAMPLES).any { index ->
            val id = resources.getIdentifier(distinct[index], "drawable", loadedPackage)
            id != 0 && decode(resources, id)?.let(::changesByNight) == true
        }
    }

    // The density the pack's bitmaps are decoded at: the bucket they ship
    // in, so the decoder doesn't resample each one to the screen's density
    // (#288: a pack of xxxhdpi-only art on a 450 dpi phone spent three times
    // as long resampling each icon as decoding it). The icon is scaled to
    // its size when it is drawn anyway. 0, the screen's own, for packs of
    // vectors or nodpi art, which aren't resampled.
    private var decodeDensity = 0

    // A few of the pack's icons, followed to the bitmaps they draw (an
    // adaptive icon's layers, a <bitmap>'s src), and the bucket those are in
    // when asked for the highest.
    @android.annotation.SuppressLint("DiscouragedApi")
    private fun bitmapDensity(names: Collection<String>, resources: Resources): Int {
        val value = TypedValue()
        val found = names.asSequence().distinct().take(DENSITY_SAMPLES).flatMap { name ->
            val id = resources.getIdentifier(name, "drawable", loadedPackage)
            if (id == 0) emptySequence() else sequenceOf(id) + xmlReferences(resources, id, value)
        }.mapNotNull { id ->
            runCatching { resources.getValueForDensity(id, DisplayMetrics.DENSITY_XXXHIGH, value, true) }
                .getOrNull() ?: return@mapNotNull null
            val file = value.string?.toString().orEmpty()
            value.density.takeIf { file.endsWith(".png") || file.endsWith(".webp") || file.endsWith(".jpg") }
        }.filter { it in 1 until TypedValue.DENSITY_NONE }
        return found.maxOrNull() ?: 0
    }

    // The resources an xml drawable points at, one level down.
    private fun xmlReferences(resources: Resources, id: Int, value: TypedValue): Sequence<Int> {
        val isXml = runCatching { resources.getValue(id, value, true) }.isSuccess &&
            value.string?.endsWith(".xml") == true
        if (!isXml) return emptySequence()
        val refs = mutableListOf<Int>()
        runCatching {
            resources.getXml(id).use { parser ->
                while (parser.next() != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType != XmlPullParser.START_TAG) continue
                    for (i in 0 until parser.attributeCount) {
                        parser.getAttributeResourceValue(i, 0).takeIf { it != 0 }?.let(refs::add)
                    }
                }
            }
        }
        return refs.asSequence()
    }

    private fun decode(resources: Resources, id: Int): Drawable? =
        runCatching { resources.getDrawableForDensity(id, decodeDensity, null) }.getOrNull()

    private fun changesByNight(drawable: Drawable): Boolean =
        drawable.changingConfigurations and ActivityInfo.CONFIG_UI_MODE != 0

    fun themedIcon(packageName: String, className: String): Drawable? {
        val resources = packResources ?: return null
        val drawableName = drawableFor(componentMap, packageFallback, packageName, className)
            ?: return null
        return drawableByName(drawableName, resources)
    }

    fun hasCalendar(packageName: String, className: String): Boolean =
        calendarMap.containsKey("$packageName/$className")

    // The dynamic calendar icon for a given day-of-month (1..31); null when
    // the pack has no calendar entry for this component or the drawable for
    // that day is missing.
    fun calendarDrawable(packageName: String, className: String, dayOfMonth: Int): Drawable? {
        val prefix = calendarMap["$packageName/$className"] ?: return null
        return drawableByName(calendarDrawableName(prefix, dayOfMonth))
    }

    // The plate a pack puts behind an unthemed icon. Packs ship several
    // iconbacks so a screen of unthemed apps doesn't come out identical; which
    // one an app gets is picked from its own component name, so it is stable
    // across launches and cache rebuilds but still varied across the drawer.
    // component is null where there is nothing to key on, which takes the
    // first back, the way this always behaved.
    fun decoration(component: String? = null): IconPackDecoration? {
        if (loadedPackage.isNullOrBlank()) return null
        if (backDrawables.isEmpty() && maskDrawable == null && uponDrawable == null) return null
        val back = when {
            backDrawables.isEmpty() -> null
            component == null -> backDrawables.first()
            else -> backDrawables[backIndex(component, backDrawables.size)]
        }
        return IconPackDecoration(
            back = back,
            mask = maskDrawable,
            upon = uponDrawable,
            scale = iconScale
        )
    }

    // Lists a pack's pickable drawables without touching the loaded-pack
    // state: the pack's own icon list (drawable.xml, which also carries the
    // generic icons no app maps to; a few packs name it drawables.xml), else
    // the appfilter entries.
    fun listDrawables(packageName: String): List<String> {
        val resources = resourcesFor(packageName) ?: return emptyList()
        val names = linkedSetOf<String>()
        val collect: (XmlPullParser) -> Unit = { parser ->
            runLogged("read icon pack drawable list") {
                while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == XmlPullParser.START_TAG && parser.name == "item") {
                        parser.getAttributeValue(null, "drawable")?.let(names::add)
                    }
                    parser.next()
                }
            }
        }

        for (list in DRAWABLE_LISTS) {
            if (names.isNotEmpty()) break
            readPackXml(resources, packageName, list, collect)
        }
        if (names.isEmpty()) readPackXml(resources, packageName, "appfilter", collect)

        return names.filter { resources.getIdentifier(it, "drawable", packageName) != 0 }.sorted()
    }

    // A pack's icons for one app, best first (see matchOrder), for the top
    // of the picker's grid. Reads the pack's appfilter without touching the
    // loaded-pack state, so it works for any pack, applied or not.
    fun matchesFor(packPackage: String, packageName: String, className: String): List<String> {
        val resources = resourcesFor(packPackage) ?: return emptyList()
        val entries = mutableListOf<Pair<String, String>>()
        readAppFilter(resources, packPackage) { parser ->
            runLogged("read icon pack matches") {
                while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == XmlPullParser.START_TAG && parser.name == "item") {
                        val component = parser.getAttributeValue(null, "component")?.let(::normalizeComponent)
                        val drawable = parser.getAttributeValue(null, "drawable")
                        if (component != null && drawable != null &&
                            component.substringBefore('/') == packageName
                        ) {
                            entries.add(component to drawable)
                        }
                    }
                    parser.next()
                }
            }
        }
        return matchOrder(entries, "$packageName/$className")
            .filter { resources.getIdentifier(it, "drawable", packPackage) != 0 }
    }

    private fun <T> readAppFilter(resources: Resources, packageName: String, block: (XmlPullParser) -> T): T? =
        readPackXml(resources, packageName, "appfilter", block)

    // Runs block over one of the pack's xml files (name without ".xml") and
    // closes what it opened: a compiled resource's parser holds native
    // memory, a raw asset a stream, until closed. Null when the pack has no
    // such file.
    private fun <T> readPackXml(
        resources: Resources,
        packageName: String,
        name: String,
        block: (XmlPullParser) -> T
    ): T? {
        val xmlId = resources.getIdentifier(name, "xml", packageName)
        if (xmlId != 0) {
            val parser = runCatching { resources.getXml(xmlId) }.getOrNull() ?: return null
            return try {
                block(parser)
            } finally {
                parser.close()
            }
        }
        // Some packs ship their xml uncompiled instead: as an asset, or in
        // res/raw (the generated packs of icon pack maker apps).
        val rawId = resources.getIdentifier(name, "raw", packageName)
        val stream = runCatching { resources.assets.open("$name.xml") }.getOrNull()
            ?: rawId.takeIf { it != 0 }?.let { id -> runCatching { resources.openRawResource(id) }.getOrNull() }
            ?: return null
        return stream.use { input ->
            val parser = runCatching {
                android.util.Xml.newPullParser().apply { setInput(input, null) }
            }.getOrNull() ?: return null
            block(parser)
        }
    }

    // A pack names its drawables in appfilter; another app's resources can
    // only be looked up by name.
    @android.annotation.SuppressLint("DiscouragedApi")
    private fun drawableByName(name: String, resources: Resources? = packResources): Drawable? {
        val res = resources ?: return null
        val pkg = loadedPackage ?: return null
        val id = res.getIdentifier(name, "drawable", pkg)
        if (id == 0) return null
        return decode(res, id)
    }

    companion object {
        // How icon packs advertise themselves: the ADW and GO theme actions,
        // a third launcher's THEME action, and Apex's category on a MAIN
        // activity. Most packs declare several; some only one of these.
        // Each needs a matching <queries> entry in the manifest.
        private val PACK_INTENTS
            get() = listOf(
                Intent("org.adw.launcher.THEMES"),
                Intent("com.gau.go.launcherex.theme"),
                Intent("com.novalauncher.THEME"),
                Intent(Intent.ACTION_MAIN).addCategory("com.anddoes.launcher.THEME")
            )

        // The pack's own icon list, under the names packs ship it as.
        private val DRAWABLE_LISTS = listOf("drawable", "drawables")

        // Manifest meta-data a pack sets when its icons differ by night.
        const val NIGHT_FLAG = "org.icontheme.CHANGES_WITH_DARK_MODE"

        // How many of a flagless pack's icons are tried for night variants.
        private const val NIGHT_SAMPLES = 12

        // How many of a pack's icons are followed to find its bitmaps' density.
        private const val DENSITY_SAMPLES = 6

        // Pack convention: the icon for day N is `prefix` + N (1..31).
        fun calendarDrawableName(prefix: String, dayOfMonth: Int): String = "$prefix$dayOfMonth"

        // Which drawable a pack has for an app. The exact component is the
        // pack author's intent and always wins; the package fallback is what
        // makes packs keep working when an app renames its launcher activity,
        // which they do constantly and packs are never updated for.
        fun drawableFor(
            byComponent: Map<String, String>,
            byPackage: Map<String, String>,
            packageName: String,
            className: String
        ): String? = byComponent["$packageName/$className"] ?: byPackage[packageName]

        // One app's appfilter entries (component to drawable) as the picker
        // offers them: the exact component's drawables, then the rest of the
        // package's (an app's other or older launcher activities, which
        // often carry the pack's alternates), each drawable once.
        fun matchOrder(entries: List<Pair<String, String>>, component: String): List<String> {
            val (exact, others) = entries.partition { it.first == component }
            return (exact + others).map { it.second }.distinct()
        }

        // Which of a pack's iconbacks an app gets. Keyed off the component so
        // the choice is the same on every launch - a plate that changed when
        // the icon cache was rebuilt would read as a glitch - while still
        // spreading a pack's backs across a screen of apps.
        fun backIndex(component: String, count: Int): Int {
            if (count <= 1) return 0
            return ((component.hashCode().toLong() and 0xFFFFFFFFL) % count).toInt()
        }
    }

    private fun normalizeComponent(raw: String): String? {
        // appfilter entries look like ComponentInfo{pkg/cls}
        val inner = raw.substringAfter("ComponentInfo{", raw).substringBefore("}")
        if (!inner.contains('/')) return null
        val component = ComponentName.unflattenFromString(inner) ?: return null
        return "${component.packageName}/${component.className}"
    }
}

data class IconPackDecoration(
    val back: Drawable?,
    val mask: Drawable?,
    val upon: Drawable?,
    val scale: Float
)
