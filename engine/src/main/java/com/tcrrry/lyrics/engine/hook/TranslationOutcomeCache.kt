package com.tcrrry.lyrics.engine.hook

import java.util.concurrent.ConcurrentHashMap

/** Keep successful work; briefly back off failures, then allow a new attempt. */
internal class TranslationOutcomeCache(private val now: () -> Long = System::currentTimeMillis) {
    private data class Entry(val outcome: DesktopLyricsSupplement.Outcome, val expires: Long)
    private val values = ConcurrentHashMap<String, Entry>()
    operator fun get(key: String): DesktopLyricsSupplement.Outcome? {
        val entry = values[key] ?: return null
        if (entry.expires <= now()) { values.remove(key, entry); return null }
        return entry.outcome
    }
    operator fun set(key: String, outcome: DesktopLyricsSupplement.Outcome) {
        if (values.size >= 64) values.keys.firstOrNull()?.let(values::remove)
        values[key] = Entry(outcome, if (outcome.retryable) now() + 5000 else Long.MAX_VALUE)
    }
    fun invalidate(id: Long) {
        val prefix = "$id|"
        values.keys.filter { it.startsWith(prefix) }.forEach(values::remove)
    }
    companion object {
        fun marker(complete: Boolean) = "<!--supplement-translation-complete=$complete-->"
        fun canReuse(ttml: String, translationEnabled: Boolean): Boolean =
            !translationEnabled || !ttml.take(1024).contains(marker(false))
    }
}
