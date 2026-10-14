package com.tcrrry.lyrics.engine.hook

import java.security.MessageDigest

/** Stamp rendered TTML with its actual settings, not a permanently disabling flag. */
internal class AutoLyricsCachePolicy(qualityFirst: Boolean, smoothShortUnits: Boolean, options: String) {
    private val hash = MessageDigest.getInstance("SHA-256")
        .digest("$qualityFirst|$smoothShortUnits|$options".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    private val marker = "<!--tcrrry-cache-policy-v1:$hash-->"
    fun matches(ttml: String): Boolean = marker in ttml.take(1024)
    fun stamp(ttml: String): String {
        val clean = strip(ttml)
        val declarationEnd = if (clean.startsWith("<?xml")) (clean.indexOf("?>") + 2).coerceAtLeast(0) else 0
        return clean.take(declarationEnd) + marker + clean.drop(declarationEnd)
    }
    companion object {
        private val markerPattern = Regex("<!--tcrrry-cache-policy-v1:[0-9a-f]{64}-->")
        fun strip(ttml: String): String = markerPattern.replace(ttml, "")
    }
}
