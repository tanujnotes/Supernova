package com.supernova.launcher.ui

// What the drawer adds for the Work tab: a button that pauses work apps
// while they run, or, while they are paused, a card in their place that
// turns them back on. Search results stand in for both.
enum class WorkTabView {
    NONE,
    PAUSE_BUTTON,
    PAUSED_CARD;

    companion object {
        fun of(onWorkTab: Boolean, searching: Boolean, paused: Boolean): WorkTabView = when {
            !onWorkTab || searching -> NONE
            paused -> PAUSED_CARD
            else -> PAUSE_BUTTON
        }
    }
}
