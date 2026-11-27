package dev.kifranei.ampp.media

internal data class PlayerVolumeRegion(val left: Int, val top: Int, val width: Int, val height: Int)

internal data class PlayerVolumePlacement(val left: Int, val top: Int, val width: Int, val height: Int,
    val rootWidth: Int, val rootHeight: Int, val alpha: Float, val column: PlayerVolumeRegion? = null) {
    fun fits(width: Int, height: Int): Boolean {
        val region = column ?: PlayerVolumeRegion(0, height / 2, width, height - height / 2)
        return rootWidth == width && rootHeight == height &&
            this.width > 0 && this.height > 0 && region.width > 0 && region.height > 0 &&
            region.left >= 0 && region.top >= 0 && region.top.toLong() + region.height > height / 2 &&
            region.left.toLong() + region.width <= width && region.top.toLong() + region.height <= height &&
            left >= region.left && top >= region.top &&
            left.toLong() + this.width <= region.left.toLong() + region.width &&
            top.toLong() + this.height <= region.top.toLong() + region.height &&
            alpha.isFinite() && alpha > .01f && alpha <= 1f
    }
}

/** A parent layout can reset the extra View to (0,0) while native pane animations are running. */
internal class PlayerVolumePosition {
    private var last: PlayerVolumePlacement? = null
    fun place(placement: PlayerVolumePlacement, apply: (PlayerVolumePlacement) -> Unit): Boolean {
        if (!placement.fits(placement.rootWidth, placement.rootHeight)) return false
        last = placement
        apply(placement)
        return true
    }
    fun hold(width: Int, height: Int, apply: (PlayerVolumePlacement) -> Unit): Boolean {
        val placement = last?.takeIf { it.fits(width, height) } ?: return false
        apply(placement)
        return true
    }
    fun clear() { last = null }
}
