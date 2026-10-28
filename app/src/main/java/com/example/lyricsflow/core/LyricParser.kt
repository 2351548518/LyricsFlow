package com.example.lyricsflow.core

import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

/**
 * All timings are milliseconds. Missing timing stays missing; word timings are never fabricated.
 */
object LyricParser {
    private val stamp = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")

    internal fun safeXml(raw: String): org.w3c.dom.Document {
        require(
            raw.length <= 2_000_000 &&
                !raw.contains("<!DOCTYPE", true) &&
                !raw.contains("<!ENTITY", true)
        ) {
            "不允许 XML 实体或 DTD"
        }
        // Android's Harmony parser rejects some desktop Xerces feature names. The preflight and
        // resolver are mandatory on both runtimes.
        val sax = javax.xml.parsers.SAXParserFactory.newInstance().newSAXParser()
        var depth = 0
        sax.parse(
            InputSource(StringReader(raw)),
            object : org.xml.sax.helpers.DefaultHandler() {
                override fun startElement(
                    uri: String?,
                    localName: String?,
                    qName: String?,
                    attributes: org.xml.sax.Attributes?,
                ) {
                    depth++
                    require(depth <= 64) { "XML 嵌套层数过多" }
                }

                override fun endElement(uri: String?, localName: String?, qName: String?) {
                    depth--
                }

                override fun resolveEntity(publicId: String?, systemId: String?): InputSource {
                    throw org.xml.sax.SAXException("不允许外部实体")
                }
            },
        )
        val f =
            DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isExpandEntityReferences = false
            }
        for ((name, value) in
            listOf(
                "http://apache.org/xml/features/disallow-doctype-decl" to true,
                "http://xml.org/sax/features/external-general-entities" to false,
                "http://xml.org/sax/features/external-parameter-entities" to false,
            )) runCatching { f.setFeature(name, value) }
        val builder = f.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> throw org.xml.sax.SAXException("不允许外部实体") }
        return builder.parse(InputSource(StringReader(raw)))
    }

    private fun stampTime(tag: MatchResult, offset: Long): Long =
        (tag.groupValues[1].toLong() * 60000 +
                tag.groupValues[2].toLong() * 1000 +
                (tag.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0) +
                offset)
            .coerceAtLeast(0)

    fun lrc(raw: String, duration: Long = 0): List<Line> {
        require(raw.length <= 2_000_000) { "歌词超过 2 MB" }
        val offset =
            Regex("\\[offset:([+-]?\\d+)]", RegexOption.IGNORE_CASE)
                .find(raw)
                ?.groupValues
                ?.get(1)
                ?.toLongOrNull() ?: 0
        val rows = mutableListOf<Line>()
        for (row in raw.lineSequence()) {
            val matches = stamp.findAll(row).toList()
            val text = row.replace(stamp, "").trim()
            for (m in matches) {
                val inline =
                    Regex("<(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?>([^<]*)").findAll(text).toList()
                val words = mutableListOf<Word>()
                for (i in inline.indices) {
                    val w = inline[i]
                    val next = inline.getOrNull(i + 1)
                    words +=
                        Word(w.groupValues[4], stampTime(w, offset), stampTime(next ?: w, offset))
                }
                rows +=
                    Line(
                        stampTime(m, offset),
                        null,
                        if (words.isEmpty()) text else words.joinToString("") { it.text },
                        words = words,
                    )
                require(rows.size <= 10000) { "歌词行数过多" }
            }
        }
        rows.sortBy { it.start }
        if (rows.isEmpty())
            return raw.lines()
                .filter { it.isNotBlank() && !it.startsWith("[") }
                .map { Line(null, null, it) }
        var nextStart: Long? = null
        var groupStart: Long? = null
        for (i in rows.lastIndex downTo 0) {
            val line = rows[i]
            if (groupStart != line.start) {
                nextStart = groupStart
                groupStart = line.start
            }
            val end = nextStart ?: duration.takeIf { it > (line.start ?: 0) }
            val words = line.words.toMutableList()
            if (words.isNotEmpty()) {
                val w = words.last()
                words[words.lastIndex] = w.copy(end = (end ?: w.end).coerceAtLeast(w.start))
            }
            rows[i] = line.copy(end = end, words = words)
        }
        return rows
    }

    fun yrc(raw: String, duration: Long = 0): List<Line> =
        raw.lineSequence()
            .mapNotNull { row ->
                val head = Regex("^\\[(\\d+),(\\d+)]").find(row) ?: return@mapNotNull null
                val matches =
                    Regex("\\((\\d+),(\\d+),\\d+\\)([^<(]*)")
                        .findAll(row.substring(head.value.length))
                        .toList()
                val words = matches.map { m ->
                    val s = m.groupValues[1].toLong()
                    Word(m.groupValues[3], s, s + m.groupValues[2].toLong())
                }
                val s = head.groupValues[1].toLong()
                Line(
                    s,
                    s + head.groupValues[2].toLong(),
                    words.joinToString("") { it.text },
                    words = words,
                )
            }
            .take(10000)
            .toList()
            .ifEmpty { lrc(raw, duration) }

    fun merge(
        original: List<Line>,
        translated: String,
        romanized: String,
        duration: Long,
    ): List<Line> {
        val t = lrc(translated, duration)
        val r = lrc(romanized, duration)
        return original.map { line ->
            line.copy(
                translation =
                    t.firstOrNull { line.start != null && it.start == line.start }?.text.orEmpty(),
                romanization =
                    r.firstOrNull { line.start != null && it.start == line.start }?.text.orEmpty(),
            )
        }
    }

    fun ttml(raw: String): List<Line> {
        require(
            raw.length <= 2_000_000 &&
                !raw.contains("<!DOCTYPE", true) &&
                !raw.contains("<!ENTITY", true)
        ) {
            "不允许 XML 实体或 DTD"
        }
        val doc = safeXml(raw)
        val nodes = doc.getElementsByTagNameNS("*", "p")
        fun time(s: String): Long? =
            when {
                s.endsWith("ms") -> s.dropLast(2).toDoubleOrNull()?.toLong()
                s.endsWith("s") -> s.dropLast(1).toDoubleOrNull()?.times(1000)?.toLong()
                s.contains(":") ->
                    s.split(':')
                        .fold(0.0) { a, b -> a * 60 + (b.toDoubleOrNull() ?: 0.0) }
                        .times(1000)
                        .toLong()
                else -> null
            }
        return (0 until nodes.length.coerceAtMost(10000))
            .map { i ->
                val el = nodes.item(i) as Element
                var parent = el.parentNode
                var parentStart = 0L
                while (parent is Element) {
                    parentStart += time(parent.getAttribute("begin")) ?: 0
                    parent = parent.parentNode
                }
                val s = time(el.getAttribute("begin"))?.plus(parentStart)
                val end =
                    time(el.getAttribute("end"))?.plus(parentStart)
                        ?: time(el.getAttribute("dur"))?.let { d -> s?.plus(d) }
                val spans = el.getElementsByTagNameNS("*", "span")
                val words =
                    (0 until spans.length).mapNotNull { n ->
                        val w = spans.item(n) as Element
                        val ws = time(w.getAttribute("begin")) ?: return@mapNotNull null
                        val we =
                            time(w.getAttribute("end"))
                                ?: time(w.getAttribute("dur"))?.plus(ws)
                                ?: return@mapNotNull null
                        Word(w.textContent, ws + parentStart, we + parentStart)
                    }
                Line(s, end, el.textContent, words = words)
            }
            .sortedBy { it.start ?: Long.MAX_VALUE }
    }

    fun parse(raw: String, duration: Long = 0): List<Line> =
        when {
            raw.contains("LyricContent=") ||
                Regex("(?m)^\\[\\d+,\\d+][^(\\r\\n]+\\(\\d+,\\d+\\)").containsMatchIn(raw) ->
                qrc(raw)
            raw.trimStart().startsWith("<") -> ttml(raw)
            Regex("(?m)^\\[\\d+,\\d+]\\(").containsMatchIn(raw) -> yrc(raw, duration)
            else -> lrc(raw, duration)
        }

    fun qrc(raw: String): List<Line> {
        require(
            raw.length <= 2_000_000 &&
                !raw.contains("<!DOCTYPE", true) &&
                !raw.contains("<!ENTITY", true)
        ) {
            "QRC XML 不安全或过大"
        }
        val content =
            if (raw.trimStart().startsWith("<")) {
                val d = safeXml(raw)
                val nodes = d.getElementsByTagName("Lyric_1")
                require(nodes.length > 0) { "QRC 缺少 Lyric_1" }
                (nodes.item(0) as Element).getAttribute("LyricContent")
            } else raw
        val rows = mutableListOf<Line>()
        for (row in content.lineSequence()) {
            val head = Regex("^\\[(\\d+),(\\d+)]").find(row) ?: continue
            val words =
                Regex("([^()]+)\\((\\d+),(\\d+)\\)")
                    .findAll(row.substring(head.value.length))
                    .map { m ->
                        val start = m.groupValues[2].toLong()
                        Word(m.groupValues[1], start, start + m.groupValues[3].toLong())
                    }
                    .toList()
            val start = head.groupValues[1].toLong()
            rows +=
                Line(
                    start,
                    start + head.groupValues[2].toLong(),
                    words.joinToString("") { it.text },
                    words = words,
                )
            require(rows.size <= 10000)
        }
        return rows.sortedBy { it.start }
    }
}
