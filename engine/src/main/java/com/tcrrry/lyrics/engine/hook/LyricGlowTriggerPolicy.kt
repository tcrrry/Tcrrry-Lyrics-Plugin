package com.tcrrry.lyrics.engine.hook

import com.tcrrry.lyrics.engine.model.LyricGlowPosition
import com.tcrrry.lyrics.engine.model.ModuleSettings

/** Changes the trigger threshold only; lyric timing and word sweep duration stay native. */
internal data class LyricGlowTriggerPolicy(val sensitivity: Int = 100,
    val position: LyricGlowPosition = LyricGlowPosition.ALL) {
    fun threshold(baseMs: Int, terminal: Boolean): Int {
        val scale = if (position == LyricGlowPosition.TAIL_PREFERRED && !terminal) 150 else 100
        return (baseMs.toLong() * scale / sensitivity.coerceIn(ModuleSettings.MIN_LYRIC_GLOW_SENSITIVITY, ModuleSettings.MAX_LYRIC_GLOW_SENSITIVITY)).toInt()
    }
    fun allows(durationMs: Int, terminal: Boolean, baseMs: Int = 1000): Boolean =
        (position != LyricGlowPosition.TAIL_ONLY || terminal) && durationMs >= threshold(baseMs, terminal)
}

/** Exact native row/vector IDs; punctuation and zero-duration tokens cannot steal the tail. */
internal class NativeGlowWordPosition {
    private val rows = java.util.WeakHashMap<Any, MutableMap<Int, Set<Int>>>()
    fun terminalIds(adapter: Any, row: Int): Set<Int> = runCatching {
        val index = adapter.javaClass.getDeclaredField("p").apply { isAccessible = true }.get(adapter)
            ?: return emptySet()
        rows[index]?.get(row)?.let { return it }
        val ptr = index.javaClass.getMethod("a", Int::class.javaPrimitiveType).invoke(index, row) ?: return emptySet()
        val line = ptr.javaClass.getMethod("get").invoke(ptr) ?: return emptySet()
        val ids = mutableSetOf<Int>()
        for (name in listOf("getWords", "getPronunciationWords")) {
            val vector = line.javaClass.getMethod(name).invoke(line) ?: continue
            val count = (vector.javaClass.getMethod("size").invoke(vector) as Number).toInt()
            if (count !in 1..4096) continue
            val get = vector.javaClass.getMethod("get", Long::class.javaPrimitiveType)
            var latest = -1L
            var last: Int? = null
            for (i in 0 until count) {
                val wordPtr = get.invoke(vector, i.toLong()) ?: continue
                val word = wordPtr.javaClass.getMethod("get").invoke(wordPtr) ?: continue
                val begin = (word.javaClass.getMethod("getBegin").invoke(word) as Number).toLong()
                val end = (word.javaClass.getMethod("getEnd").invoke(word) as Number).toLong()
                val text = word.javaClass.getMethod("getHtmlLineText").invoke(word) as? String ?: continue
                if (begin >= 0 && end > begin && end >= latest && isGlowTailText(text)) {
                    latest = end
                    last = (word.javaClass.getMethod("getWordId").invoke(word) as Number).toInt()
                }
            }
            last?.let(ids::add)
        }
        rows.getOrPut(index) { mutableMapOf() }[row] = ids
        ids
    }.getOrDefault(emptySet())
}
internal fun isGlowTailText(html: String): Boolean = html.replace(Regex("<[^>]*>|&[^;]+;"), "")
    .codePoints().anyMatch(Character::isLetterOrDigit)

internal fun promotedGlowDuration(value: Long, actualMs: Int): Long =
    if (actualMs in 1..999) (value * actualMs / 1000).coerceAtLeast(0) else value
