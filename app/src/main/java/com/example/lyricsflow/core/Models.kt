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
    val key: String
        get() = digest("${title.trim().lowercase()}|${artist.trim().lowercase()}|$album")
}

data class Word(val text: String, val start: Long, val end: Long)

data class Line(
    val start: Long?,
    val end: Long?,
    val text: String,
    val translation: String = "",
    val romanization: String = "",
    val words: List<Word> = emptyList(),
    val translationKind: String = "provider",
    val translationModel: String = "",
)

data class Lyrics(
    val lines: List<Line>,
    val source: Provider,
    val instrumental: Boolean = false,
    val providerRevision: Int = 0,
) {
    val hash
        get() = digest(lines.joinToString("\n") { "${it.start}:${it.end}:${it.text}:${it.words}" })

    fun active(position: Long): Int = lines.indexOfLast { it.start != null && it.start <= position }
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

fun digest(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") {
        "%02x".format(it)
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
