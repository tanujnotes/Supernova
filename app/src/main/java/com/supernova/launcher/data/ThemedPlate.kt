package com.supernova.launcher.data

import android.graphics.drawable.ColorDrawable

// The plate behind a themed icon's glyph. Glass knows it by this type: it
// tints the pane with the plate's colour and draws the glyph whole, rather
// than reading the glyph for a tile of its own to clear.
class ThemedPlate(color: Int) : ColorDrawable(color)
