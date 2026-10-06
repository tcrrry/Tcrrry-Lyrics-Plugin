package com.tcrrry.lyrics.engine.hook

/** Only this adapter can turn an opaque lyric payload into an Apple Music pointer. */
internal class OpaqueTtmlParser(private val native: TtmlNativeParser) {
    private class Handle(val pointer: Any) : NativeLyricsHandle
    private val handles = mutableListOf<java.lang.ref.WeakReference<Handle>>()
    fun parse(ttml: String): NativeLyricsHandle? = wrap(native.parse(ttml))
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
