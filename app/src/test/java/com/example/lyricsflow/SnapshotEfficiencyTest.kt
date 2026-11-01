package com.example.lyricsflow

import com.example.lyricsflow.core.*
import java.security.MessageDigest
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class SnapshotEfficiencyTest {
    @Test fun fingerprintsRemainCompatibleAndAreReused() {
        val track = Track("  A SONG 日本語  ", "Singer", "Album")
        val lyric = Lyrics(listOf(Line(0, 1000, "Original", words = listOf(Word("Original", 0, 1000)))), Provider.QQ)
        fun originalDigest(raw: String) = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
        for (raw in listOf("", "abc", "中文😀", (0..255).map { it.toChar() }.joinToString("")))
            assertEquals(originalDigest(raw), digest(raw))
        assertEquals(originalDigest("${track.title.trim().lowercase()}|${track.artist.trim().lowercase()}|${track.album}"), track.key)
        assertEquals(originalDigest(lyric.lines.joinToString("\n") { "${it.start}:${it.end}:${it.text}:${it.words}" }), lyric.hash)
        assertSame(track.key, track.key)
        assertSame(lyric.hash, lyric.hash)
        assertNotEquals(track.key, track.copy(title = "Different song").key)
        assertEquals(lyric.hash, lyric.copy(lines = lyric.lines.map { it.copy(translation = "译文") }).hash)
        assertNotEquals(lyric.hash, lyric.copy(lines = lyric.lines.map { it.copy(text = "Different text") }).hash)
    }

    @Test fun indexedTimelineMatchesLinearLookupForNullDuplicateAndUnsortedRows() {
        val random = Random(7)
        val lines = (0 until 1000).map { i -> Line(if (i % 9 == 0) null else i / 3 * 1000L, null, "Line $i") }
        for (rows in listOf(lines, lines.shuffled(random), emptyList(), listOf(Line(null, null, "plain")))) {
            val lyrics = Lyrics(rows, Provider.LOCAL)
            for (position in listOf(Long.MIN_VALUE, -1, 0, 1000, Long.MAX_VALUE) + List(1000) { random.nextLong(-1000, 400_000) }) {
                assertEquals(rows.indexOfLast { it.start != null && it.start <= position }, lyrics.active(position))
            }
        }
    }

    @Test fun overlayKeysCannotCollideWhenTextAndTranslationBoundariesChange() {
        val prefs = Preferences()
        val first = Line(0, 1000, "ab", translation = "c")
        val second = Line(1000, 2000, "a", translation = "bc")
        val a = OverlayRenderKey("ab", first, -1, prefs, "")
        assertNotEquals(a, OverlayRenderKey("a", second, -1, prefs, ""))
        assertEquals(a, OverlayRenderKey("ab", first.copy(), -1, prefs, ""))
        assertNotEquals(a, a.copy(preferences = prefs.copy(overlayAlpha = 0f)))
    }
}
