package com.tcrrry.lyrics.engine.hook

/** This host ID must exist before Android restores child fragments, including in portrait. */
internal object FragmentDualPanePolicy {
    const val RIGHT_HOST_ID = 0x00a71606
    const val SHELL_ID = 0x00a71607
    const val RIGHT_TAG_SUFFIX = ":am++:1606:right"

    fun enabled(officialTablet: Boolean, landscape: Boolean, setting: Boolean): Boolean =
        officialTablet && landscape && setting

    /** Portrait keeps a measurable saved lyrics host only until its removal commits. */
    fun rightPaneWidth(playerWidth: Int, dualPane: Boolean, sideGap: Int): Int =
        ((if (dualPane) playerWidth - playerWidth / 2 else playerWidth) - 2 * sideGap).coerceAtLeast(1)

    fun initialLeftState(current: String?): String = when (current) {
        "SONG", "QUEUE" -> current
        else -> "SONG"
    }

    fun suppressSelection(dualPane: Boolean, requested: String?): Boolean = dualPane && requested == "LYRICS"

    fun mayPositionArtwork(resumed: Boolean, sharedElement: Boolean, switching: Boolean, expanded: Boolean = true): Boolean =
        resumed && !sharedElement && !switching && expanded

    /** Mirrors native PlayerMainFragment$i.d(F), applied only to the independent right host. */
    fun lyricsAlpha(slide: Float): Float = when {
        !slide.isFinite() || slide <= 0f -> 0f
        slide >= 1f -> 1f
        else -> (1f + 0.5f * kotlin.math.ln(slide)).coerceIn(0f, 1f)
    }
}

/** Centers native artwork without modifying its size or replaying stale native translations. */
internal class FragmentDualPaneArtworkLease(private val read: () -> Float, write: (Float) -> Unit) : AutoCloseable {
    private val translation = com.tcrrry.lyrics.engine.host.OwnedHostProperty(read, write)

    fun place(intervalTop: Int, barrierTop: Int, coverTop: Int, nativeSize: Int) {
        val available = barrierTop - intervalTop
        if (available <= 0 || nativeSize <= 0) return
        val gap = ((available - nativeSize) / 2f).coerceAtLeast(0f)
        val delta = intervalTop + gap - coverTop
        if (kotlin.math.abs(delta) > 0.5f) translation.set(read() + delta)
    }

    override fun close() = translation.close()
}

/** Small native boundary makes restore, saved-state and commit races testable on the JVM. */
internal interface FragmentDualPaneNative {
    fun stateSaved(): Boolean
    fun find(tag: String): Any?
    fun isLyrics(fragment: Any): Boolean
    fun hostId(fragment: Any): Int
    fun createLyrics(): Any
    fun commit(remove: Any?, add: Any?, hostId: Int, tag: String, completed: () -> Unit)
    /** False while a restored fragment has no view; resume must retry initialization. */
    fun initialize(fragment: Any): Boolean
}

internal class FragmentDualPaneReconciler {
    enum class Result { PRESENT, COMMITTED, DEFERRED, ABSENT }
    private var pending = false
    private var destroyed = false
    private var initialized: Any? = null

    fun reconcile(native: FragmentDualPaneNative, tag: String, enabled: Boolean): Result {
        if (destroyed || pending || native.stateSaved()) return Result.DEFERRED
        val existing = native.find(tag)
        if (enabled && existing != null && native.isLyrics(existing) &&
            native.hostId(existing) == FragmentDualPanePolicy.RIGHT_HOST_ID
        ) {
            initializeOnce(native, existing)
            return Result.PRESENT
        }
        if (!enabled && existing == null) return Result.ABSENT
        val replacement = if (enabled) native.createLyrics() else null
        pending = true
        try {
            native.commit(existing, replacement, FragmentDualPanePolicy.RIGHT_HOST_ID, tag) {
                pending = false
                if (!destroyed && replacement != null) initializeOnce(native, replacement)
            }
        } catch (error: Throwable) {
            pending = false
            throw error
        }
        return Result.COMMITTED
    }

    private fun initializeOnce(native: FragmentDualPaneNative, fragment: Any) {
        if (initialized === fragment) return
        if (native.initialize(fragment)) initialized = fragment
    }

    fun destroy() {
        destroyed = true
        initialized = null
    }
}
