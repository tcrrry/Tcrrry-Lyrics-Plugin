package dev.kifranei.ampp.media

internal enum class SharedCardKind(val shareId: String, val saveId: String, val filenameTag: String) {
    LYRICS("amppShareLyricsImage", "amppSaveLyricsImage", "lyrics"),
    SONG("amppShareSongImage", "amppSaveSongImage", "song");

    private fun chinese(language: String) = language.equals("zh", ignoreCase = true)
    fun label(language: String) = if (chinese(language)) {
        if (this == LYRICS) "歌词卡片" else "歌曲卡片"
    } else if (this == LYRICS) "lyrics card" else "song card"
    fun saveLabel(language: String) = if (chinese(language)) "保存${label(language)}" else "Save ${label(language)}"
    fun shareTitle(language: String) = if (chinese(language)) "分享${label(language)}" else "Share ${label(language)}"
    fun savedMessage(language: String) = if (chinese(language)) "${label(language)}已保存到相册" else "Saved to Photos"
    fun failureMessage(language: String, generation: Boolean = false) = if (chinese(language)) {
        "${label(language)}${if (generation) "暂时无法生成" else "导出失败"}，请重试"
    } else if (generation) "Could not generate the card. Please try again." else "Could not export the card. Please try again."
}

/** Retain the selected action on the progress fragment while AM renders its story template. */
internal data class SharedCardAction(val kind: SharedCardKind, val save: Boolean) {
    val id get() = if (save) kind.saveId else kind.shareId
    companion object {
        fun fromId(id: String?): SharedCardAction? = SharedCardKind.entries.firstNotNullOfOrNull { kind ->
            when (id) {
                kind.shareId -> SharedCardAction(kind, false)
                kind.saveId -> SharedCardAction(kind, true)
                else -> null
            }
        }
    }
}
