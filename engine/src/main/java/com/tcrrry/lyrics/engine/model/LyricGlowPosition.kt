package com.tcrrry.lyrics.engine.model

enum class LyricGlowPosition(val storageValue: String, val displayName: String) {
    ALL("all", "全部长音"),
    TAIL_ONLY("tail", "仅句尾"),
    TAIL_PREFERRED("tail-preferred", "句尾优先");

    companion object {
        fun fromStorage(value: String?) = entries.firstOrNull { it.storageValue == value } ?: ALL
    }
}
