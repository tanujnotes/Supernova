package com.supernova.launcher.ui

// The room the launcher last laid icons out in, for Settings' Icon size
// rows: icons are fitted to their cells (WorkspaceCanvas.IconFit,
// DrawerTileFit), so on a dense grid a larger size stops growing them, and
// the row says why. Settings runs in this process but can't measure the
// home screen or the drawer itself; 0 = not laid out yet, and no hint.
object IconFitRoom {
    // A desktop page's canvas, px.
    @Volatile var desktopWidthPx = 0
    @Volatile var desktopHeightPx = 0
    // The page padding (desktopPadding prefs) around that canvas, px.
    @Volatile var desktopMarginHPx = 0
    @Volatile var desktopMarginVPx = 0

    // The drawer grid's list height, px, without its padding.
    @Volatile var drawerHeightPx = 0

    // How far a desktop icon `boxPx` square, padded by `paddingPx` all round
    // over a `labelPx` label line (0 with labels off), shrinks into a
    // `cellW` x `cellH` cell (cellSize): 1 where it fits.
    fun desktopFitScale(cellW: Int, cellH: Int, boxPx: Int, paddingPx: Int, labelPx: Int): Float {
        val needW = boxPx + paddingPx * 2
        return iconFitScale(cellW, cellH, needW, needW + labelPx)
    }

    fun desktopFitted(cellW: Int, cellH: Int, boxPx: Int, paddingPx: Int, labelPx: Int): Boolean =
        desktopFitScale(cellW, cellH, boxPx, paddingPx, labelPx) < 1f

    // A desktop cell of a `cols` x `rows` grid as the launcher last laid its
    // pages out, or null before it has.
    fun desktopCell(cols: Int, rows: Int): Pair<Int, Int>? {
        val width = desktopWidthPx
        val height = desktopHeightPx
        if (width <= 0 || height <= 0) return null
        return cellSize(width, cols) to cellSize(height, rows)
    }

    // The cell the same grid would have under other page padding: the
    // Home screen preview shows a padding bar's value before the desktop
    // lays itself out again.
    fun desktopCell(cols: Int, rows: Int, marginHPx: Int, marginVPx: Int): Pair<Int, Int>? {
        val width = desktopWidthPx + 2 * (desktopMarginHPx - marginHPx)
        val height = desktopHeightPx + 2 * (desktopMarginVPx - marginVPx)
        val laidOut = desktopWidthPx > 0 && desktopHeightPx > 0
        if (!laidOut || width <= 0 || height <= 0) return null
        return cellSize(width, cols) to cellSize(height, rows)
    }

    // Whether a drawer icon `iconPx` square is held below its size: by a
    // tile `tileWidthPx` wide less its `sidePaddingPx` either side, or by a
    // `rowPx` row (0 = rows at the tile's height) that can't hold it over
    // its `labelPx` gap and one label line.
    fun drawerFitted(tileWidthPx: Int, sidePaddingPx: Int, rowPx: Int, iconPx: Int, labelPx: Int): Boolean {
        val room = tileWidthPx - sidePaddingPx * 2
        if (tileWidthPx > 0 && room in 1 until iconPx) return true
        return rowPx > 0 && iconPx + labelPx > rowPx
    }
}
