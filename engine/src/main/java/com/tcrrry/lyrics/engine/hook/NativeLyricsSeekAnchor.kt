package com.tcrrry.lyrics.engine.hook

import java.lang.ref.WeakReference

/** Read-only native line times: gaps point to the next vocal line, never to a stale highlight. */
internal class NativeLyricsSeekAnchor {
    data class Line(val id: Int, val begin: Long, val end: Long)
    private var document = WeakReference<Any>(null)
    private var lines = emptyList<Line>()

    fun at(pointer: Any, position: Long): Int? {
        if (document.get() !== pointer) {
            val song = pointer.javaClass.getMethod("get").invoke(pointer) ?: return null
            val result = mutableListOf<Line>()
            fun entries(vector: Any, maximum: Int, consume: (Any) -> Unit) {
                val count = (vector.javaClass.getMethod("size").invoke(vector) as Number).toInt()
                require(count in 0..maximum)
                val get = vector.javaClass.getMethod("get", Long::class.javaPrimitiveType)
                for (i in 0 until count) {
                    val ptr = get.invoke(vector, i.toLong()) ?: continue
                    ptr.javaClass.getMethod("get").invoke(ptr)?.let(consume)
                }
            }
            val sections = song.javaClass.getMethod("getSections").invoke(song) ?: return null
            entries(sections, 512) { section ->
                val rows = section.javaClass.getMethod("getLines").invoke(section) ?: return@entries
                entries(rows, 4096) { line ->
                    val type = line.javaClass
                    val id = (type.getMethod("getLineId").invoke(line) as Number).toInt()
                    val begin = (type.getMethod("getBegin").invoke(line) as Number).toLong()
                    val end = (type.getMethod("getEnd").invoke(line) as Number).toLong()
                    val text = type.getMethod("getHtmlLineText").invoke(line) as? String
                    if (id >= 0 && begin >= 0 && end > begin && !text.isNullOrBlank()) result += Line(id, begin, end)
                }
            }
            lines = result.distinctBy { it.id }.sortedBy { it.begin }
            document = WeakReference(pointer)
        }
        return select(lines, position)
    }

    companion object {
        internal fun select(lines: List<Line>, position: Long): Int? =
            lines.filter { position >= it.begin && position < it.end }.maxByOrNull { it.begin }?.id
                ?: lines.firstOrNull { it.begin > position }?.id
                ?: lines.lastOrNull()?.id
    }
}
