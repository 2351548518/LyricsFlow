package com.example.lyricsflow

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.lyricsflow.core.*
import com.example.lyricsflow.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImportSecurityTest {
    @Test
    fun schemaUnknownFieldsRangesAndNativeMotion() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        StyleSchema.load(
            context.assets.open("lyricsflow.style.schema.json").bufferedReader().use {
                it.readText()
            }
        )
        val raw = JSONObject(StyleCodec.preset()).put("script", "alert(1)").toString()
        assertThrows(Exception::class.java) { StyleCodec.parse(raw) }
        assertThrows(Exception::class.java) {
            StyleCodec.parse(
                JSONObject(StyleCodec.preset())
                    .put(
                        "text",
                        JSONObject().put("original", JSONObject().put("textSizeSp", 100000)),
                    )
                    .toString()
            )
        }
        assertThrows(Exception::class.java) { jsonObject("[".repeat(1000) + "]".repeat(1000)) }
        val source =
            JSONObject(StyleCodec.preset())
                .put(
                    "animation",
                    JSONObject(
                        """{"easing":"linear","enter":{"property":"opacity","keyframes":[{"atMs":0,"value":0},{"atMs":1000,"value":1}]}}"""
                    ),
                )
                .toString()
        StyleCodec.parse(source)
        assertEquals(.5f, ThemeSpec.from(source).motion(500, 2000).opacity, .001f)
    }

    @Test
    fun androidXmlParserAndLosslessLyricRoundtrip() {
        val raw =
            """<tt xmlns="http://www.w3.org/ns/ttml"><body><div><p begin="1s" end="4s"><span begin="1s" end="2s">A </span><span begin="2s" end="4s">day</span></p></div></body></tt>"""
        val lines = LyricParser.parse(raw)
        assertEquals("A day", lines.single().text)
        val lyrics = Lyrics(lines, Provider.LOCAL)
        val exported = Transfer.lyricPackage(Track("Test", duration = 5000), lyrics, 500)
        val result = Transfer.importLyrics(exported, 5000)
        assertEquals(lyrics.lines, result.second.lines)
        assertThrows(Exception::class.java) {
            LyricParser.parse("<!DOCTYPE tt SYSTEM 'https://example.com/evil'><tt/>")
        }
    }
}
