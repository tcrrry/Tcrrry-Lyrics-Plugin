package dev.kifranei.ampp.media

internal object PlayerChromeGeometry {
    /**
     * Width (dp) from which Apple Music inflates its `layout-w600dp` two-pane player (artwork left, lyrics/queue
     * right). There AM++ already draws a handle above the artwork column, so the plugin's centred pill would be a
     * second handle above the lyrics pane (tablet landscape). Phone portrait is always narrower.
     */
    const val TWO_PANE_MIN_WIDTH_DP = 600

    fun twoPane(screenWidthDp: Int) = screenWidthDp >= TWO_PANE_MIN_WIDTH_DP

    fun handleAlpha(expansion: Float, fullscreenArtwork: Boolean = false, screenWidthDp: Int = 0): Int {
        if (fullscreenArtwork || !expansion.isFinite() || twoPane(screenWidthDp)) return 0
        return (((expansion - .75f) / .25f).coerceIn(0f, 1f) * 150f).toInt()
    }

    fun motionRadius(system: Float, collapsed: Float, nativeCollapse: Float): Float {
        val progress = if (nativeCollapse.isFinite()) nativeCollapse.coerceIn(0f, 1f) else 1f
        return system + (collapsed - system) * progress
    }
}
