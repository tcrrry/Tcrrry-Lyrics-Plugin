package com.tcrrry.lyrics.engine.host

import java.util.IdentityHashMap

/** Fragment identity survives a temporary detach; each attached view gets a fresh binding. */
class FragmentViewSessions<O : Any, R : Any, S : AutoCloseable>(private val revoked: (S) -> Unit) : AutoCloseable {
    private val roots = IdentityHashMap<O, R>()
    private val bindings = IdentityHashMap<O, S>()
    val values: Collection<S> get() = bindings.values
    val owners: Set<O> get() = roots.keys
    fun root(owner: O): R? = roots[owner]
    fun hasBinding(owner: O): Boolean = bindings.containsKey(owner)
    fun current(owner: O, root: R): Boolean = roots[owner] === root
    fun prepare(owner: O, root: R): R? {
        val previous = roots[owner]
        if (previous === root) return null
        destroy(owner)
        roots[owner] = root
        return previous
    }
    /** Null means a duplicate or a callback for an obsolete view. */
    fun bind(owner: O, root: R, create: () -> S): S? {
        if (!current(owner, root) || bindings.containsKey(owner)) return null
        return create().also { bindings[owner] = it }
    }
    fun detach(owner: O, root: R) {
        if (!current(owner, root)) return
        bindings.remove(owner)?.let { old -> try { revoked(old) } finally { old.close() } }
    }
    fun destroy(owner: O) {
        try { roots[owner]?.let { detach(owner, it) } } finally { roots.remove(owner) }
    }
    override fun close() { owners.toList().forEach(::destroy) }
}
