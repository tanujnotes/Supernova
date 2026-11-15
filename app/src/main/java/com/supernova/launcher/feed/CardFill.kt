package com.supernova.launcher.feed

// "Fill the page": the height a card's featured widget takes so the whole
// card, margins included, fits the feed list's visible height. chromePx is
// everything else the card draws (its padding, header, action chips and
// margins). Never below the stepper's minimum, so a short window (landscape
// with the header up) still shows a usable widget and the list scrolls.
object CardFill {
    fun widgetHeightPx(availablePx: Int, chromePx: Int, minPx: Int): Int =
        (availablePx - chromePx).coerceAtLeast(minPx)
}
