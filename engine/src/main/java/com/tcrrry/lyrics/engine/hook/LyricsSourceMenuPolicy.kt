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
    fun next(applied: String?, selected: String?): String {
        val current = selected?.takeIf { it in sources } ?: provider(applied)
        return sources[(sources.indexOf(current) + 1) % sources.size]
    }
    fun cycleOrder(applied: String?, selected: String?): List<String> {
        val first = sources.indexOf(next(applied, selected))
        return sources.drop(first) + sources.take(first)
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
