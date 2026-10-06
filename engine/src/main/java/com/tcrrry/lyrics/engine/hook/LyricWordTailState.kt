package com.tcrrry.lyrics.engine.hook

/** Only real native word end times can defer the host's departed-row cancellation. */
internal class LyricWordTailState {
    private val epoch = LyricPlaybackEpoch()
    private var document: Any? = null
    private var position: Long? = null
    private val endsBySource = mutableMapOf<String, MutableMap<Int, Long>>()
    @Synchronized fun process(token: Any, current: Long?) {
        if (epoch.update(token, current) != LyricPlaybackEpoch.Change.CONTINUOUS) endsBySource.clear()
        if (document !== token) position = null
        document = token
        if (current != null) position = current
        position?.let { now -> endsBySource.values.forEach { rows -> rows.entries.removeAll { it.value <= now } } }
    }
    @Synchronized fun update(source: String, ends: Map<Int, Long>) {
        val now = position ?: return
        val rows = endsBySource.getOrPut(source) { mutableMapOf() }
        rows.entries.removeAll { it.value <= now }
        ends.forEach { (row, end) ->
            if (row >= 0 && end > now) rows[row] = maxOf(rows[row] ?: end, end)
        }
    }
    @Synchronized fun protectedRows(): Set<Int> {
        val now = position ?: return emptySet()
        return endsBySource.values.flatMap { rows -> rows.filterValues { it > now }.keys }.toSet()
    }
    @Synchronized fun owns(token: Any): Boolean = token === document
    @Synchronized fun protects(token: Any, row: Int): Boolean {
        val now = position ?: return false
        return token === document && endsBySource.values.any { (it[row] ?: Long.MIN_VALUE) > now }
    }
}
