package com.supernova.launcher.ui

import android.content.Context
import android.graphics.drawable.Drawable
import com.supernova.launcher.R
import com.supernova.launcher.data.IconRenderer
import com.supernova.launcher.data.IconShape
import com.supernova.launcher.data.LauncherPrefs
import com.supernova.launcher.search.SearchChip
import com.supernova.launcher.search.WebProvider

// The "Search with" row both search surfaces show when a query matches nothing
// locally: the configured providers as brand marks. Shared so the app drawer
// and the global overlay answer an empty search identically.
object SearchMarks {

    // Deliberately below FrequentAppsRowView.APP_ICON_DP: these are providers,
    // not apps, and the size difference is what says so.
    const val MARK_DP = 32

    @androidx.annotation.StringRes
    fun labelOf(chip: SearchChip): Int = when (chip) {
        SearchChip.PLAY_STORE -> R.string.chip_play_store
        SearchChip.MAPS -> R.string.chip_maps
        SearchChip.YOUTUBE -> R.string.chip_youtube
        SearchChip.WIKIPEDIA -> R.string.chip_wikipedia
        SearchChip.SPOTIFY -> R.string.chip_spotify
        SearchChip.GOOGLE -> R.string.chip_google
    }

    // False with every provider unticked: there is no "Search with" to show.
    fun hasProviders(prefs: LauncherPrefs?): Boolean =
        SearchChip.decode(prefs?.searchChips.orEmpty()).isNotEmpty()

    // The web engine leads, so a web search stays reachable even when Google is
    // not one of the chips.
    fun chipsFor(prefs: LauncherPrefs?): List<SearchChip> {
        val configured = SearchChip.decode(prefs?.searchChips.orEmpty())
        val engine = when (prefs?.searchWebProvider ?: WebProvider.GOOGLE) {
            WebProvider.GOOGLE -> SearchChip.GOOGLE
            else -> null
        }
        return (listOfNotNull(engine) + configured).distinct()
    }

    fun entries(
        context: Context,
        prefs: LauncherPrefs?,
        renderer: IconRenderer,
        accentColor: Int,
        onTap: (SearchChip) -> Unit
    ): List<FrequentAppsRowView.Entry> = chipsFor(prefs).map { chip ->
        FrequentAppsRowView.Entry(
            context.getString(labelOf(chip)),
            markFor(context, chip, renderer, accentColor)
        ) { onTap(chip) }
    }

    // Rendered marks, by chip and the renderer's look: the row rebinds on
    // every keystroke, and loading and rendering each provider's icon again
    // was most of a keystroke's main-thread time. A null value is a provider
    // that isn't installed. Cleared when apps change (forget).
    private val rendered = HashMap<String, Drawable.ConstantState?>()

    fun forget() = synchronized(rendered) { rendered.clear() }

    private fun markFor(
        context: Context,
        chip: SearchChip,
        renderer: IconRenderer,
        accentColor: Int
    ): Drawable {
        val key = "${chip.name}|${renderer.iconSizePx}|${renderer.shadow}|${renderer.glass}|" +
            "${renderer.reshapeLegacy}|${renderer.normalizeSize}"
        val known = synchronized(rendered) { key in rendered }
        val installed = if (known) {
            synchronized(rendered) { rendered[key] }?.newDrawable(context.resources)
        } else {
            renderInstalled(context, chip, renderer).also { mark ->
                // A mark that can't be copied is rendered again next time.
                val state = mark?.constantState
                if (mark == null || state != null) synchronized(rendered) { rendered[key] = state }
            }
        }
        if (installed != null) return installed
        val label = context.getString(labelOf(chip))
        return MonogramDrawable(
            label.take(1).uppercase(),
            (0x33 shl 24) or (accentColor and 0xFFFFFF),
            accentColor.takeIf { it != 0 } ?: context.getColor(R.color.textPrimary)
        )
    }

    // Circles regardless of the icon-shape pref: these read as buttons in a
    // row of their own, not as launcher icons.
    private fun renderInstalled(context: Context, chip: SearchChip, renderer: IconRenderer): Drawable? =
        chip.iconPackage
            ?.let { pkg -> runCatching { context.packageManager.getApplicationIcon(pkg) }.getOrNull() }
            ?.let { renderer.render(it, shape = IconShape.CIRCLE) }
}
