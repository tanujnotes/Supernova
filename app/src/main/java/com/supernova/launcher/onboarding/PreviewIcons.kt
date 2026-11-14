package com.supernova.launcher.onboarding

import android.content.Context
import android.graphics.drawable.Drawable
import com.supernova.launcher.data.CustomShapeSpec
import com.supernova.launcher.data.IconGlass
import com.supernova.launcher.data.IconRenderer
import com.supernova.launcher.data.IconShape
import com.supernova.launcher.data.LauncherPrefs
import com.supernova.launcher.data.ReshapeLegacy
import com.supernova.launcher.runLogged
import java.util.concurrent.ConcurrentHashMap

// The first run's own renders of the preview's icons. A shape or a finish
// tapped re-renders every app's icon in the launcher, and a glass icon
// takes tens of milliseconds, so the preview waited most of a second for
// its handful. Here each option on the step is drawn ahead (prefetch), with
// a renderer and a lock of its own, so a tap shows icons already drawn.
//
// It draws what the launcher would for a fresh install: an app's own icon
// in the shape, glass, reshape and shadow chosen. Themed icons and icon
// packs it leaves to the launcher (handles), which reads them.
internal class PreviewIcons(context: Context, private val source: (component: String) -> Drawable?) {

    private data class Look(
        val shape: IconShape,
        val custom: String,
        val glass: IconGlass,
        val reshape: ReshapeLegacy,
        val shadow: Boolean
    )

    private val renderer = IconRenderer(context)
    private val sources = ConcurrentHashMap<String, Drawable>()
    private val missing = ConcurrentHashMap.newKeySet<String>()
    private val rendered = ConcurrentHashMap<Pair<String, Look>, Drawable>()

    fun handles(prefs: LauncherPrefs): Boolean = prefs.iconPack.isEmpty() && !prefs.themedIcons

    // Each component's icon in prefs' look, keyed by component; null until
    // every one of them has been drawn.
    fun cached(components: Collection<String>, prefs: LauncherPrefs): Map<String, Drawable>? {
        val look = lookOf(prefs)
        val wanted = components.filterNot { it in missing }
        val icons = wanted.mapNotNull { component -> rendered[component to look]?.let { component to it } }.toMap()
        return icons.takeIf { handles(prefs) && it.size == wanted.size }
    }

    // Draws whatever is not drawn yet, off the main thread. `keepGoing`
    // stops it between icons (a step left, a tap that goes first).
    @Synchronized
    fun render(components: Collection<String>, prefs: LauncherPrefs, keepGoing: () -> Boolean = { true }) {
        if (!handles(prefs)) return
        val look = lookOf(prefs)
        renderer.reshapeLegacy = look.reshape
        renderer.shadow = look.shadow
        val custom = CustomShapeSpec.decode(look.custom)
        for (component in components) {
            if (!keepGoing()) return
            if (!rendered.containsKey(component to look) && component !in missing) draw(component, look, custom)
        }
    }

    private fun draw(component: String, look: Look, custom: CustomShapeSpec?) {
        val raw = sources[component] ?: source(component)?.also { sources[component] = it }
        if (raw == null) {
            missing += component
            return
        }
        runLogged("render a preview icon") {
            renderer.render(raw, shape = look.shape, customShape = custom, glass = look.glass)
        }.getOrNull()?.let { rendered[component to look] = it }
    }

    private fun lookOf(prefs: LauncherPrefs) = Look(
        prefs.iconShape, prefs.iconShapeCustom, prefs.iconGlass, ReshapeLegacy.fromPrefs(prefs), prefs.iconShadow
    )
}
