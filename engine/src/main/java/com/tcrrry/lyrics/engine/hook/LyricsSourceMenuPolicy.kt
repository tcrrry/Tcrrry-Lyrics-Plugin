package com.tcrrry.lyrics.engine.hook

/** Only the displayed recording's applied source describes the active lyrics. */
internal object LyricsSourceMenuPolicy {
    const val NATIVE = "Apple Music 原生"
    const val AUTHOR = "AM++ 作者整理库"
    val thirdPartySources = listOf("QQ音乐", "网易云音乐", "LRCLIB")
    val sources = thirdPartySources + listOf(NATIVE, AUTHOR)
    fun provider(applied: String?): String? = when (applied) {
        "APPLE_NATIVE", NATIVE -> NATIVE
        com.tcrrry.lyrics.engine.model.CustomLyricsSources.AM_LYRICS -> AUTHOR
        else -> applied?.takeIf { it.startsWith("desktop-lyrics:") }
            ?.substringAfter(':')?.takeIf { it in thirdPartySources }
    }
    fun next(applied: String?, selected: String?, order: List<String> = sources): String {
        val list = if (order.isEmpty()) sources else order
        val current = selected?.takeIf { it in list } ?: provider(applied)?.takeIf { it in list }
        return list[(list.indexOf(current) + 1) % list.size]
    }
    fun cycleOrder(applied: String?, selected: String?, order: List<String> = sources): List<String> {
        val list = if (order.isEmpty()) sources else order
        val first = list.indexOf(next(applied, selected, list))
        return list.drop(first) + list.take(first)
    }
    fun caption(applied: String?): String = provider(applied) ?: when (applied) {
        "manual" -> "手动歌词"
        "automatic-cache" -> "缓存来源未记录"
        com.tcrrry.lyrics.engine.model.CustomLyricsSources.AMLL -> "AMLL TTML 库"
        com.tcrrry.lyrics.engine.model.CustomLyricsSources.LUNABEAT -> "Lunabeat 歌词库"
        else -> "来源尚未确认"
    }
    fun canAct(menuSongId: Long, currentSongId: Long?): Boolean = menuSongId > 0 && menuSongId == currentSongId
}
