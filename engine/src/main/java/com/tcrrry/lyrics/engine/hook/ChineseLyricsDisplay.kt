package com.tcrrry.lyrics.engine.hook

import android.icu.text.Transliterator
import android.os.Build
import androidx.annotation.RequiresApi
import com.tcrrry.lyrics.engine.lyrics.ChineseSubtitlePolicy
import com.tcrrry.lyrics.engine.lyrics.TtmlInputPolicy
import java.io.StringReader
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler

/** A render copy only: source/cache TTML stays reversible and timing attributes are never edited. */
internal object ChineseLyricsDisplay {
    @RequiresApi(29)
    private object Converter {
        val value: Transliterator = Transliterator.getInstance("Traditional-Simplified")
    }
    private fun simplify(text: String): String =
        if (Build.VERSION.SDK_INT >= 29) Converter.value.transliterate(text) else text
    private val unsafeXml = Regex("(?i)<!\\s*(?:DOCTYPE|ENTITY)")
    private fun han(text: String) = text.any { it in '\u3400'..'\u9fff' || it in '\uf900'..'\ufaff' }
    private fun japaneseOrKorean(text: String) = text.any { it in '\u3040'..'\u30ff' || it in '\uac00'..'\ud7af' }
    private fun name(node: Node) = node.nodeName.substringAfter(':')
    private fun chinese(language: String) = language.lowercase(java.util.Locale.ROOT)
        .let { it == "zh" || it.startsWith("zh-") || it == "cmn" || it.startsWith("cmn-") || it == "yue" || it.startsWith("yue-") }
    private fun foreign(language: String) = language.lowercase(java.util.Locale.ROOT)
        .let { it == "ja" || it.startsWith("ja-") || it == "ko" || it.startsWith("ko-") }

    @Synchronized fun render(ttml: String, enabled: Boolean): String {
        if (!enabled || (!han(ttml) && !ttml.contains("&#")) || ttml.length > TtmlInputPolicy.MAX_TTML_BYTES || unsafeXml.containsMatchIn(ttml)) return ttml
        return runCatching {
            val builder = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
                .newDocumentBuilder().apply {
                    setEntityResolver { _, _ -> InputSource(StringReader("")) }
                    setErrorHandler(DefaultHandler())
                }
            val document = builder.parse(InputSource(StringReader(ttml)))
            val root = document.documentElement
            val elements = root.getElementsByTagName("*")
            val lines = (0 until elements.length).map { elements.item(it) }.filter { name(it) == "p" }.map { it.textContent }
            val rootLanguage = TtmlOriginalLanguage.read(ttml) ?: root.getAttribute("xml:lang")
            // Supplemented tracks can use a synthetic ko root to expose native subtitles.
            val chineseMixedBody = lines.any(::han) && lines.none(::japaneseOrKorean)
            val primaryChinese = chinese(rootLanguage) || (!rootLanguage.startsWith("ja", true) &&
                (ChineseSubtitlePolicy.isChineseSong(lines) || chineseMixedBody))
            var changed = false
            fun convertRow(element: Element) {
                val textNodes = mutableListOf<Node>()
                fun collect(node: Node, depth: Int) {
                    check(depth <= 64)
                    if (node.nodeType == Node.TEXT_NODE || node.nodeType == Node.CDATA_SECTION_NODE) textNodes += node
                    else if (node is Element) {
                        check(!foreign(node.getAttribute("xml:lang")))
                        for (index in 0 until node.childNodes.length) collect(node.childNodes.item(index), depth + 1)
                    }
                }
                if (runCatching { collect(element, 0) }.isFailure) return
                val old = textNodes.joinToString("") { it.nodeValue }
                if (!han(old) || japaneseOrKorean(old)) return
                val next = simplify(old)
                if (next == old || next.length != old.length) return
                var at = 0
                for (node in textNodes) {
                    val end = at + node.nodeValue.length
                    node.nodeValue = next.substring(at, end)
                    at = end
                }
                changed = true
            }
            fun visit(node: Node, scope: Boolean, depth: Int) {
                check(depth <= 64)
                when (node.nodeType) {
                    Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> if (scope && han(node.nodeValue.orEmpty())) {
                        val old = node.nodeValue
                        val next = simplify(old)
                        if (next != old) { node.nodeValue = next; changed = true }
                    }
                    Node.ELEMENT_NODE -> {
                        val element = node as Element
                        val language = element.getAttribute("xml:lang")
                        val selected = when (name(element)) {
                            "body" -> primaryChinese
                            "translation" -> chinese(language) || (language.isBlank() && han(element.textContent) && !japaneseOrKorean(element.textContent))
                            "transliteration" -> primaryChinese
                            "p" -> scope && !japaneseOrKorean(element.textContent)
                            else -> scope
                        } && (!foreign(language) || name(element) == "transliteration")
                        if (selected && name(element) in setOf("p", "text")) {
                            convertRow(element)
                            return
                        }
                        val children = element.childNodes
                        for (index in 0 until children.length) visit(children.item(index), selected, depth + 1)
                    }
                }
            }
            visit(root, false, 0)
            if (!changed) return ttml
            val output = StringWriter()
            TransformerFactory.newInstance().newTransformer().apply {
                setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, if (ttml.startsWith("<?xml")) "no" else "yes")
                setOutputProperty(OutputKeys.INDENT, "no")
                setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            }.transform(DOMSource(document), StreamResult(output))
            output.toString()
        }.getOrElse { ttml }
    }
}
