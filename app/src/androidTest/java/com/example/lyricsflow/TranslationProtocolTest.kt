package com.example.lyricsflow

import com.example.lyricsflow.core.Line
import com.example.lyricsflow.data.TranslationProtocol
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TranslationProtocolTest {
    private val lines = listOf(Line(0, 1000, "A new day"), Line(1000, 2000, "A quiet sky"))

    private fun response(content: String, finish: String = "stop") =
        JSONObject()
            .put(
                "choices",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("finish_reason", finish)
                            .put("message", JSONObject().put("content", content))
                    ),
            )

    @Test
    fun deepseekOptionsAreHostScopedAndBounded() {
        val body =
            TranslationProtocol.request(
                "https://api.deepseek.com",
                "deepseek-flash",
                listOf(0, 1),
                lines,
            )
        assertEquals("disabled", body.getJSONObject("thinking").getString("type"))
        assertEquals("json_object", body.getJSONObject("response_format").getString("type"))
        assertEquals(512, body.getInt("max_tokens"))
        val other =
            TranslationProtocol.request("https://example.com/v1", "user-model", listOf(0), lines)
        assertFalse(other.has("thinking"))
        assertFalse(other.has("response_format"))
    }

    @Test
    fun validatesLineIdsAndPreservesOriginalTimeline() {
        val translated =
            TranslationProtocol.decode(
                response("""{"lines":[{"id":1,"text":"宁静的天空"},{"id":0,"text":"新的一天"}]}"""),
                listOf(0, 1),
                lines,
                "test-model",
            )
        assertEquals("新的一天", translated[0].translation)
        assertEquals("宁静的天空", translated[1].translation)
        assertEquals(lines.map { it.start to it.end }, translated.map { it.start to it.end })
        assertEquals(lines.map { it.text }, translated.map { it.text })
        assertTrue(
            translated.all {
                it.translationKind == "machine" && it.translationModel == "test-model"
            }
        )
        for (invalid in
            listOf(
                """{"lines":[{"id":0,"text":"甲"}]}""",
                """{"lines":[{"id":0,"text":"甲"},{"id":0,"text":"乙"}]}""",
                """{"lines":[{"id":0.5,"text":"甲"},{"id":1,"text":"乙"}]}""",
                """{"lines":[{"id":0,"text":""},{"id":1,"text":"乙"}]}""",
            )) assertThrows(Exception::class.java) {
            TranslationProtocol.decode(response(invalid), listOf(0, 1), lines, "test")
        }
        assertTrue(lines.all { it.translation.isEmpty() })
    }

    @Test
    fun wholeSongRequestAndResponseHaveNoAppLineOrCharacterBatchCap() {
        val song = (0 until 120).map { Line(it * 1000L, (it + 1) * 1000L, "A long original lyric ".repeat(8)) } +
            Line(120_000, 121_000, "A single long lyric ".repeat(300))
        val ids = song.indices.toList()
        val request = TranslationProtocol.request("https://api.deepseek.com", "deepseek-flash", ids, song)
        val prompt = request.getJSONArray("messages").getJSONObject(0).getString("content")
        val sent = JSONArray(prompt.substringAfter('\n'))
        assertEquals(121, sent.length())
        assertEquals(song.last().text, sent.getJSONObject(120).getString("text"))
        assertTrue(request.getLong("max_tokens") > 8192)
        assertFalse(TranslationProtocol.request("https://example.com/v1", "user-model", ids, song).has("max_tokens"))
        val translations = JSONArray().apply {
            ids.reversed().forEach { put(JSONObject().put("id", it).put("text", if (it == 120) "长".repeat(10_001) else "译文 $it")) }
        }
        val decoded = TranslationProtocol.decode(response(JSONObject().put("lines", translations).toString()), ids, song, "test")
        assertEquals(121, decoded.size)
        assertEquals(10_001, decoded.last().translation.length)
        assertEquals(song.map { it.text to it.start }, decoded.map { it.text to it.start })
    }

    @Test
    fun rejectsTruncatedOrInterruptedCompletion() {
        for (finish in listOf("length", "content_filter", "aborted", "tool_calls")) assertThrows(
            IllegalArgumentException::class.java
        ) {
            TranslationProtocol.decode(response("{}", finish), listOf(0), lines, "test")
        }
    }
}
