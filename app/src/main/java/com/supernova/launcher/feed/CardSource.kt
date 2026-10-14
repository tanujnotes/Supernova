package com.supernova.launcher.feed

import kotlinx.coroutines.flow.StateFlow

// A feed card backed by live device state. Sources are STRICTLY dormant while
// the feed pane is hidden: onShown registers listeners/observers, onHidden
// unregisters every one of them, and nothing may run in between. The feed
// controller collects `state` only between those two calls; null means "no
// card to show".
interface CardSource<S : Any> {
    val state: StateFlow<S?>

    fun onShown()

    fun onHidden()
}
