package com.tcrrry.lyrics.engine.ui

import org.junit.Assert.*
import org.junit.Test

class LanguageSearchPolicyTest {
    private val codes = listOf("auto", "ja", "ko", "en", "fr", "de", "zh")
    @Test fun chineseEnglishCodeAndNativeNamesResolveToTheSameLanguage() {
        for (query in listOf("日语", "Japanese", "ja", "日本語"))
            assertEquals(listOf("ja"),LanguageSearchPolicy.filter(codes,query))
        assertEquals(listOf("en"),LanguageSearchPolicy.filter(codes," ENGLISH "))
        assertEquals(listOf("auto"),LanguageSearchPolicy.filter(codes,"自动"))
    }
    @Test fun blankRestoresAllAndUnknownReturnsNoResults() {
        assertEquals(codes,LanguageSearchPolicy.filter(codes," "))
        assertTrue(LanguageSearchPolicy.filter(codes,"不存在的语言").isEmpty())
    }
}
