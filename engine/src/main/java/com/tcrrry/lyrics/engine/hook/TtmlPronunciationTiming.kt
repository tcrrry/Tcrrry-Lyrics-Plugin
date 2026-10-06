package com.tcrrry.lyrics.engine.hook

/** Apple's Word parser ignores pronunciation text before its first span. */
internal object TtmlPronunciationTiming {
    private data class Timing(val begin: String, val end: String) {
        val attributes get() = "begin=$begin end=$end"
    }
    private val body = Regex("""(?s)<body\b[^>]*>.*?</body\s*>""")
    private val line = Regex("""(?s)<p\b([^>]*)>.*?</p\s*>""")
    private val track = Regex("""(?s)<transliteration\b[^>]*>.*?</transliteration\s*>""")
    private val text = Regex("""(?s)(<text\b([^>]*)>)([^<]*)(</text\s*>)""")
    private fun attribute(attributes: String, name: String): String? =
        Regex("""(?:^|\s)${Regex.escape(name)}\s*=\s*("[^"]*"|'[^']*')""")
            .find(attributes)?.groupValues?.get(1)

    fun prepare(ttml: String): String {
        if (!TtmlTimingPolicy.isWord(ttml) || !ttml.contains("<transliteration")) return ttml
        val timings = body.find(ttml)?.let { block ->
            line.findAll(block.value).mapNotNull { match ->
                val attributes = match.groupValues[1]
                val key = attribute(attributes, "itunes:key")?.drop(1)?.dropLast(1) ?: return@mapNotNull null
                val begin = attribute(attributes, "begin") ?: return@mapNotNull null
                val end = attribute(attributes, "end") ?: return@mapNotNull null
                key to Timing(begin, end)
            }.toMap()
        } ?: return ttml
        // Textual replacement keeps word separators, agents, translations and
        // already timed pronunciation markup byte-for-byte. Apply at parse time
        // so old cached files receive the same repair without clearing mappings.
        return track.replace(ttml) { block ->
            text.replace(block.value) { entry ->
                val key = attribute(entry.groupValues[2], "for")?.drop(1)?.dropLast(1)
                val timing = timings[key]
                if (timing == null) entry.value else {
                    val attributes = entry.groupValues[2]
                    val extra = buildString {
                        if (attribute(attributes, "begin") == null) append(" begin=${timing.begin}")
                        if (attribute(attributes, "end") == null) append(" end=${timing.end}")
                    }
                    "<text$attributes$extra><span ${timing.attributes}>${entry.groupValues[3]}</span>${entry.groupValues[4]}"
                }
            }
        }
    }
}
