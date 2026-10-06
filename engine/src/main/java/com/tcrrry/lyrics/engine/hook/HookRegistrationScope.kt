package com.tcrrry.lyrics.engine.hook

/** Logical callback ownership; close does not assume a framework unhook handle exists. */
class HookRegistrationScope : AutoCloseable {
    private enum class State { PREPARING, ACTIVE, CLOSED }
    private val lock = Any()
    private var state = State.PREPARING
    private val cleanup = mutableListOf<() -> Unit>()

    val isActive: Boolean get() = synchronized(lock) { state == State.ACTIVE }

    fun activate() = synchronized(lock) {
        check(state == State.PREPARING) { "Registration scope cannot be activated again" }
        state = State.ACTIVE
    }

    fun onClose(action: () -> Unit) {
        val closed = synchronized(lock) {
            if (state == State.CLOSED) true else { cleanup += action; false }
        }
        if (closed) action()
    }

    override fun close() {
        val actions = synchronized(lock) {
            if (state == State.CLOSED) return
            state = State.CLOSED
            cleanup.asReversed().toList().also { cleanup.clear() }
        }
        actions.forEach { action -> runCatching(action).onFailure {
            runCatching { ModernXposedRuntime.log("Hook scope cleanup failed", it) }
        } }
    }
}
