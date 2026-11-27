package dev.kifranei.ampp.media

/** Keep hierarchy mutations out of Android's recursive window-detach traversal. */
internal class MenuSurfaceCleanup(
    private val defer: (() -> Unit) -> Unit,
    private val stop: () -> Unit,
    private val restore: () -> Unit,
) {
    var closed = false
        private set

    fun close(insideWindowDetach: Boolean = false) {
        if (closed) return
        closed = true
        stop()
        if (insideWindowDetach) defer(restore) else restore()
    }
}
