package com.tcrrry.lyrics.engine.ui

import java.util.Locale

internal object LanguageSearchPolicy {
    fun filter(codes: List<String>, query: String): List<String> {
        val terms = query.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter(String::isNotBlank)
        if (terms.isEmpty()) return codes
        return codes.filter { code ->
            val locale = Locale.forLanguageTag(code)
            val names = if (code == "auto") "auto 自动判断 自动检测" else
                "$code ${locale.getDisplayLanguage(Locale.SIMPLIFIED_CHINESE)} ${locale.getDisplayLanguage(Locale.ENGLISH)} ${locale.getDisplayLanguage(locale)}"
            terms.all { names.lowercase(Locale.ROOT).contains(it) }
        }
    }
}
