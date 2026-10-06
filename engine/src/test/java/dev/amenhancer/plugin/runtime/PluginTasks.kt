package dev.amenhancer.plugin.runtime

import java.util.concurrent.Executors

/** Storage stays serialized; plugin code cannot occupy the management queue. */
internal class PluginTasks : AutoCloseable {
    private val management = Executors.newSingleThreadExecutor { Thread(it, "ampp-plugin-management").apply { isDaemon = true } }
    private val loading = Executors.newSingleThreadExecutor { Thread(it, "ampp-plugin-loading").apply { isDaemon = true } }
    private val plugins = Executors.newCachedThreadPool { Thread(it, "ampp-plugin-onload").apply { isDaemon = true } }

    fun execute(task: () -> Unit) { management.execute(task) }

    fun <T> prepareAndLoad(prepare: () -> T, load: (T) -> Unit, failed: (Throwable) -> Unit) {
        execute {
            try {
                val snapshot = prepare()
                loading.execute {
                    try { load(snapshot) } catch (failure: Throwable) { failed(failure) }
                }
            } catch (failure: Throwable) { failed(failure) }
        }
    }

    fun loadPlugin(load: () -> Unit, complete: () -> Unit, failed: (Throwable) -> Unit) {
        plugins.execute {
            try { load(); loading.execute(complete) } catch (failure: Throwable) { failed(failure) }
        }
    }

    override fun close() { management.shutdownNow(); plugins.shutdownNow(); loading.shutdownNow() }
}
