package dev.kifranei.ampp.media

/** Only reuse completed row measurements of the same native layout and window. */
internal class PlayerVolumePanePreparation {
    data class Window(val width: Int, val height: Int, val configuration: String)
    data class Row(val id: Int, val layoutWidth: Int, val layoutHeight: Int,
        val paddingTop: Int, val paddingBottom: Int, val intrinsicHeight: Int)
    private var window: Window? = null
    private val heights = HashMap<List<Row>, Int>()

    private fun select(value: Window): Boolean {
        if (window != value) { window = value; heights.clear() }
        return value.width > 0 && value.height > 0
    }

    fun remember(window: Window, rows: List<Row>, requiredHeight: Int) {
        if (select(window) && rows.isNotEmpty() && requiredHeight > 0) heights[rows.toList()] = requiredHeight
    }

    fun required(window: Window, rows: List<Row>): Int? =
        if (select(window)) heights[rows] else null
}

/** Let a changed native layout settle, without starving drawing if AM keeps changing it. */
internal class PlayerVolumeDrawSettlement {
    private var pending = 0
    fun allow(layoutChanged: Boolean): Boolean {
        if (!layoutChanged) { pending = 0; return true }
        if (pending < 2) { pending++; return false }
        return true
    }
}
