package com.tcrrry.lyrics.engine.lyrics

/** Presentation facts travel with the exact TTML, including its on-disk cache. */
internal data class DesktopLyricsPresentation(
    val source: String,
    val wordTimed: Boolean,
    val platformTranslation: Boolean,
    val apiTranslation: Boolean,
    val offlineTranslation: Boolean,
    val pronunciation: Boolean,
    val nativePronunciation: Boolean = pronunciation,
    val offlinePronunciation: Boolean = false,
    val primaryPronunciation: Boolean = false,
    val offlinePronunciationLanguages: Set<String> = emptySet(),
) {
    // An XML comment is ignored by Apple's parser; it does not alter TTML timing.
    fun marker(): String = "<!--tcrrry-lyrics-v1 source=$source word=$wordTimed " +
        "platform=$platformTranslation api=$apiTranslation offline=$offlineTranslation roma=$pronunciation nativeRoma=$nativePronunciation offlineRoma=$offlinePronunciation primaryRoma=$primaryPronunciation romaLangs=${offlinePronunciationLanguages.sorted().joinToString(",")}-->"

    fun detail(): String = buildList {
        add(if (wordTimed) "逐字" else "逐行")
        if (platformTranslation) add("自带译文")
        if (apiTranslation) add("api译")
        if (offlineTranslation) add("机翻译文")
        if (nativePronunciation) add("自带发音")
        if (offlinePronunciation) add("机翻发音")
        if (primaryPronunciation) add(if (wordTimed) "发音逐段高亮" else "发音逐行显示")
    }.joinToString(" · ")

    companion object {
        private val marker = Regex("""<!--tcrrry-lyrics-v1 source=(QQ音乐|网易云音乐|LRCLIB|未知) word=(true|false) platform=(true|false) api=(true|false) offline=(true|false) roma=(true|false)(?: nativeRoma=(true|false) offlineRoma=(true|false)(?: primaryRoma=(true|false))?)?(?: romaLangs=([a-z,]*))?-->""")

        fun fromTtml(ttml: String): DesktopLyricsPresentation? {
            val values = marker.find(ttml.take(1024))?.groupValues ?: return null
            return DesktopLyricsPresentation(values[1], values[2].toBoolean(), values[3].toBoolean(),
                values[4].toBoolean(), values[5].toBoolean(), values[6].toBoolean(),
                values[7].takeIf(String::isNotEmpty)?.toBoolean() ?: values[6].toBoolean(),
                values[8].toBoolean(), values[9].toBoolean(), values[10].split(',').filter { it in setOf("ja", "ko", "yue") }.toSet())
        }
    }
}
