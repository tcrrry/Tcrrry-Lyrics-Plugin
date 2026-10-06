package com.tcrrry.lyrics.engine.hook

/** Read the complete native row, including words that have not become active yet. */
internal fun nativeLastWordEnd(vector: Any): Long? = runCatching {
    val type = vector.javaClass
    val count = (type.getMethod("size").invoke(vector) as Number).toLong()
    if (count !in 2L..4096L) return@runCatching null
    val get = type.getMethod("get", Long::class.javaPrimitiveType)
    val starts = mutableSetOf<Long>()
    var last: Long? = null
    for (index in 0 until count) {
        val pointer = get.invoke(vector, index) ?: continue
        val word = pointer.javaClass.getMethod("get").invoke(pointer) ?: continue
        val begin = (word.javaClass.getMethod("getBegin").invoke(word) as Number).toLong()
        val end = (word.javaClass.getMethod("getEnd").invoke(word) as Number).toLong()
        if (begin < 0 || end <= begin) continue
        starts.add(begin)
        last = maxOf(last ?: end, end)
    }
    last.takeIf { starts.size >= 2 }
}.getOrNull()
