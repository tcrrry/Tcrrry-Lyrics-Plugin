package com.tcrrry.lyrics.engine.hook

import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Node
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler

/** Read real text-node timings through styling wrappers; never infer missing word ends. */
internal object NativeLyricsTimedWords {
    data class Parsed(val text: String, val words: List<Triple<Long, Long, String>>?)

    private fun time(text: String): Long? = runCatching {
        val value = when {
            text.endsWith("ms") -> text.dropLast(2).toDouble()
            text.endsWith("s") -> text.dropLast(1).toDouble() * 1000
            else -> text.split(':').fold(0.0) { value, part -> value * 60 + part.toDouble() } * 1000
        }
        value.takeIf { it.isFinite() && it >= 0 }?.toLong()
    }.getOrNull()

    fun parse(contents: String): Parsed? = runCatching {
        if (contents.length > 512 * 1024 || Regex("(?i)<!\\s*(?:DOCTYPE|ENTITY)").containsMatchIn(contents)) return null
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
        val builder = factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> InputSource(StringReader("")) }
            setErrorHandler(DefaultHandler())
        }
        val root = builder.parse(InputSource(StringReader("<root>$contents</root>"))).documentElement
        val words = mutableListOf<Triple<Long, Long, String>>()
        var pending = ""
        fun visit(node: Node, start: Long?, finish: Long?, depth: Int): Boolean {
            if (depth > 64 || words.size > 65536) return false
            when (node.nodeType) {
                Node.ELEMENT_NODE -> {
                    if (node !== root && node.nodeName.substringAfter(':') != "span") return false
                    val attributes = node.attributes
                    val begin = attributes.getNamedItem("begin")
                    val end = attributes.getNamedItem("end")
                    val a = if (begin == null) start else time(begin.nodeValue) ?: return false
                    val b = if (end == null) finish else time(end.nodeValue) ?: return false
                    if (a != null && b != null && b <= a) return false
                    if (start != null && a != null && a < start || finish != null && b != null && b > finish) return false
                    val children = node.childNodes
                    for (index in 0 until children.length) if (!visit(children.item(index), a, b, depth + 1)) return false
                }
                Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> {
                    val text = node.nodeValue.orEmpty()
                    if (text.isBlank()) pending += text
                    else {
                        if (start == null || finish == null || finish <= start) return false
                        val value = pending + text
                        pending = ""
                        val last = words.lastOrNull()
                        if (last?.first == start && last.second == finish) words[words.lastIndex] = last.copy(third = last.third + value)
                        else words += Triple(start, finish, value)
                    }
                }
                Node.COMMENT_NODE -> Unit
                else -> return false
            }
            return true
        }
        val valid = visit(root, null, null, 0)
        if (valid && words.isNotEmpty() && pending.isNotEmpty()) {
            val last = words.last()
            words[words.lastIndex] = last.copy(third = last.third + pending)
        }
        Parsed(root.textContent, words.takeIf { valid && it.isNotEmpty() })
    }.getOrNull()
}
