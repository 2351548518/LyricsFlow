package com.example.lyricsflow.core

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

data class SearchPage(
    val tracks: List<Track>,
    val more: Boolean = false,
    val total: Int? = null,
    val channel: String = "",
    val note: String = "",
)

object SongSearch {
    private val brackets = Regex("\\([^)]*\\)|（[^）]*）|【[^】]*】|\\[[^]]*]")
    private val versions =
        Regex(
            "live|remix|cover|acoustic|instrumental|karaoke|sped|slowed|version|edit|english|japanese|korean|现场|演唱会|翻唱|伴奏|纯音乐|重制|混音|合唱|独唱|国语|粤语|日语|韩语|英文|版",
            RegexOption.IGNORE_CASE,
        )

    fun normalized(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT).filter {
            it.isLetterOrDigit()
        }

    /** Only remove translated aliases; keep performance/version qualifiers significant. */
    fun withoutAliases(text: String): String =
        brackets
            .replace(text) { match ->
                val inner = match.value.drop(1).dropLast(1)
                if (
                    versions.containsMatchIn(inner) ||
                        inner.none {
                            it in '\u3400'..'\u9fff' ||
                                it in '\u3040'..'\u30ff' ||
                                it in '\uac00'..'\ud7af'
                        }
                )
                    match.value
                else " "
            }
            .replace(Regex("\\s+"), " ")
            .trim()

    fun queries(query: String, current: Track? = null, expand: Boolean = true): List<String> {
        val q = Normalizer.normalize(query.trim(), Normalizer.Form.NFKC).replace(Regex("\\s+"), " ")
        if (!expand) return listOf(q).filter { it.isNotBlank() }
        val result = mutableListOf(q, withoutAliases(q))
        if (current != null && normalized(q) == normalized("${current.title} ${current.artist}")) {
            val title = withoutAliases(current.title)
            result += "$title ${withoutAliases(current.artist)}".trim()
            val primary = current.artist.split(Regex("\\s*[,，/、;&]\\s*")).first().trim()
            if (primary != current.artist.trim()) result += "$title $primary"
            result += title
        }
        return result.filter { it.isNotBlank() }.distinct().take(3)
    }

    fun identity(t: Track): String =
        "${t.provider}:${t.sourceId.ifBlank { t.mid.ifBlank { t.key } }}"

    fun similarity(left: String, right: String): Double {
        val a = normalized(left).take(256)
        val b = normalized(right).take(256)
        if (a == b) return if (a.isEmpty()) 0.0 else 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a.contains(b) || b.contains(a))
            return .72 + .28 * minOf(a.length, b.length) / max(a.length, b.length)
        var previous = IntArray(b.length + 1) { it }
        a.forEachIndexed { i, ac ->
            val next = IntArray(b.length + 1)
            next[0] = i + 1
            b.forEachIndexed { j, bc ->
                next[j + 1] =
                    minOf(next[j] + 1, previous[j + 1] + 1, previous[j] + if (ac == bc) 0 else 1)
            }
            previous = next
        }
        return 1.0 - previous.last().toDouble() / max(a.length, b.length)
    }

    fun rank(rows: List<Track>, query: String, current: Track? = null): List<Track> {
        val contextual = current?.takeIf {
            normalized(query) in setOf(normalized(it.title), normalized("${it.title} ${it.artist}"))
        }
        return rows.distinctBy(::identity).sortedByDescending { t ->
            maxOf(
                similarity(query, t.title),
                similarity(query, t.artist),
                similarity(withoutAliases(query), "${withoutAliases(t.title)} ${t.artist}"),
            ) +
                if (contextual == null) 0.0
                else .25 * similarity(withoutAliases(contextual.title), withoutAliases(t.title))
        }
    }

    data class MatchDecision(val track: Track?, val reason: String, val candidates: Int)

    /** Keep aliases supplied by the same credit together: 瑞葵(mizuki) is one artist. */
    fun artistGroups(raw: String): List<Set<String>> =
        raw.split(
                Regex(
                    "\\s*(?:,|，|/|、|;|；|&|\\+|\\bfeat\\.?|\\bft\\.?)\\s*",
                    RegexOption.IGNORE_CASE,
                )
            )
            .filter { it.isNotBlank() }
            .map { credit ->
                val parens = Regex("\\(([^)]*)\\)|（([^）]*)）")
                val aliases =
                    parens
                        .findAll(credit)
                        .map { it.groupValues[1].ifBlank { it.groupValues[2] } }
                        .filter { !versions.containsMatchIn(it) }
                        .toList()
                (listOf(credit, parens.replace(credit, " ")) + aliases)
                    .map(::normalized)
                    .filter { it.isNotBlank() }
                    .toSet()
            }

    private fun creditsAgree(left: String, right: String): Boolean {
        if (normalized(left).isNotBlank() && normalized(left) == normalized(right)) return true
        val a = artistGroups(left)
        val b = artistGroups(right)
        // A primary-artist substring alone must not silently accept a different guest vocalist.
        return a.isNotEmpty() &&
            a.size == b.size &&
            a.all { x -> b.any { y -> x.intersect(y).isNotEmpty() } } &&
            b.all { y -> a.any { x -> x.intersect(y).isNotEmpty() } }
    }

    fun decide(current: Track, candidates: List<Track>): MatchDecision {
        fun title(t: Track) = normalized(withoutAliases(t.title))
        val all = candidates.distinctBy(::identity)
        fun rejected(reason: String) = MatchDecision(null, reason, all.size)
        if (all.isEmpty()) return rejected("没有返回歌曲候选")
        val titles = all.filter { title(current).isNotBlank() && title(current) == title(it) }
        if (titles.isEmpty()) return rejected("歌名或录音版本不一致")
        val artists = titles.filter { creditsAgree(current.artist, it.artist) }
        if (artists.isEmpty()) return rejected("歌手名称或别名无法确认")
        val eligible = artists.filter {
            current.duration <= 0 || it.duration <= 0 || abs(current.duration - it.duration) <= 5000
        }
        if (eligible.isEmpty()) return rejected("歌曲时长相差超过 5 秒")
        if (eligible.size == 1) return MatchDecision(eligible.single(), "歌名、歌手与时长符合", all.size)
        val album = eligible.filter {
            current.album.isNotBlank() &&
                normalized(withoutAliases(current.album)) == normalized(withoutAliases(it.album))
        }
        if (album.size == 1) return MatchDecision(album.single(), "专辑唯一对应", all.size)
        val recording = album.ifEmpty { eligible }
        if (
            current.duration > 0 &&
                recording.all { it.duration > 0 } &&
                recording.maxOf { it.duration } - recording.minOf { it.duration } <= 2000
        ) {
            val closest = recording.minBy { abs(it.duration - current.duration) }
            return MatchDecision(closest, "相同歌手、版本与近似时长的多专辑发行", all.size)
        }
        return rejected("存在多个不同录音，需确认版本")
    }

    fun automatic(current: Track, candidates: List<Track>): Track? =
        decide(current, candidates).track
}
