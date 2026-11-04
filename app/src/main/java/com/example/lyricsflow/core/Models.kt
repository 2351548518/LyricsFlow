package com.example.lyricsflow.core

import java.security.MessageDigest

enum class Provider(val label: String) {
    QQ("QQ 音乐"),
    NETEASE("网易云音乐"),
    AMLL("AMLL"),
    LRCLIB("LRCLIB"),
    LOCAL("本地"),
}

fun playbackAppName(packageName: String): String =
    when (packageName) {
        "com.spotify.music" -> "Spotify"
        "com.tencent.qqmusic" -> "QQ 音乐"
        "com.netease.cloudmusic" -> "网易云音乐"
        "com.apple.android.music" -> "Apple Music"
        "com.google.android.apps.youtube.music" -> "YouTube Music"
        "org.videolan.vlc" -> "VLC"
        "in.krosbits.musicolet" -> "Musicolet"
        else -> "本机播放器"
    }

fun needsChineseTranslation(text: String): Boolean = text.any {
    it.isLetter() && it !in '\u3400'..'\u9fff'
}

fun needsRomanization(text: String): Boolean = text.any {
    it.isLetter() && Character.UnicodeScript.of(it.code) != Character.UnicodeScript.LATIN
}

fun graphemes(text: String): List<String> {
    val iterator = java.text.BreakIterator.getCharacterInstance(java.util.Locale.ROOT)
    iterator.setText(text)
    val result = mutableListOf<String>()
    var begin = iterator.first()
    var end = iterator.next()
    while (end != java.text.BreakIterator.DONE) {
        result += text.substring(begin, end)
        begin = end
        end = iterator.next()
    }
    return result
}

data class Track(
    val title: String,
    val artist: String = "",
    val album: String = "",
    val duration: Long = 0,
    val provider: Provider = Provider.LOCAL,
    val sourceId: String = "",
    val mid: String = "",
) {
    val key: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        digest("${title.trim().lowercase()}|${artist.trim().lowercase()}|$album")
    }
}

data class Word(val text: String, val start: Long, val end: Long)

data class LyricAlignment(val text: String, val romanization: String, val translation: String)

fun validAlignment(text: String, parts: List<LyricAlignment>): Boolean =
    parts.isNotEmpty() && parts.size <= text.length && parts.joinToString("") { it.text } == text &&
        parts.all { part ->
            part.text.isNotEmpty() && part.romanization.length <= 2000 && part.translation.length <= 2000 &&
                !needsRomanization(part.romanization) &&
                (!part.text.any { it.isLetter() } ||
                    part.romanization.any { it.isLetter() } && part.translation.isNotBlank())
        }

fun Line.needsAlignment(): Boolean = text.any { it.isLetter() } && alignment.isEmpty()

data class Line(
    val start: Long?,
    val end: Long?,
    val text: String,
    val translation: String = "",
    val romanization: String = "",
    val words: List<Word> = emptyList(),
    val translationKind: String = "provider",
    val translationModel: String = "",
    val alignment: List<LyricAlignment> = emptyList(),
) {
    // Immutable rows are reused while the playback clock advances.
    val hasValidAlignment: Boolean by lazy(LazyThreadSafetyMode.PUBLICATION) { validAlignment(text, alignment) }
}

data class Lyrics(
    val lines: List<Line>,
    val source: Provider,
    val instrumental: Boolean = false,
    val providerRevision: Int = 0,
) {
    val needsTranslation: Boolean by lazy(LazyThreadSafetyMode.PUBLICATION) {
        lines.any { it.translation.isBlank() && needsChineseTranslation(it.text) }
    }
    val needsWordAlignment: Boolean by lazy(LazyThreadSafetyMode.PUBLICATION) { lines.any { it.needsAlignment() } }
    val hash: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        digest(lines.joinToString("\n") { "${it.start}:${it.end}:${it.text}:${it.words}" })
    }

    // Snapshots are immutable. Keep original row indices, including untimed/duplicate rows.
    private val timeline by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val indices = lines.indices.filter { lines[it].start != null }.toIntArray()
        val starts = LongArray(indices.size) { lines[indices[it]].start!! }
        if ((1 until starts.size).all { starts[it - 1] <= starts[it] }) starts to indices else null
    }

    fun active(position: Long): Int {
        val (starts, indices) = timeline ?: return lines.indexOfLast { it.start != null && it.start <= position }
        var low = 0
        var high = starts.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (starts[middle] <= position) low = middle + 1 else high = middle
        }
        return if (low == 0) -1 else indices[low - 1]
    }
}

data class Playback(
    val track: Track? = null,
    val packageName: String = "",
    val playing: Boolean = false,
    val position: Long = 0,
    val updatedAt: Long = 0,
    val speed: Float = 1f,
    val artwork: android.graphics.Bitmap? = null,
    val positionKnown: Boolean = true,
) {
    fun at(now: Long): Long {
        if (!positionKnown) return 0
        return (position +
                if (playing && updatedAt > 0) ((now - updatedAt).coerceAtLeast(0) * speed).toLong()
                else 0)
            .coerceIn(0, track?.duration?.takeIf { it > 0 } ?: Long.MAX_VALUE)
    }
}

data class Comment(
    val id: String,
    val author: String,
    val text: String,
    val likes: Int = 0,
    val time: Long = 0,
    val cursor: String = "",
)

data class CommentPage(val comments: List<Comment>, val more: Boolean, val cursor: String = "")

fun digest(value: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
    val digits = "0123456789abcdef"
    return CharArray(bytes.size * 2).apply {
        bytes.forEachIndexed { index, byte ->
            val unsigned = byte.toInt() and 255
            this[index * 2] = digits[unsigned ushr 4]
            this[index * 2 + 1] = digits[unsigned and 15]
        }
    }.concatToString()
}

object Demo {
    val track = Track("Somewhere, softer", "THE QUIET HOURS", "A softer place", 80000)
    val lyrics =
        Lyrics(
            listOf(
                    Triple(0L, "Let the morning find us slowly", "让清晨，慢慢找到我们"),
                    Triple(9000L, "Where the quiet river bends", "在静静的河流转弯的地方"),
                    Triple(18000L, "There is nothing we must carry", "不必再背负什么"),
                    Triple(27000L, "Leave a little room for the light", "为光，留一点空隙"),
                    Triple(37000L, "Let the city turn to gold", "让整座城市染上金色"),
                    Triple(46000L, "We can take the long way home", "我们可以绕远一点回家"),
                    Triple(56000L, "Every silence holds a story", "每一段沉默，都藏着故事"),
                    Triple(67000L, "Somewhere softer, we belong", "在柔软的某处，找到归属"),
                )
                .mapIndexed { i, (start, text, translation) ->
                    Line(
                        start,
                        listOf(9000L, 18000L, 27000L, 37000L, 46000L, 56000L, 67000L, 80000L)[i],
                        text,
                        translation,
                    )
                },
            Provider.LOCAL,
        )
}
