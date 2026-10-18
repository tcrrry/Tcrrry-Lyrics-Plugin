package com.tcrrry.lyrics.engine.hook

/** Facts about the auxiliary rows on this exact document, independent of primary lyric source. */
internal data class TtmlAuxiliaryOrigins(val translation: Set<String> = emptySet(), val pronunciation: Set<String> = emptySet()) {
    fun marker() = "<!--tcrrry-aux-v1 translation=${translation.sorted().joinToString(",")} pronunciation=${pronunciation.sorted().joinToString(",")}-->"
    fun attach(ttml: String): String {
        val clean = ttml.replace(pattern, "")
        return if (clean.startsWith("<?xml")) clean.replaceFirst("?>", "?>${marker()}") else marker() + clean
    }
    fun detail(): String = (translation.mapNotNull { when (it) {
        "native" -> "原生译文"; "author" -> "歌词库译文"; "source" -> "来源自带译文"; "offline" -> "机翻译文"; "api" -> "API 译文"
        "QQ音乐", "网易云音乐" -> "$it 译文"; else -> null
    } } + pronunciation.mapNotNull { when (it) {
        "native" -> "原生发音"; "author" -> "歌词库发音"; "source" -> "来源自带发音"; "dictionary" -> "离线注音"
        "QQ音乐", "网易云音乐" -> "$it 发音"; else -> null
    } }).joinToString(" · ")
    companion object {
        private val pattern = Regex("<!--tcrrry-aux-v1 translation=([^ ]*) pronunciation=([^ ]*)-->")
        fun read(ttml: String): TtmlAuxiliaryOrigins? = pattern.find(ttml.take(2048))?.let {
            TtmlAuxiliaryOrigins(it.groupValues[1].split(',').filter(String::isNotBlank).toSet(),
                it.groupValues[2].split(',').filter(String::isNotBlank).toSet())
        }
        fun original(source: String, ttml: String): TtmlAuxiliaryOrigins {
            val origin = when(source) {
                "APPLE_NATIVE", LyricsSourceMenuPolicy.NATIVE -> "native"
                com.tcrrry.lyrics.engine.model.CustomLyricsSources.AM_LYRICS -> "author"
                else -> "source"
            }
            return TtmlAuxiliaryOrigins(
                if (TtmlSubtitleTrack.subtitleValues(ttml).isNotEmpty()) setOf(origin) else emptySet(),
                if (TtmlSubtitleTrack.subtitleValues(ttml, true).isNotEmpty()) setOf(origin) else emptySet())
        }
    }
}

/** Keep the source language when exposing auxiliary tracks uses a synthetic ko root. */
internal object TtmlOriginalLanguage {
    private val marker=Regex("<!--tcrrry-source-language-v1 ([A-Za-z0-9-]+)-->")
    private val root=Regex("""(?is)<tt\b[^>]*\bxml:lang\s*=\s*["']([A-Za-z0-9-]+)["']""")
    fun read(ttml:String):String? = marker.find(ttml.take(2048))?.groupValues?.get(1)
        ?: root.find(ttml)?.groupValues?.get(1)
    fun preserve(original:String,rendered:String):String {
        if(marker.containsMatchIn(rendered.take(2048))) return rendered
        val language=read(original) ?: return rendered
        val comment="<!--tcrrry-source-language-v1 $language-->"
        return if(rendered.startsWith("<?xml")) rendered.replaceFirst("?>","?>$comment") else comment+rendered
    }
}
