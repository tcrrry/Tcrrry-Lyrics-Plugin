package com.tcrrry.lyrics.engine.host

/** Owns one published view session. A Fragment view recreation always gets a new identity. */
class HostViewSessionController<K : Any, S : AutoCloseable>(private val create: (K, Long) -> S) : AutoCloseable {
    private var identity: K? = null
    private var configuration: Long? = null
    private var session: S? = null
    @Synchronized fun bind(owner: K, configurationRevision: Long): S {
        session?.takeIf { identity === owner && configuration == configurationRevision }?.let { return it }
        close()
        // A failed factory never publishes a partially initialized session.
        return create(owner, configurationRevision).also {
            identity = owner; configuration = configurationRevision; session = it
        }
    }
    @Synchronized fun destroy(owner: K) { if (identity === owner) close() }
    @Synchronized override fun close() {
        val old = session
        identity = null; configuration = null; session = null
        old?.close()
    }
}

/** Restores a property only if the host has not replaced the module's latest write. */
class OwnedHostProperty<T>(private val read: () -> T, private val write: (T) -> Unit) : AutoCloseable {
    private var owned = false
    private var native: T? = null
    private var last: T? = null
    fun set(value: T) {
        val current = read()
        if (!owned || current != last) native = current
        last = value; owned = true; write(value)
    }
    override fun close() {
        if (!owned) return
        if (read() == last) {
            @Suppress("UNCHECKED_CAST") write(native as T)
        }
        owned = false
    }
}
