package com.supernova.launcher.ui.settings

import android.content.Context
import androidx.annotation.StringRes
import com.supernova.launcher.ui.LauncherViewModel

// A flat, resolved-string index over every catalogue row, built once and
// searched by plain substring. Matching is title + section title only: the
// catalogue's titles are already the user-facing vocabulary, so a keywords
// side-channel wasn't worth threading through every SettingItem constructor.
class SettingsSearchIndex private constructor(val entries: List<Entry>) {

    class Entry(
        @StringRes val sectionTitleRes: Int,
        @StringRes val itemTitleRes: Int,
        val sectionLabel: String,
        val itemLabel: String,
        // Lowercased "item section" haystack, prepared once at build time so
        // per-keystroke matching allocates nothing.
        val haystack: String
    )

    fun search(query: String, limit: Int = Int.MAX_VALUE): List<Entry> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return emptyList()
        val matches = ArrayList<Entry>()
        for (entry in entries) {
            if (entry.haystack.contains(needle)) {
                matches.add(entry)
                if (matches.size >= limit) break
            }
        }
        return matches
    }

    companion object {

        // Built from the declarative catalogue with inert Actions: the index
        // needs only titles, never behavior. Items are indexed regardless of
        // their current visibleIf state so a hidden row is still findable;
        // navigation simply skips the highlight when the row isn't showing.
        fun build(context: Context, viewModel: LauncherViewModel): SettingsSearchIndex {
            val actions = SettingsCatalogue.Actions()
            val sections = SettingsCatalogue.sections(context, viewModel, actions) +
                SettingsCatalogue.styleSection(context, viewModel, actions)
            val entries = buildList {
                for (section in sections) {
                    // Labs stays hidden until it is asked for.
                    if (section.title == com.supernova.launcher.R.string.labs) continue
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
                                    haystack = "${itemLabel.lowercase()} ${sectionLabel.lowercase()}"
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
