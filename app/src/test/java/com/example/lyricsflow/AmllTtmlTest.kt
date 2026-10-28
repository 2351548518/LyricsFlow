package com.example.lyricsflow

import com.example.lyricsflow.core.*
import org.junit.Assert.*
import org.junit.Test

class AmllTtmlTest {
    private fun document(body: String) =
        """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata"><body><div begin="10s">$body</div></body></tt>"""

    @Test
    fun absoluteTimingSeparatesOriginalTranslationAndRomanization() {
        val line =
            AmllTtml.parse(
                    document(
                        """<p begin="10s" end="15s"><span begin="10s" end="12s">光の</span><span begin="12s" end="15s">向こうへ</span><span ttm:role="x-translation" xml:lang="zh-CN">去往光的彼方</span><span ttm:role="x-roman">hikari no mukou e</span></p>"""
                    )
                )
                .single()
        assertEquals(10000L, line.start)
        assertEquals(15000L, line.end)
        assertEquals("光の向こうへ", line.text)
        assertEquals("去往光的彼方", line.translation)
        assertEquals("hikari no mukou e", line.romanization)
        assertEquals(listOf(10000L, 12000L), line.words.map { it.start })
        assertEquals(line.text, line.words.joinToString("") { it.text })
    }

    @Test
    fun preservesExplicitSpacesAndDoesNotInventWordTimingForLineLyrics() {
        val rows =
            AmllTtml.parse(
                document(
                    """<p begin="10s" end="15s"><span begin="10s" end="12s">Hello</span> <span begin="12s" end="15s">world</span></p><p begin="16s" end="18s">A line only<span ttm:role="x-translation">仅有逐行时间</span></p>"""
                )
            )
        assertEquals("Hello world", rows[0].text)
        assertEquals("Hello world", rows[0].words.joinToString("") { it.text })
        assertTrue(rows[1].words.isEmpty())
        assertEquals(16000L, rows[1].start)
        assertEquals("仅有逐行时间", rows[1].translation)
    }

    @Test
    fun backingVocalsRemainSeparateWithTheirOwnTranslationAndTiming() {
        val rows =
            AmllTtml.parse(
                document(
                    """<p begin="10s" end="15s"><span begin="10s" end="13s">Main</span><span ttm:role="x-translation" xml:lang="zh">主唱</span><span ttm:role="x-bg" begin="12s" end="15s"><span begin="12s" end="15s">Echo</span><span ttm:role="x-translation" xml:lang="zh">回声</span></span></p>"""
                )
            )
        assertEquals(2, rows.size)
        assertEquals("Main", rows[0].text)
        assertEquals("主唱", rows[0].translation)
        assertEquals("（Echo）", rows[1].text)
        assertEquals(12000L, rows[1].start)
        assertEquals("回声", rows[1].translation)
        assertEquals("（Echo）", rows[1].words.single().text)
    }

    @Test
    fun prefersSimplifiedChineseAndDoesNotShowEnglishAsChineseTranslation() {
        val rows =
            AmllTtml.parse(
                document(
                    """<p begin="10s" end="11s">光<span ttm:role="x-translation" xml:lang="en">light</span><span ttm:role="x-translation" xml:lang="zh-TW">微光</span><span ttm:role="x-translation" xml:lang="zh-Hans">光芒</span></p><p begin="11s" end="12s">風<span ttm:role="x-translation" xml:lang="en">wind</span></p>"""
                )
            )
        assertEquals("光芒", rows[0].translation)
        assertEquals("", rows[1].translation)
    }

    @Test
    fun rejectsUnsafeXmlAndInvalidTimes() {
        assertThrows(IllegalArgumentException::class.java) {
            AmllTtml.parse("<!DOCTYPE tt [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><tt>&x;</tt>")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AmllTtml.parse(document("""<p begin="15s" end="10s">bad</p>"""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AmllTtml.parse("<html>not lyrics</html>")
        }
    }
}
