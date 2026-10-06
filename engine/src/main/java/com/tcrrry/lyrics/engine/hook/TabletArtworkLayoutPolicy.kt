package com.tcrrry.lyrics.engine.hook

data class TabletArtworkLayout(
    val sizePx: Float,
    val edgeGapPx: Float,
)

/**
 * Center the unchanged native cover inside the vertical interval between the
 * player top and Apple's metadata barrier, leaving equal edge gaps whenever
 * the native cover fits in that interval.
 */
object TabletArtworkLayoutPolicy {
    /** All positions are local to player_root; insets are resolved against its expanded viewport. */
    fun nativeTopMarginInPlayer(
        hostTopPx: Float,
        metadataTopPx: Float,
        parentTopPx: Float,
        nativeSizePx: Float,
        topInsetPx: Float,
    ): Int? {
        if (!topInsetPx.isFinite() || topInsetPx < 0f) return null
        return nativeTopMargin(maxOf(hostTopPx, topInsetPx), metadataTopPx, parentTopPx, nativeSizePx)
    }

    /** Express centering in layout space, visible to the native descendant-rectangle animator. */
    fun nativeTopMargin(
        playerTopPx: Float,
        metadataTopPx: Float,
        parentTopPx: Float,
        nativeSizePx: Float,
    ): Int? {
        if (!playerTopPx.isFinite() || !metadataTopPx.isFinite() || !parentTopPx.isFinite() || !nativeSizePx.isFinite()) return null
        val layout = resolve(metadataTopPx - playerTopPx, nativeSizePx) ?: return null
        val margin = playerTopPx + layout.edgeGapPx - parentTopPx
        return if (margin.isFinite()) kotlin.math.round(margin).toInt() else null
    }

    fun resolve(
        availableHeightPx: Float,
        nativeSizePx: Float,
    ): TabletArtworkLayout? {
        if (availableHeightPx <= 0f || nativeSizePx <= 0f) return null
        val edgeGapPx = ((availableHeightPx - nativeSizePx) / 2f).coerceAtLeast(0f)
        return TabletArtworkLayout(
            sizePx = nativeSizePx,
            edgeGapPx = edgeGapPx,
        )
    }
}
