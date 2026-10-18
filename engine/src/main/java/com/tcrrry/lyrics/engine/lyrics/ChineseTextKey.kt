package com.tcrrry.lyrics.engine.lyrics

import android.os.Build
import android.icu.text.Transliterator
import androidx.annotation.RequiresApi

/** Comparison only: retain source and display strings, reject collisions at lookup sites. */
internal object ChineseTextKey {
    @RequiresApi(29)
    private object Converter { val value:Transliterator=Transliterator.getInstance("Traditional-Simplified") }
    @Synchronized fun of(text:String):String {
        if(Build.VERSION.SDK_INT<29 || Regex("[ぁ-ゖァ-ヺ가-힣]").containsMatchIn(text)) return text
        return Converter.value.transliterate(text)
    }
}
