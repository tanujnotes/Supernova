package com.supernova.launcher.ui

import kotlin.math.abs

// The arithmetic behind moving an app inside an open folder
// (FolderReorderDrag), kept free of views so it can be tested.
internal object FolderReorderMath {

    // A laid-out member tile: its adapter position and its bounds on screen.
    data class Slot(val position: Int, val left: Float, val top: Float, val right: Float, val bottom: Float)

    // One entry taken from `from` and put back at `to`, everything between
    // shifting one place toward the gap it left.
    fun <T> moved(list: List<T>, from: Int, to: Int): List<T> {
        if (from !in list.indices || to !in list.indices || from == to) return list
        return list.toMutableList().apply { add(to, removeAt(from)) }
    }

    // The slot a finger at (x, y) aims at. A vertical grid fills row by row,
    // so the row is found first and the place in it second: a finger in the
    // empty end of the last row aims at the last app, not at the one above
    // it. A horizontal (paged) grid fills column by column, so the column
    // comes first. Tiles in a row can differ in height (a label wrapping to
    // two lines), so each row counts as tall as its tallest tile. -1 with no
    // slots.
    fun targetSlot(slots: List<Slot>, x: Float, y: Float, rowMajor: Boolean): Int {
        if (slots.isEmpty()) return -1
        val bands = if (rowMajor) {
            slots.groupBy { it.top }.mapValues { (_, row) -> row.maxOf { it.bottom } }
        } else {
            slots.groupBy { it.left }.mapValues { (_, column) -> column.maxOf { it.right } }
        }
        return slots.minWith(
            compareBy<Slot>(
                { if (rowMajor) gap(y, it.top, bands.getValue(it.top)) else gap(x, it.left, bands.getValue(it.left)) },
                { if (rowMajor) gap(x, it.left, it.right) else gap(y, it.top, it.bottom) },
                { it.position }
            )
        ).position
    }

    // How far v lies outside [start, end); 0 inside.
    private fun gap(v: Float, start: Float, end: Float): Float = when {
        v < start -> start - v
        v >= end -> v - end
        else -> 0f
    }

    // How far a grid held near one of its ends scrolls this frame: nothing
    // outside the edge bands, faster the deeper the finger is in one, up to
    // maxStep at the very edge and past it. Negative scrolls back toward the
    // start. pos, start and end lie along the grid's scroll axis.
    fun edgeScrollStep(pos: Float, start: Float, end: Float, band: Float, maxStep: Float): Float {
        if (band <= 0f || end - start <= band * 2) return 0f
        val intoStart = start + band - pos
        val intoEnd = pos - (end - band)
        return when {
            intoStart > 0f -> -maxStep * (intoStart / band).coerceAtMost(1f)
            intoEnd > 0f -> maxStep * (intoEnd / band).coerceAtMost(1f)
            else -> 0f
        }
    }

    // Whether the finger sits in an edge band at all (a paged grid flips a
    // page after a hover there): -1 the start, 1 the end, 0 neither.
    fun edgeSide(pos: Float, start: Float, end: Float, band: Float): Int {
        val step = edgeScrollStep(pos, start, end, band, 1f)
        return if (abs(step) > 0f) (if (step < 0f) -1 else 1) else 0
    }
}
