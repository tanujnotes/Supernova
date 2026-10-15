package com.supernova.launcher.ui.settings

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.supernova.launcher.data.LauncherPrefs

// What a Preview row renders; each kind maps to one build/tick path in
// SettingsPreviewView. HOME and SEARCH_BAR are drawn over the wallpaper.
enum class PreviewKind { ICON_ROW, HOME, DOCK, FOLDER, DRAWER, SEARCH, SEARCH_BAR, DRAWER_SEARCH_BAR }

// One user-facing setting row. The catalogue declares these; the adapter
// renders them. Values are always read through the latest LauncherPrefs
// snapshot so rows never hold state of their own.
sealed class SettingItem {
    @get:StringRes
    abstract val title: Int
    abstract val visibleIf: (LauncherPrefs) -> Boolean

    // More names the settings search finds this row by: the controls a
    // row holds inside itself, which have no row of their own to match.
    open val searchTitles: List<Int> get() = emptyList()

    class Toggle(
        @StringRes override val title: Int,
        val get: (LauncherPrefs) -> Boolean,
        val set: (Boolean) -> Unit,
        override val visibleIf: (LauncherPrefs) -> Boolean = { true },
        // Most toggles carry an explanation under the title; 0 = none.
        @StringRes val subtitle: Int = 0
    ) : SettingItem()

    class Slider(
        @StringRes override val title: Int,
        val min: Int,
        val max: Int,
        val step: Int = 1,
        val get: (LauncherPrefs) -> Int,
        val set: (Int) -> Unit,
        val display: (Int) -> String,
        // Maps an uncommitted drag value onto a prefs snapshot so the section's
        // Preview row can render it without a DataStore round-trip.
        val preview: ((LauncherPrefs, Int) -> LauncherPrefs)? = null,
        override val visibleIf: (LauncherPrefs) -> Boolean = { true },
        // The max while Labs' Big grid size options is on; null = max.
        val bigGridMax: Int? = null
    ) : SettingItem() {
        fun maxFor(prefs: LauncherPrefs): Int = bigGridMax?.takeIf { prefs.labsBigGrid } ?: max
    }

    // Choice pickers, action buttons, and in-section links all render as a
    // tappable row with an optional current-value subtitle. A row for a
    // user-authored thing (a custom card) carries its name in titleText with
    // title = 0, which keeps it out of the settings search index.
    class Row(
        @StringRes override val title: Int,
        val subtitle: (LauncherPrefs) -> CharSequence? = { null },
        val onClick: () -> Unit,
        override val visibleIf: (LauncherPrefs) -> Boolean = { true },
        val titleText: CharSequence? = null
    ) : SettingItem()

    // An entry on the root list: a leading icon, a larger title,
    // and its description always under the title (never a value pill).
    class SectionLink(
        @StringRes override val title: Int,
        @DrawableRes val icon: Int,
        val subtitle: (LauncherPrefs) -> CharSequence? = { null },
        val onClick: () -> Unit,
        override val visibleIf: (LauncherPrefs) -> Boolean = { true }
    ) : SettingItem()

    // An accent-colored group label. Ends any accordion scope.
    class Header(
        @StringRes override val title: Int,
        override val visibleIf: (LauncherPrefs) -> Boolean = { true }
    ) : SettingItem()

    // A collapsible group toggle: the items after it (until the next Header,
    // Accordion, or Preview) hide while it is collapsed.
    class Accordion(
        @StringRes override val title: Int,
        override val visibleIf: (LauncherPrefs) -> Boolean = { true }
    ) : SettingItem()

    // The Label group: a switch in its header shows or hides the labels,
    // and the chevron opens a card holding their size, font, shadow and
    // single-line options side by side. It opens and closes like an
    // Accordion (same remembered state) but holds its controls itself, so
    // it hides no rows after it.
    class LabelGroup(
        @StringRes override val title: Int,
        val shown: (LauncherPrefs) -> Boolean,
        val setShown: (Boolean) -> Unit,
        val sizeMin: Int,
        val sizeMax: Int,
        val size: (LauncherPrefs) -> Int,
        val setSize: (Int) -> Unit,
        val sizeDisplay: (Int) -> String,
        val font: (LauncherPrefs) -> CharSequence,
        val pickFont: () -> Unit,
        val shadow: (LauncherPrefs) -> Boolean,
        val setShadow: (Boolean) -> Unit,
        val singleLine: (LauncherPrefs) -> Boolean,
        val setSingleLine: (Boolean) -> Unit,
        // As Slider.preview, for the size bar.
        val previewSize: ((LauncherPrefs, Int) -> LauncherPrefs)? = null,
        override val searchTitles: List<Int> = emptyList(),
        override val visibleIf: (LauncherPrefs) -> Boolean = { true }
    ) : SettingItem()

    // A Style-tab target row with a mini strip previewing its resolved
    // surface, text and accent (F6).
    class StyleTarget(
        @StringRes override val title: Int,
        val surface: com.supernova.launcher.ui.theme.SurfaceId,
        val onClick: () -> Unit,
        override val visibleIf: (LauncherPrefs) -> Boolean = { true }
    ) : SettingItem()

    // A Cards page entry: the card's name with a gear over a live
    // rendering of the card, which build inflates into the row. A null gear
    // is the rendering alone (the card's own settings page).
    class Card(
        val titleText: CharSequence,
        val build: (android.view.ViewGroup) -> android.view.View,
        val onGear: (() -> Unit)?,
        override val visibleIf: (LauncherPrefs) -> Boolean = { true }
    ) : SettingItem() {
        override val title: Int = 0
    }

    // A live preview header; has no title of its own.
    class Preview(
        val kind: PreviewKind,
        override val visibleIf: (LauncherPrefs) -> Boolean = { true }
    ) : SettingItem() {
        override val title: Int = 0
    }
}

class SettingsSection(
    @StringRes val title: Int,
    val items: List<SettingItem>
)

// A single choice the activity shows for a row, as a menu under the row:
// the title, the labels in order, the one that holds now (-1 for none),
// and what a pick writes. The catalogue builds these; see Actions.pickChoice.
class Choice(
    @StringRes val title: Int,
    val labels: List<CharSequence>,
    val selected: Int,
    val onPick: (Int) -> Unit
)
