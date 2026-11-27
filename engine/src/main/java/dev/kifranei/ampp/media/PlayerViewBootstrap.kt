package dev.kifranei.ampp.media

import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Bind pre-existing fragment views once without scheduling more work from getView itself. */
internal class PlayerViewBootstrap<T : Any, V : Any>(private val bind: (T, V) -> Unit) {
    private val roots = WeakHashMap<T, WeakReference<V>>()
    private val destroyed = WeakHashMap<T, Boolean>()
    fun observed(owner: T, root: V) {
        if (destroyed[owner] == true || roots[owner]?.get() === root) return
        // Record before binding: native view initialization may call getView recursively.
        roots[owner] = WeakReference(root)
        bind(owner, root)
    }
    fun created(owner: T, root: V) { destroyed.remove(owner); observed(owner, root) }
    fun destroyed(owner: T) { destroyed[owner] = true; roots.remove(owner) }
    fun close() { roots.clear(); destroyed.clear() }
}
