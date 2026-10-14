package com.supernova.launcher.ui

// The drawer list as the rows its grid lays out, for the scrollbar: which
// items share a row, and how tall each row is. Heights are measured as rows
// are laid out and kept; a row not seen yet counts as tall as most rows are,
// so the frequent row (one item, two rows tall) and the odd two-line label
// don't skew the guess. Offsets run down from the top of the first row.
internal class ScrollRows {

    // The first item of each row, then one past the last item.
    private var starts = IntArray(1)
    // Each row's height; 0 until the row has been laid out.
    private var heights = IntArray(0)
    private var typical = 0
    private var typicalStale = false

    val rowCount: Int get() = heights.size
    val itemCount: Int get() = starts[rowCount]
    // The whole list's height.
    val total: Int get() = top(rowCount)

    // Groups the items the way GridLayoutManager does: a row takes items
    // until the next one would overflow it. The measured heights stay when
    // the rows come out the same, as after a rebind that moved nothing.
    fun group(itemCount: Int, spanCount: Int, spanSize: (Int) -> Int) {
        val next = IntArray(itemCount + 1)
        var rows = 0
        var used = 0
        for (position in 0 until itemCount) {
            val size = spanSize(position).coerceIn(1, spanCount)
            if (position == 0 || used + size > spanCount) {
                next[rows++] = position
                used = 0
            }
            used += size
        }
        next[rows] = itemCount
        val grouped = next.copyOf(rows + 1)
        if (grouped.contentEquals(starts)) return
        starts = grouped
        heights = IntArray(rows)
        typicalStale = true
    }

    // After a width change every row may measure differently.
    fun forgetHeights() {
        heights.fill(0)
        typicalStale = true
    }

    fun record(row: Int, height: Int) {
        if (row !in heights.indices || height <= 0 || heights[row] == height) return
        heights[row] = height
        typicalStale = true
    }

    fun rowOf(position: Int): Int {
        var low = 0
        var high = rowCount - 1
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (starts[mid] <= position) low = mid else high = mid - 1
        }
        return low
    }

    fun firstPosition(row: Int): Int = starts[row]

    // How far down the list a row starts.
    fun top(row: Int): Int {
        var sum = 0
        for (index in 0 until row.coerceAtMost(rowCount)) sum += heightOf(index)
        return sum
    }

    // The row at `offset` down the list; the last one past the end.
    fun rowAt(offset: Int): Int {
        var top = 0
        for (row in 0 until rowCount) {
            top += heightOf(row)
            if (offset < top) return row
        }
        return (rowCount - 1).coerceAtLeast(0)
    }

    // The item at `offset`: its row, and across that row in proportion to
    // how far into it the offset falls.
    fun positionAt(offset: Int): Int {
        if (rowCount == 0) return 0
        val row = rowAt(offset)
        val height = heightOf(row).coerceAtLeast(1)
        val into = (offset - top(row)).coerceIn(0, height - 1)
        val items = starts[row + 1] - starts[row]
        return starts[row] + into * items / height
    }

    private fun heightOf(row: Int): Int = heights[row].takeIf { it > 0 } ?: typicalHeight()

    // The height most measured rows share; on a tie, the shorter one, as
    // the frequent row is the tall one.
    private fun typicalHeight(): Int {
        if (!typicalStale) return typical
        typicalStale = false
        val known = heights.filter { it > 0 }.sorted()
        var best = 0
        var bestRun = 0
        var run = 0
        for (index in known.indices) {
            run = if (index > 0 && known[index] == known[index - 1]) run + 1 else 1
            if (run > bestRun) {
                bestRun = run
                best = known[index]
            }
        }
        typical = best
        return typical
    }
}
