package com.example.lyricsflow.core

import org.w3c.dom.Element
import org.w3c.dom.Node

/** AMLL uses song-absolute timestamps, including nested spans and div blocks. */
object AmllTtml {
    private const val TTM = "http://www.w3.org/ns/ttml#metadata"
    private const val XML = "http://www.w3.org/XML/1998/namespace"

    private fun role(e: Element) =
        e.getAttributeNS(TTM, "role").ifBlank { e.getAttribute("ttm:role") }

    private fun time(raw: String): Long? {
        if (raw.isBlank()) return null
        val value =
            when {
                raw.endsWith("ms") -> raw.dropLast(2).toDoubleOrNull()
                raw.endsWith("s") -> raw.dropLast(1).toDoubleOrNull()?.times(1000)
                raw.contains(':') ->
                    raw.split(':').fold(0.0) { sum, field ->
                        sum * 60 + (field.toDoubleOrNull() ?: error("TTML 时间格式无效"))
                    } * 1000
                else -> null
            } ?: error("TTML 时间格式无效")
        require(value.isFinite() && value >= 0 && value <= 24 * 60 * 60 * 1000) { "TTML 时间超出范围" }
        return value.toLong()
    }

    fun parse(raw: String): List<Line> {
        val doc = LyricParser.safeXml(raw.removePrefix("\uFEFF"))
        require(doc.documentElement.localName == "tt" || doc.documentElement.tagName == "tt") {
            "AMLL 响应不是 TTML"
        }
        val paragraphs = doc.getElementsByTagNameNS("*", "p")
        require(paragraphs.length <= 10000) { "TTML 行数过多" }
        val result = mutableListOf<Line>()
        for (i in 0 until paragraphs.length) {
            val p = paragraphs.item(i) as Element
            val background = mutableListOf<Element>()
            fun line(root: Element, bg: Boolean): Line? {
                val parts = mutableListOf<Triple<String, Long?, Long?>>()
                val translations = mutableListOf<Pair<String, String>>()
                val romans = mutableListOf<String>()
                fun visit(node: Node, inheritedStart: Long? = null, inheritedEnd: Long? = null) {
                    if (node is Element) {
                        when (role(node)) {
                            "x-translation" -> {
                                translations +=
                                    node.getAttributeNS(XML, "lang") to node.textContent.trim()
                                return
                            }
                            "x-roman" -> {
                                romans += node.textContent.trim()
                                return
                            }
                            "x-bg" ->
                                if (node !== root) {
                                    if (!bg) background += node
                                    return
                                }
                        }
                        if (node.localName == "br" || node.tagName == "br") {
                            parts += Triple("\n", null, null)
                            return
                        }
                        val begin = time(node.getAttribute("begin")) ?: inheritedStart
                        val end =
                            time(node.getAttribute("end"))
                                ?: time(node.getAttribute("dur"))?.let { begin?.plus(it) }
                                ?: inheritedEnd
                        for (j in 0 until node.childNodes.length) visit(
                            node.childNodes.item(j),
                            begin,
                            end,
                        )
                    } else if (
                        node.nodeType == Node.TEXT_NODE || node.nodeType == Node.CDATA_SECTION_NODE
                    ) {
                        val text = node.nodeValue.orEmpty()
                        // Ignore pretty-print indentation; preserve explicit inter-word spaces.
                        if (
                            text.isNotEmpty() &&
                                !(text.isBlank() && (text.contains('\n') || text.contains('\r')))
                        )
                            parts += Triple(text, inheritedStart, inheritedEnd)
                    }
                }
                // p timestamps must not be inherited as word timestamps for untimed plain text.
                for (j in 0 until root.childNodes.length) visit(root.childNodes.item(j))
                val text = parts.joinToString("") { it.first }.trim()
                if (text.isEmpty()) return null
                require(text.length <= 20000)
                val words = mutableListOf<Word>()
                var prefix = ""
                for ((value, start, end) in parts) {
                    if (start != null && end != null && value.isNotBlank()) {
                        require(end >= start) { "TTML 单词结束早于开始" }
                        words += Word(prefix + value, start, end)
                        prefix = ""
                    } else if (words.isNotEmpty()) {
                        words[words.lastIndex] = words.last().copy(text = words.last().text + value)
                    } else prefix += value
                }
                val start =
                    time(root.getAttribute("begin"))
                        ?: words.minOfOrNull { it.start }
                        ?: time(p.getAttribute("begin"))
                val end =
                    time(root.getAttribute("end"))
                        ?: time(root.getAttribute("dur"))?.let { start?.plus(it) }
                        ?: words.maxOfOrNull { it.end }
                        ?: time(p.getAttribute("end"))
                require(start == null || end == null || end >= start) { "TTML 歌词时间无效" }
                val translation =
                    translations.firstOrNull {
                        it.first.equals("zh-CN", true) || it.first.equals("zh-Hans", true)
                    }
                        ?: translations.firstOrNull { it.first.startsWith("zh", true) }
                        ?: translations.firstOrNull { it.first.isBlank() }
                // Distinguish backing vocals without dropping their own timing/annotations.
                if (bg && words.isNotEmpty()) {
                    words[0] = words.first().copy(text = "（" + words.first().text)
                    words[words.lastIndex] = words.last().copy(text = words.last().text + "）")
                }
                return Line(
                    start,
                    end,
                    if (bg) "（$text）" else text,
                    translation?.second.orEmpty(),
                    romans.joinToString(" "),
                    words,
                )
            }
            line(p, false)?.let { result += it }
            background.forEach { node -> line(node, true)?.let { result += it } }
            require(result.size <= 10000)
        }
        require(result.isNotEmpty()) { "AMLL TTML 中没有可显示歌词" }
        return result.sortedBy { it.start ?: Long.MAX_VALUE }
    }
}
