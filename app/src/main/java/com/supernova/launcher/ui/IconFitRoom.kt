package com.supernova.launcher.ui

// The room the launcher last laid icons out in, and the one size every
// surface draws an icon at.
//
// An icon is the size its Icon size bar asks for - BASE_DP at that
// percentage - on the home screen, in the dock and in the app drawer alike,
// and labels never enter that arithmetic: turning them on or off, or
// changing their size, moves the label, not the icon. The only thing that
// shrinks an icon is a cell too small to hold it inside PADDING_DP, and then
// every surface that follows the desktop's size shrinks with it
// (LauncherActivity.desktopDrawnIconBoxPx), so the three stay equal (#383).
//
// Settings runs in this process but can't measure the home screen or the
// drawer itself, so the Icon size rows read the room from here; 0 = not laid
// out yet, and no hint.
object IconFitRoom {
    // The icon box at 100%, and the padding between an icon and the edge of
    // its cell, slot or tile: the same on every surface, so the same size
    // leaves the same gap between neighbours wherever it is drawn.
    const val BASE_DP = 48
    const val PADDING_DP = 4

    // A desktop page's canvas, px.
    @Volatile var desktopWidthPx = 0
    @Volatile var desktopHeightPx = 0
    // The page padding (desktopPadding prefs) around that canvas, px.
    @Volatile var desktopMarginHPx = 0
    @Volatile var desktopMarginVPx = 0

    // The drawer grid's list height, px, without its padding.
    @Volatile var drawerHeightPx = 0

    // How far a desktop icon `boxPx` square, keeping `paddingPx` to its
    // cell's edges, shrinks into a `cellW` x `cellH` cell (cellSize): 1
    // where it fits. The label is not in it - it takes the room the icon
    // leaves instead.
    fun desktopFitScale(cellW: Int, cellH: Int, boxPx: Int, paddingPx: Int): Float =
        if (boxPx <= 0) 1f else fittedIconPx(cellW, cellH, boxPx, paddingPx) / boxPx.toFloat()

    fun desktopFitted(cellW: Int, cellH: Int, boxPx: Int, paddingPx: Int): Boolean =
        desktopFitScale(cellW, cellH, boxPx, paddingPx) < 1f

    // The box a desktop icon is drawn at in the cell a `cols` x `rows` grid
    // has in the room the desktop last laid out in: the one size every
    // surface that follows the desktop draws, so all of them agree. The box
    // asked for while the desktop has no room yet, and wherever it fits.
    fun desktopDrawnBoxPx(cols: Int, rows: Int, boxPx: Int, paddingPx: Int): Int {
        val cell = desktopCell(cols, rows) ?: return boxPx
        return fittedIconPx(cell.first, cell.second, boxPx, paddingPx)
    }

    // The same as a scale of the box asked for, for surfaces that size
    // themselves from a scale rather than a box.
    fun desktopFit(cols: Int, rows: Int, boxPx: Int, paddingPx: Int): Float =
        if (boxPx <= 0) 1f else desktopDrawnBoxPx(cols, rows, boxPx, paddingPx) / boxPx.toFloat()

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
    // `rowPx` row (0 = rows at the tile's height) too short for it. The
    // label is not in it, as on the desktop.
    fun drawerFitted(tileWidthPx: Int, sidePaddingPx: Int, rowPx: Int, iconPx: Int): Boolean {
        val room = tileWidthPx - sidePaddingPx * 2
        if (tileWidthPx > 0 && room in 1 until iconPx) return true
        return rowPx > 0 && iconPx > rowPx
    }
}
