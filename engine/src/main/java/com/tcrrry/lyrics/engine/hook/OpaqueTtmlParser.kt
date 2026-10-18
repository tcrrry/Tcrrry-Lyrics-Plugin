// Adapted from Zennmn/AM-plus-plus d781fc260198f26a61f3d8631a19527c6bf17594 (GPL-3.0).
package com.tcrrry.lyrics.engine.hook

/** Only this adapter can turn an opaque lyric payload into an Apple Music pointer. */
internal class OpaqueTtmlParser(private val native: TtmlNativeParser) {
    private class Handle(val pointer: Any, var document: String? = null) : NativeLyricsHandle
    private val handles = mutableListOf<java.lang.ref.WeakReference<Handle>>()
    private val moduleParse = ThreadLocal<Boolean>()
    // Preparation and independent refresh workers share one native parser.
    @Synchronized fun parse(ttml: String): NativeLyricsHandle? {
        moduleParse.set(true)
        return try {
            (wrap(native.parse(ttml)) as? Handle)?.also { it.document = ttml }
        } finally { moduleParse.remove() }
    }

    /** The parser hook must not treat a module-produced replacement as native input. */
    fun isModuleParse(): Boolean = moduleParse.get() == true

    /** Content identity for submission deduplication; never shares mutable pointers across IDs. */
    @Synchronized fun documentOf(payload: Any): String? =
        (payload as? Handle)?.document ?: handles.firstNotNullOfOrNull {
            it.get()?.takeIf { handle -> handle.pointer === payload }?.document
        }
    @Synchronized fun wrap(pointer: Any?): NativeLyricsHandle? {
        pointer ?: return null
        handles.removeAll { it.get() == null }
        handles.firstNotNullOfOrNull { it.get()?.takeIf { handle -> handle.pointer === pointer } }?.let { return it }
        return Handle(pointer).also { handles += java.lang.ref.WeakReference(it) }
    }
    fun unwrap(payload: Any?): Any? = (payload as? Handle)?.pointer ?: payload
    fun isAlive(payload: Any?): Boolean = native.isAlive(unwrap(payload))
    fun isValid(payload: Any?): Boolean = native.isValid(unwrap(payload))
    fun adamIdOf(payload: Any): Long? = unwrap(payload)?.let(native::adamIdOf)
    fun bindAdamId(payload: Any, id: Long): Boolean = unwrap(payload)?.let { native.bindAdamId(it,id) } == true
}
