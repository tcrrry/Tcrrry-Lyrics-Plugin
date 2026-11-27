package dev.kifranei.ampp.media

/** Native transport buttons have 0.5 vertical bias; bottom margin moves their laid-out bounds. */
internal class PlayerVolumeSpacing(initialBottomMargin: Int) {
    private var base = initialBottomMargin
    private var extra = 0

    private fun observe(current: Int) {
        if (current != base + extra) { base = current; extra = 0 }
    }

    fun offset(current: Int): Float { observe(current); return -extra / 2f }
    fun update(current: Int, shift: Int): Int {
        observe(current)
        extra = -shift.coerceAtMost(0) * 2
        return base + extra
    }
    fun restore(current: Int): Int {
        observe(current)
        extra = 0
        return base
    }
}

internal object PlayerVolumeMotion {
    enum class Mode { HIDE, HOLD, LAYOUT }
    fun mode(sheetState: Int, expansion: Float, paneTransition: Boolean): Mode = when {
        sheetState != 3 || !expansion.isFinite() || expansion < .999f -> Mode.HIDE
        paneTransition -> Mode.HOLD
        else -> Mode.LAYOUT
    }
}
