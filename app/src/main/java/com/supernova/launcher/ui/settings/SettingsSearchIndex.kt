package com.supernova.launcher.ui.settings

import android.content.Context
import androidx.annotation.StringRes
import com.supernova.launcher.data.LauncherPrefs
import com.supernova.launcher.ui.LauncherViewModel

// A flat, resolved-string index over every catalogue row, built once and
// searched by plain substring, word by word. Matching is title + section title only: the
// catalogue's titles are already the user-facing vocabulary, so a keywords
// side-channel wasn't worth threading through every SettingItem constructor.
class SettingsSearchIndex private constructor(val entries: List<Entry>) {

    class Entry(
        @StringRes val sectionTitleRes: Int,
        @StringRes val itemTitleRes: Int,
        val sectionLabel: String,
        val itemLabel: String,
        // Lowercased "item section" haystack, prepared once at build time so
        // per-keystroke matching allocates nothing per entry.
        val haystack: String,
        // The row's own visibleIf: a row its page isn't showing can't be
        // landed on, so it isn't offered.
        val visibleIf: (LauncherPrefs) -> Boolean = { true }
    )

    fun search(query: String, prefs: LauncherPrefs, limit: Int = Int.MAX_VALUE): List<Entry> {
        // Each word on its own, so "dock labels" finds the Dock page's
        // Icon labels: a row's title doesn't repeat its page's name.
        val words = query.trim().lowercase().split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val matches = ArrayList<Entry>()
        for (entry in entries) {
            if (words.all { entry.haystack.contains(it) } && entry.visibleIf(prefs)) {
                matches.add(entry)
                if (matches.size >= limit) break
            }
        }
        return matches
    }

    companion object {

        // Built from the declarative catalogue with inert Actions: the index
        // needs only titles and visibleIf, never behavior. Every row is
        // indexed; search() drops the ones the current prefs hide, so a
        // result always lands on a row its page shows (#126).
        fun build(context: Context, viewModel: LauncherViewModel): SettingsSearchIndex {
            val actions = SettingsCatalogue.Actions()
            val sections = SettingsCatalogue.sections(context, viewModel, actions) +
                SettingsCatalogue.styleSection(context, viewModel, actions)
            val entries = buildList {
                for (section in sections) {
                    val sectionLabel = context.getString(section.title)
                    for (item in section.items) {
                        // Preview headers carry no title and aren't rows;
                        // group labels and accordion toggles aren't settings.
                        if (item.title == 0) continue
                        if (item is SettingItem.Header || item is SettingItem.Accordion) continue
                        // A row's inner controls are listed under their own
                        // names but lead to the row that holds them.
                        for (titleRes in listOf(item.title) + item.searchTitles) {
                            val itemLabel = context.getString(titleRes)
                            add(
                                Entry(
                                    sectionTitleRes = section.title,
                                    itemTitleRes = item.title,
                                    sectionLabel = sectionLabel,
                                    itemLabel = itemLabel,
                                    haystack = "${itemLabel.lowercase()} ${sectionLabel.lowercase()}",
                                    visibleIf = item.visibleIf
                                )
                            )
                        }
                    }
                }
            }
            return SettingsSearchIndex(entries)
        }
    }
}
