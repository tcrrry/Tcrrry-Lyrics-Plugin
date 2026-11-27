package dev.kifranei.ampp.media

import java.util.IdentityHashMap

/** Only sizes produced by a completed native layout can describe the control stack. */
internal object PlayerVolumeMeasurement {
    fun height(layoutHeight: Int, measuredHeight: Int, laidOutHeight: Int, ready: Boolean): Int? {
        if (!ready || measuredHeight <= 0 || measuredHeight != laidOutHeight) return null
        if (layoutHeight > 0 && measuredHeight != layoutHeight) return null
        return measuredHeight
    }
}

/** music_player owns the constraint; lyrics/queue fill their immediate controls wrapper. */
internal object PlayerVolumeSpaceTarget {
    fun <T> find(controls: T, isConstraint: (T) -> Boolean, parent: (T) -> T?,
        isWrapper: (T) -> Boolean, fillsParent: (T) -> Boolean): T? {
        if (isConstraint(controls)) return controls
        if (!fillsParent(controls)) return null
        return parent(controls)?.takeIf { isWrapper(it) && isConstraint(it) }
    }
}

/** Native panes and shared elements can have identical resource IDs but distinct Views. */
internal object PlayerVolumeLayoutState {
    fun <T : Any> inactive(tracked: Collection<T>, active: Collection<T>): List<T> {
        val retained = IdentityHashMap<T, Boolean>()
        active.forEach { retained[it] = true }
        return tracked.filterNot(retained::containsKey)
    }
}

/** A tablet column can move sideways without changing the player's width or height. */
internal class PlayerVolumeHoldColumn<T : Any>(private val position: PlayerVolumePosition) {
    var source: T? = null
        private set
    private var windowX = 0

    fun remember(source: T, windowX: Int) { this.source = source; this.windowX = windowX }

    fun hold(currentWindowX: Int?, width: Int, height: Int, apply: (PlayerVolumePlacement) -> Unit): Boolean {
        if (source == null || currentWindowX != windowX) { clear(); return false }
        return position.hold(width, height, apply)
    }

    fun clear() { source = null; position.clear() }
}
