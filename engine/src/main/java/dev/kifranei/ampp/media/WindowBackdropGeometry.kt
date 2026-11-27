package dev.kifranei.ampp.media

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot

/** Translate consumer-local pixels into the source window's screen-aligned capture. */
internal object WindowBackdropGeometry {
    fun origin(sourceX: Int, sourceY: Int, consumerX: Int, consumerY: Int,
        localX: Float, localY: Float): Pair<Float, Float> =
        Pair(consumerX - sourceX + localX, consumerY - sourceY + localY)
}

/** Position invalidates the observed draw immediately, independently of PixelCopy. */
internal class WindowBackdropPosition {
    var origin by mutableStateOf(Pair(0f, 0f))
        private set

    fun update(next: Pair<Float, Float>) {
        if (origin == next) return
        // Apply during native pre-draw so Compose invalidates its cached layer
        // before this traversal draws, rather than waiting for a sampled image.
        Snapshot.withMutableSnapshot { origin = next }
    }
}
