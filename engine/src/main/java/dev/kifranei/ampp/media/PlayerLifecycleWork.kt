package dev.kifranei.ampp.media

import java.util.IdentityHashMap
import java.util.WeakHashMap

/** Coalesce lifecycle work and prevent an accessor called inside that work from rescheduling itself. */
internal class PlayerLifecycleWork<T : Any>(private val post: (Runnable) -> Unit, private val cancel: (Runnable) -> Unit,
    private val active: () -> Boolean, private val work: (T) -> Unit) {
    private val generations = WeakHashMap<T, Long?>()
    private val pending = IdentityHashMap<T, Runnable>()
    private val running = IdentityHashMap<T, Boolean>()
    private var nextGeneration = 0L
    fun created(owner: T) { destroyed(owner); generations[owner] = ++nextGeneration; request(owner) }
    fun request(owner: T) {
        if (!active() || pending.containsKey(owner) || running.containsKey(owner)) return
        if (!generations.containsKey(owner)) generations[owner] = ++nextGeneration
        val generation = generations[owner] ?: return
        lateinit var task: Runnable
        task = Runnable {
            if (pending[owner] !== task) return@Runnable
            pending.remove(owner)
            if (active() && generations[owner] == generation) {
                running[owner] = true
                try { work(owner) } finally { running.remove(owner) }
            }
        }
        pending[owner] = task; post(task)
    }
    fun destroyed(owner: T) { pending.remove(owner)?.let(cancel); generations[owner] = null }
    fun close() { pending.values.toList().forEach(cancel); pending.clear(); running.clear(); generations.clear() }
}
