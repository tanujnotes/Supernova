package com.supernova.launcher.ui.settings

import android.content.Context
import androidx.annotation.StringRes
import com.supernova.launcher.data.LauncherPrefs
import com.supernova.launcher.ui.LauncherViewModel

// A flat, resolved-string index over every catalogue row, built once and
// searched by plain substring, word by word. Matching is title + section
// title, plus the few extra words a row is given (SettingItem.searchableBy)
// where people would look for it by another name. Rows that match only
// with the help of their description line come after those (#218).
class SettingsSearchIndex internal constructor(val entries: List<Entry>) {

    class Entry(
        @StringRes val sectionTitleRes: Int,
        // The catalogue row the entry finds, for its title and visibility.
        val row: SettingItem,
        val sectionLabel: String,
        val itemLabel: String,
        // Lowercased "item section" haystack, prepared once at build time so
        // per-keystroke matching allocates nothing per entry.
        val haystack: String,
        // The row's fixed description, as shown, or "" for none.
        val description: String = ""
    ) {
        @get:StringRes
        val itemTitleRes: Int get() = row.title

        // The description's lowercased words, matched from their start.
        internal val descriptionWords: List<String> =
            description.lowercase().split(WORD_BREAK).filter { it.isNotEmpty() }

        // The setting that hides the row now, if one does: the row is still
        // offered, and the hit names that setting and leads to it (#133).
        fun hiddenBy(prefs: LauncherPrefs): SettingItem.HiddenBy? = row.hiddenBy(prefs)

        internal fun describedBy(word: String): Boolean =
            word.length >= MIN_DESCRIPTION_WORD && word !in STOP_WORDS &&
                descriptionWords.any { it.startsWith(word) }
    }

    // A found entry; byDescription when its description line was needed
    // to match, so the hit can show that line.
    class Hit(val entry: Entry, val byDescription: Boolean)

    fun search(query: String, prefs: LauncherPrefs, limit: Int = Int.MAX_VALUE): List<Hit> {
        // Each word on its own, so "dock labels" finds the Dock page's
        // Icon labels: a row's title doesn't repeat its page's name.
        val words = query.trim().lowercase().split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val byTitle = ArrayList<Hit>()
        val byDescription = ArrayList<Hit>()
        for (entry in entries) {
            // visibleIf is what no setting can change (Android version,
            // work apps): a row it rules out isn't offered (#126).
            val offered = entry.row.visibleIf(prefs)
            if (offered && words.all { entry.haystack.contains(it) }) {
                byTitle.add(Hit(entry, byDescription = false))
                if (byTitle.size >= limit) break
            } else if (offered && words.all { entry.haystack.contains(it) || entry.describedBy(it) }) {
                byDescription.add(Hit(entry, byDescription = true))
            }
        }
        return (byTitle + byDescription).take(limit)
    }

    companion object {

        private val WORD_BREAK = Regex("[^\\p{L}\\p{N}]+")

        // Short and filler words would pull in most descriptions.
        private const val MIN_DESCRIPTION_WORD = 3
        private val STOP_WORDS = setOf(
            "the", "and", "for", "you", "your", "its", "with", "from",
            "when", "while", "into", "onto", "are", "will", "than", "then"
        )

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
                        // names but lead to the row that holds them; the
                        // row's description is the row's own, not theirs.
                        val keywords = if (item.keywords != 0) context.getString(item.keywords).lowercase() else ""
                        val description = item.searchDescription.takeIf { it != 0 }?.let(context::getString).orEmpty()
                        for (titleRes in listOf(item.title) + item.searchTitles) {
                            val itemLabel = context.getString(titleRes)
                            add(
                                Entry(
                                    sectionTitleRes = section.title,
                                    row = item,
                                    sectionLabel = sectionLabel,
                                    itemLabel = itemLabel,
                                    haystack = "${itemLabel.lowercase()} ${sectionLabel.lowercase()} $keywords",
                                    description = if (titleRes == item.title) description else ""
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
