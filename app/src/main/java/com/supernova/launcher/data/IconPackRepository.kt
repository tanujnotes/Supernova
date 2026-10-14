package com.supernova.launcher.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.util.Log
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

    // The pack's own app label, for anywhere a raw package name would be
    // shown to a user. Falls back to the package name when the pack has been
    // uninstalled behind our back and the pref still names it.
    fun packLabel(packageName: String): String =
        runCatching { pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString() }
            .getOrDefault(packageName)

    fun availablePacks(): List<IconPack> {
        // Icon packs advertise themselves through one of these theme actions.
        val actions = listOf(
            "org.adw.launcher.THEMES",
            "com.gau.go.launcherex.theme"
        )
        return actions
            .flatMap { action ->
                runCatching { pm.queryIntentActivities(Intent(action), 0) }.getOrDefault(emptyList())
            }
            .map { it.activityInfo.packageName }
            .distinct()
            .mapNotNull { pkg ->
                runCatching {
                    IconPack(pkg, pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString())
                }.getOrNull()
            }
            .sortedBy { it.label.lowercase() }
    }

    // Parse the loaded pack again, for one updated in place.
    @Synchronized
    fun isLoaded(packageName: String?): Boolean = packageName == loadedPackage

    fun reload() {
        val pack = loadedPackage ?: return
        loadedPackage = null
        load(pack)
    }

    @Synchronized
    fun load(packageName: String?) {
        if (packageName == loadedPackage) return
        componentMap = emptyMap()
        packageFallback = emptyMap()
        calendarMap = emptyMap()
        packResources = null
        maskDrawable = null
        backDrawables = emptyList()
        uponDrawable = null
        iconScale = 1f
        loadedPackage = packageName
        if (packageName.isNullOrBlank()) return

        val resources = runCatching { pm.getResourcesForApplication(packageName) }.getOrNull() ?: return
        packResources = resources

        val map = mutableMapOf<String, String>()
        val byPackage = mutableMapOf<String, String>()
        val calendars = mutableMapOf<String, String>()
        val backs = mutableListOf<String>()
        var maskName: String? = null
        var uponName: String? = null

        readAppFilter(resources, packageName) { parser -> runCatching {
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
                    "iconmask" -> maskName = parser.getAttributeValue(null, "img1")
                    "iconupon" -> uponName = parser.getAttributeValue(null, "img1")
                    "scale" -> iconScale = parser.getAttributeValue(null, "factor")?.toFloatOrNull() ?: 1f
                }
            }
        }.onFailure { Log.w("Supernova", "Failed parsing icon pack $packageName", it) } } ?: return

        componentMap = map
        packageFallback = byPackage
        calendarMap = calendars
        backDrawables = backs.mapNotNull { drawableByName(it) }
        maskDrawable = maskName?.let { drawableByName(it) }
        uponDrawable = uponName?.let { drawableByName(it) }
    }

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
    // state: drawables.xml when the pack ships one, else appfilter entries.
    fun listDrawables(packageName: String): List<String> {
        val resources = runCatching { pm.getResourcesForApplication(packageName) }.getOrNull()
            ?: return emptyList()
        val names = linkedSetOf<String>()

        val drawablesId = resources.getIdentifier("drawables", "xml", packageName)
        if (drawablesId != 0) {
            runCatching {
                val parser = resources.getXml(drawablesId)
                try {
                    while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                        if (parser.eventType == XmlPullParser.START_TAG && parser.name == "item") {
                            parser.getAttributeValue(null, "drawable")?.let(names::add)
                        }
                        parser.next()
                    }
                } finally {
                    parser.close()
                }
            }
        }

        if (names.isEmpty()) {
            readAppFilter(resources, packageName) { parser ->
                runCatching {
                    while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                        if (parser.eventType == XmlPullParser.START_TAG && parser.name == "item") {
                            parser.getAttributeValue(null, "drawable")?.let(names::add)
                        }
                        parser.next()
                    }
                }
            }
        }

        return names.filter { resources.getIdentifier(it, "drawable", packageName) != 0 }.sorted()
    }

    // Runs block over the pack's appfilter and closes what it opened: a
    // compiled resource's parser holds native memory, a raw asset a stream,
    // until closed. Null when the pack has no appfilter.
    private fun <T> readAppFilter(resources: Resources, packageName: String, block: (XmlPullParser) -> T): T? {
        val xmlId = resources.getIdentifier("appfilter", "xml", packageName)
        if (xmlId != 0) {
            val parser = runCatching { resources.getXml(xmlId) }.getOrNull() ?: return null
            return try {
                block(parser)
            } finally {
                parser.close()
            }
        }
        // Some packs ship appfilter.xml as a raw asset instead of a compiled resource.
        val stream = runCatching { resources.assets.open("appfilter.xml") }.getOrNull() ?: return null
        return stream.use { input ->
            val parser = runCatching {
                android.util.Xml.newPullParser().apply { setInput(input, null) }
            }.getOrNull() ?: return null
            block(parser)
        }
    }

    private fun drawableByName(name: String, resources: Resources? = packResources): Drawable? {
        val res = resources ?: return null
        val pkg = loadedPackage ?: return null
        val id = res.getIdentifier(name, "drawable", pkg)
        if (id == 0) return null
        return runCatching { res.getDrawable(id, null) }.getOrNull()
    }

    companion object {
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
