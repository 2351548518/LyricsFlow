package com.example.lyricsflow

import com.example.lyricsflow.core.*
import org.junit.Assert.*
import org.junit.Test

class TimelineTest {
    @Test
    fun translationLanguageAndGraphemes() {
        assertFalse(needsChineseTranslation("清晨，慢慢找到我们"))
        assertTrue(needsChineseTranslation("遠い街の灯りが"))
        assertTrue(needsChineseTranslation("A new day"))
        assertEquals(listOf("A", "😀", "é"), graphemes("A😀é"))
    }

    @Test
    fun lrcOffsetsMultipleTimestampsAndPlaintext() {
        val lines = LyricParser.lrc("[offset:100]\n[00:01.5][00:02.50]A\n[00:02.50]B", 5000)
        assertEquals(listOf(1600L, 2600L, 2600L), lines.map { it.start })
        assertEquals(listOf(2600L, 5000L, 5000L), lines.map { it.end })
        assertNull(LyricParser.lrc("plain text").first().start)
    }

    @Test
    fun yrcWordStartIsAbsolute() {
        val l = LyricParser.yrc("[12000,2000](12000,500,0)Hello (12500,1500,0)world").single()
        assertEquals(12000L, l.words[0].start)
        assertEquals(14000L, l.words[1].end)
        assertEquals("Hello world", l.text)
    }

    @Test
    fun clockFreezesPausedClampsAndScales() {
        val p =
            Playback(
                Track("T", duration = 10000),
                playing = true,
                position = 1000,
                updatedAt = 500,
                speed = 2f,
            )
        assertEquals(3000L, p.at(1500))
        assertEquals(10000L, p.at(20000))
        assertEquals(1000L, p.copy(playing = false).at(1500))
        assertEquals(0L, p.copy(positionKnown = false).at(5000))
    }

    @Test
    fun positiveDelayMeansLaterAndNoEarlyLine() {
        val l = Lyrics(LyricParser.lrc("[00:01.00]A\n[00:03.00]B", 5000), Provider.LOCAL)
        assertEquals(-1, l.active(900))
        assertEquals(0, l.active(3100 - 200))
        assertEquals(1, l.active(3100))
    }

    @Test
    fun xmlRejectsEntities() {
        assertThrows(IllegalArgumentException::class.java) {
            LyricParser.ttml(
                "<!DOCTYPE tt [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><tt><p>&x;</p></tt>"
            )
        }
    }

    @Test
    fun enhancedLrcKeepsActualWordTimes() {
        val l =
            LyricParser.lrc("[00:01.00]<00:01.00>Hello <00:02.00>world\n[00:04.00]Next", 6000)
                .first()
        assertEquals("Hello world", l.text)
        assertEquals(2000L, l.words[0].end)
        assertEquals(4000L, l.words[1].end)
    }

    @Test
    fun qrcUsesRealTrailingWordTimestamps() {
        val l = LyricParser.parse("[12000,2000]Hello (12000,500)world(12500,1500)").single()
        assertEquals("Hello world", l.text)
        assertEquals(12000L, l.words.first().start)
        assertEquals(14000L, l.words.last().end)
    }

    @Test
    fun overlayPauseBoundaryLockAndResume() {
        assertTrue(OverlayPolicy.visible(true, true, false, false, 1000, 300999))
        assertFalse(OverlayPolicy.visible(true, true, false, false, 1000, 301000))
        assertTrue(OverlayPolicy.visible(true, true, false, true, 1000, 500000))
        assertFalse(OverlayPolicy.visible(true, true, true, true, 0, 1000))
        assertFalse(OverlayPolicy.visible(true, false, false, true, 0, 1000))
    }

    @Test
    fun ttmlTimingAndWhitespace() {
        val l =
            LyricParser.ttml(
                    "<tt xmlns=\"http://www.w3.org/ns/ttml\"><body><div begin=\"2s\"><p begin=\"1s\" end=\"5s\"><span begin=\"1s\" end=\"2s\">A </span><span begin=\"2s\" end=\"5s\">day</span></p></div></body></tt>"
                )
                .single()
        assertEquals(3000L, l.start)
        assertEquals(7000L, l.end)
        assertEquals("A day", l.text)
        assertEquals(3000L, l.words.first().start)
    }
}
