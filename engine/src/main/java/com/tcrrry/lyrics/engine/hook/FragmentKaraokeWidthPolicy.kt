package com.tcrrry.lyrics.engine.hook

/** Native 1606 detached-row formula, also usable when RecyclerView reuses a cached row. */
object FragmentKaraokeWidthPolicy {
    fun initialize(currentWidth: Int, viewportWidth: Int, rowInsets: Iterable<Int>): Int? {
        if (currentWidth > 0 || viewportWidth <= 0) return null
        val inset = rowInsets.fold(0L) { total, value -> total + value }
        val width = viewportWidth.toLong() - inset
        return width.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()
    }
}
