package com.supernova.launcher.ui.settings

import android.content.Context
import androidx.annotation.StringRes
import com.supernova.launcher.data.LauncherPrefs
import com.supernova.launcher.ui.LauncherViewModel

// A flat, resolved-string index over every catalogue row, built once and
// searched by plain substring, word by word. Matching is title + section
// title, plus the few extra words a row is given (SettingItem.searchableBy)
// where people would look for it by another name.
class SettingsSearchIndex internal constructor(val entries: List<Entry>) {

    class Entry(
        @StringRes val sectionTitleRes: Int,
        // The catalogue row the entry finds, for its title and visibility.
        val row: SettingItem,
        val sectionLabel: String,
        val itemLabel: String,
        // Lowercased "item section" haystack, prepared once at build time so
        // per-keystroke matching allocates nothing per entry.
        val haystack: String
    ) {
        @get:StringRes
        val itemTitleRes: Int get() = row.title

        // The setting that hides the row now, if one does: the row is still
        // offered, and the hit names that setting and leads to it (#133).
        fun hiddenBy(prefs: LauncherPrefs): SettingItem.HiddenBy? = row.hiddenBy(prefs)
    }

    fun search(query: String, prefs: LauncherPrefs, limit: Int = Int.MAX_VALUE): List<Entry> {
        // Each word on its own, so "dock labels" finds the Dock page's
        // Icon labels: a row's title doesn't repeat its page's name.
        val words = query.trim().lowercase().split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val matches = ArrayList<Entry>()
        for (entry in entries) {
            // visibleIf is what no setting can change (Android version,
            // work apps): a row it rules out isn't offered (#126).
            if (words.all { entry.haystack.contains(it) } && entry.row.visibleIf(prefs)) {
                matches.add(entry)
                if (matches.size >= limit) break
            }
        }
        return matches
    }

    companion object {

        // Built from the declarative catalogue with inert Actions: the index
        // needs only titles and visibility, never behavior. Every row is
        // indexed; search() drops the ones visibleIf rules out, and keeps
        // the ones a setting hides, to name that setting (#126, #133).
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
                        val keywords = if (item.keywords != 0) context.getString(item.keywords).lowercase() else ""
                        for (titleRes in listOf(item.title) + item.searchTitles) {
                            val itemLabel = context.getString(titleRes)
                            add(
                                Entry(
                                    sectionTitleRes = section.title,
                                    row = item,
                                    sectionLabel = sectionLabel,
                                    itemLabel = itemLabel,
                                    haystack = "${itemLabel.lowercase()} ${sectionLabel.lowercase()} $keywords"
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
