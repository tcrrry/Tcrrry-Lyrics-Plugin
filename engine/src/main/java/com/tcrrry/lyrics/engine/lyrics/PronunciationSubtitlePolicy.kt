package com.tcrrry.lyrics.engine.lyrics

import java.text.Normalizer
import java.util.Locale

/** Reject redundant Latin-script echoes when lyrics do not require transliteration. */
internal object PronunciationSubtitlePolicy {
    private fun normalized(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKD)
            .filter(Char::isLetterOrDigit)
            .lowercase(Locale.ROOT)

    private fun hasNonLatinLetter(text: String): Boolean =
        text.codePoints().anyMatch { code ->
            Character.isLetter(code) && Character.UnicodeScript.of(code) != Character.UnicodeScript.LATIN
        }

    fun usable(original: String, pronunciation: String?): String? {
        val value = pronunciation?.trim()?.takeIf(String::isNotBlank) ?: return null
        val normalizedValue = normalized(value)
        if (normalizedValue.isEmpty() || normalizedValue == normalized(original)) return null
        if (!hasNonLatinLetter(original) && !hasNonLatinLetter(value)) return null
        return value
    }
}
