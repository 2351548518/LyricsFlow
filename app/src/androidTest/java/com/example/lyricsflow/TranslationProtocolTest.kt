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
    fun rejectsTruncatedOrInterruptedCompletion() {
        for (finish in listOf("length", "content_filter", "aborted", "tool_calls")) assertThrows(
            IllegalArgumentException::class.java
        ) {
            TranslationProtocol.decode(response("{}", finish), listOf(0), lines, "test")
        }
    }
}
