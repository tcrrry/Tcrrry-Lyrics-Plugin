package com.tcrrry.lyrics.engine.hook

/** Shared by native full binding and the subtitle hook, before either clears the old views. */
internal object NativeLyricsTailBindGuard {
    @Volatile var retain: ((Any, Any, Int) -> Boolean)? = null
    private val bypass = ThreadLocal<Boolean>()

    fun beforeBind(adapter: Any, holder: Any, position: Int): Boolean =
        bypass.get() != true && runCatching { retain?.invoke(adapter, holder, position) == true }
            .getOrDefault(false)

    fun <T> updatingAuxiliary(explicitChange: Boolean = true, block: () -> T): T {
        if (!explicitChange) return block()
        val previous = bypass.get()
        bypass.set(true)
        return try { block() } finally {
            if (previous == null) bypass.remove() else bypass.set(previous)
        }
    }
}
