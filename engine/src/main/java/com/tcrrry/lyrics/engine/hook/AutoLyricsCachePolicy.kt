package com.tcrrry.lyrics.engine.hook

import java.security.MessageDigest

/** Stamp rendered TTML with its actual settings, not a permanently disabling flag. */
internal class AutoLyricsCachePolicy(qualityFirst: Boolean, smoothShortUnits: Boolean, options: String) {
    private val hash = MessageDigest.getInstance("SHA-256")
        .digest("$qualityFirst|$smoothShortUnits|$options".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    private val marker = "<!--tcrrry-cache-policy-v1:$hash-->"
    fun matches(ttml: String): Boolean = marker in ttml.take(2048)
    fun stamp(ttml: String): String = insertHeaderComment(strip(ttml), marker)

    companion object {
        private val markerPattern = Regex("<!--tcrrry-cache-policy-v1:[0-9a-f]{64}-->")
        private val sourcePattern = Regex("<!--tcrrry-cache-source:(.*?)-->")
        private val enrichmentPattern = Regex("<!--tcrrry-enrichment-complete=(true|false)-->")

        private fun insertHeaderComment(ttml: String, comment: String): String {
            val declarationEnd = if (ttml.startsWith("<?xml")) (ttml.indexOf("?>") + 2).coerceAtLeast(0) else 0
            return ttml.take(declarationEnd) + comment + ttml.drop(declarationEnd)
        }

        fun strip(ttml: String): String = markerPattern.replace(ttml, "")

        fun stampSource(ttml: String, source: String): String {
            val clean = sourcePattern.replace(ttml, "")
            val safe = source.replace("-->", "").replace("\n", "").replace("\r", "")
            return insertHeaderComment(clean, "<!--tcrrry-cache-source:$safe-->")
        }

        fun readSource(ttml: String): String? =
            sourcePattern.find(ttml.take(2048))?.groupValues?.get(1)?.takeIf(String::isNotBlank)

        fun stampEnrichmentComplete(ttml: String, complete: Boolean): String {
            val clean = enrichmentPattern.replace(ttml, "")
            return insertHeaderComment(clean, "<!--tcrrry-enrichment-complete=$complete-->")
        }

        private val body = Regex("(?is)<body\\b[^>]*>.*?</body\\s*>")

        fun keepTranslatedDocument(previous: String, incoming: String): Boolean {
            val source = readSource(incoming) ?: return false
            if (source != readSource(previous)) return false
            val pending = isEnrichmentIncomplete(incoming) || !TranslationOutcomeCache.canReuse(incoming, true)
            if (!pending) return false
            val previousBody = body.find(previous)?.value ?: return false
            if (previousBody != body.find(incoming)?.value) return false
            val previousRows = TtmlSubtitleTrack.subtitleValues(previous).keys
            val incomingRows = TtmlSubtitleTrack.subtitleValues(incoming).keys
            return previousRows.size > incomingRows.size && previousRows.containsAll(incomingRows)
        }

        fun isEnrichmentIncomplete(ttml: String): Boolean =
            enrichmentPattern.find(ttml.take(2048))?.groupValues?.get(1) == "false"
    }
}
