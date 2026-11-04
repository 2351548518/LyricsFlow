package com.example.lyricsflow

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.File
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.lyricsflow.core.*
import com.example.lyricsflow.data.*
import com.example.lyricsflow.ui.FlowApp
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RomanizationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun response(text: String) = JSONObject("""{"choices":[{"finish_reason":"stop","message":{"content":${JSONObject.quote(text)}}}]}""")

    @Test fun actualModelSamplePassesProductionDecoder() {
        val sample = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets
            .open("alignment-model-sample.json").bufferedReader().use { JSONObject(it.readText()) }
        val input = sample.getJSONArray("input")
        val lines = (0 until input.length()).map { Line(0, 60000, input.getJSONObject(it).getString("text")) }
        val rows = TranslationProtocol.decode(response(sample.getJSONObject("response").toString()),
            lines.indices.toList(), lines, "deepseek-flash", true)
        assertEquals(6, rows.size)
        rows.forEachIndexed { i, row ->
            assertEquals(lines[i].text, row.text)
            assertTrue(validAlignment(row.text, row.alignment))
            assertFalse(needsRomanization(row.romanization))
            assertFalse(row.needsAlignment())
        }
    }

    @Test fun protocolProducesPronunciationAndRejectsMissingDuplicateOrNativeOutput() {
        val lines = listOf(Line(100, 1000, "君の声", "你的声音"), Line(1000, 2000, "ありがとう", romanization = "arigatou"))
        val body = TranslationProtocol.request("https://example.com/v1", "model", listOf(0), lines, true)
        val prompt = body.getJSONArray("messages").getJSONObject(0).getString("content")
        assertTrue(prompt.startsWith("Romanize"))
        assertFalse(prompt.substringAfter('\n').contains("ありがとう"))
        val rows = TranslationProtocol.decode(response("""{"lines":[{"id":0,"text":"kimi no koe"}]}"""), listOf(0), lines, "model", true)
        assertEquals("kimi no koe", rows[0].romanization)
        assertEquals("你的声音", rows[0].translation)
        assertEquals(lines[1], rows[1])
        assertEquals(lines.map { it.text to it.start }, rows.map { it.text to it.start })
        for (bad in listOf("""{"lines":[]}""", """{"lines":[{"id":0,"text":"kimi"},{"id":0,"text":"koe"}]}"""))
            assertThrows(Exception::class.java) { TranslationProtocol.decode(response(bad), listOf(0), lines, "model", true) }
        val native = TranslationProtocol.decode(response("""{"lines":[{"id":0,"text":"君の声"}]}"""), listOf(0), lines, "model", true)
        assertEquals(lines, native)
        assertTrue(needsRomanization("君の声")); assertTrue(needsRomanization("안녕"))
        assertFalse(needsRomanization("Hello café"))
    }

    @Test fun lateTranslationAndRomanizationMergePersistAndTravelInBackup() {
        val f = ChoiceFixture()
        val t = f.song()
        val original = Lyrics(listOf(Line(0, 60000, "君の声")), Provider.LOCAL)
        try {
            f.main { f.graph.preferences(f.original.copy(romanization = false, autoTranslate = false)); f.graph.browseLocal(t, original); f.graph.saveCurrent() }
            f.waitFor { f.graph.store.get("saved", t.key) != null }
            val translated = original.lines.map { it.copy(translation = "你的声音", translationKind = "machine", translationModel = "fixture") }
            val parts = listOf(LyricAlignment("君の", "kimi no", "你的"), LyricAlignment("声", "koe", "声音"))
            val romanized = original.lines.map { it.copy(romanization = "kimi no koe", alignment = parts) }
            f.main {
                assertTrue(f.graph.applyTranslation(romanized, original.hash, t.key, Provider.LOCAL, true))
                assertTrue(f.graph.applyTranslation(translated, original.hash, t.key, Provider.LOCAL))
            }
            f.waitFor {
                val raw = f.graph.store.get("saved", t.key) ?: return@waitFor false
                val row = readLyrics(JSONObject(raw).getJSONObject("lyrics")).lines.single()
                row.romanization == "kimi no koe" && row.translation == "你的声音"
            }
            val backup = Transfer.validateBackup(Transfer.backup(f.graph))
            val cached = backup.first.first { it.first == "lyrics" && it.second == "${t.key}:LOCAL" }
            val line = readLyrics(JSONObject(cached.third)).lines.single()
            assertEquals("kimi no koe", line.romanization); assertEquals("你的声音", line.translation)
            assertEquals(parts, line.alignment)
            val packaged = Transfer.importLyrics(Transfer.lyricPackage(t, f.graph.state.value.lyrics!!, 0), 60000).second
            assertEquals(line, packaged.lines.single())
            f.main { f.graph.browseLocal(t.copy(title = "Other"), original) }
            f.main { assertFalse(f.graph.applyTranslation(romanized, original.hash, t.key, Provider.LOCAL, true)) }
        } finally { f.graph.store.remove("saved", t.key); f.clean() }
    }

    @Test fun refreshKeepsGeneratedRomanizationForTheSameRecord() = runBlocking {
        val f = ChoiceFixture();val t = f.song()
        try {
            val record = f.seed(t, Provider.QQ)
            val parts = listOf(LyricAlignment("君の", "kimi no", "你的"), LyricAlignment("声", "koe", "声音"))
            val l = Lyrics(listOf(Line(0, 60000, "君の声", "你的声音", "kimi no koe", alignment = parts)), Provider.QQ, providerRevision = 99)
            f.graph.store.put("lyrics", "${t.key}:QQ", lyricsJson(l).toString())
            f.backend.fetch = { _, _ -> l.copy(lines = l.lines.map { it.copy(translation = "", romanization = "", alignment = emptyList()) }) }
            val result = SongLyrics(f.graph.store, f.backend).lookup(t, Provider.QQ, true, true, record).resolved!!
            assertEquals("kimi no koe", result.lyrics.lines.single().romanization)
            assertEquals("你的声音", result.lyrics.lines.single().translation)
            assertEquals(parts, result.lyrics.lines.single().alignment)
        } finally { f.clean() }
    }

    @Test fun toolbarShowsRomanizationImmediatelyAfterTranslation() {
        val f = ChoiceFixture();val t = f.song()
        try {
            f.main { f.graph.preferences(f.original.copy(romanization = false, autoTranslate = false)); f.graph.browseLocal(t, Lyrics(listOf(Line(0, 60000, "君の声", "你的声音", "kimi no koe")), Provider.LOCAL)) }
            compose.activityRule.scenario.onActivity { it.setContent { FlowApp(f.graph) } }
            val translation = compose.onNodeWithContentDescription("显示或隐藏译文").getUnclippedBoundsInRoot()
            val roman = compose.onNodeWithTag("romanization-toggle").getUnclippedBoundsInRoot()
            assertTrue(roman.left >= translation.right)
            compose.onNodeWithTag("romanization-toggle").performClick()
            compose.onNodeWithText("kimi no koe").assertIsDisplayed()
            compose.onNodeWithContentDescription("歌曲评论 · 共鸣").assertHasClickAction()
            val screenshot = File(compose.activity.getExternalFilesDir(null), "romanization-toolbar.png")
            screenshot.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { f.clean() }
    }

    @Test fun pronunciationSitsAboveOriginalAndSharesItsEdgesAcrossReaderModes() {
        val f = ChoiceFixture()
        val original = "君の声を聞きながら遠くへ歩いていこう"
        val pronunciation = "kimi no koe o kikinagara tooku e aruite ikou"
        val translated = "听着你的声音，向远方走去"
        try {
            f.main {
                f.graph.preferences(f.original.copy(romanization = true, translation = true,
                    autoTranslate = false, consent = false, free = false, reduced = true))
                f.graph.browseLocal(f.song(), Lyrics(listOf(Line(0, 60000, original,
                    translated, pronunciation)), Provider.LOCAL))
            }
            compose.activityRule.scenario.onActivity { it.setContent { FlowApp(f.graph) } }
            fun verifyOrder() {
                val roman = compose.onNodeWithText(pronunciation).getUnclippedBoundsInRoot()
                val text = compose.onNodeWithText(original).getUnclippedBoundsInRoot()
                val translation = compose.onNodeWithText(translated).getUnclippedBoundsInRoot()
                assertTrue("Pronunciation must precede original", roman.bottom <= text.top)
                assertTrue("Translation must remain below original", text.bottom <= translation.top)
                assertEquals(text.left.value, roman.left.value, .5f)
                assertEquals(text.right.value, roman.right.value, .5f)
            }
            verifyOrder()
            compose.onNodeWithTag("lyric-viewport").performClick()
            compose.onNodeWithTag("fullscreen-lyrics").assertIsDisplayed()
            verifyOrder()
            compose.onNodeWithTag("lyric-viewport").performClick()
            f.main { f.graph.preferences(f.graph.state.value.prefs.copy(free = true)) }
            verifyOrder()
            compose.onNodeWithTag("romanization-toggle").performClick()
            compose.onNodeWithText(pronunciation).assertDoesNotExist()
            compose.onNodeWithText(original).assertExists()
            compose.onNodeWithText(translated).assertExists()
        } finally { f.clean() }
    }

    @Test fun alignmentProtocolRejectsChangedOriginalAndInvalidReadings() {
        val lines = listOf(Line(0, 60000, "君の声", "你的声音", "kimi no koe"))
        fun decode(segments: String) = TranslationProtocol.decode(response(
            """{"lines":[{"id":0,"text":"kimi no koe","segments":$segments}]}"""), listOf(0), lines, "fixture", true).single()
        val valid = """[{"original":"君の","romanization":"kimi no","translation":"你的"},{"original":"声","romanization":"koe","translation":"声音"}]"""
        val line = decode(valid)
        assertTrue(validAlignment(line.text, line.alignment))
        assertFalse(line.needsAlignment())
        assertTrue(lines[0].needsAlignment())
        assertEquals(lines[0].translation, line.translation)
        val ranges = """[{"start":0,"end":2,"romanization":"kimi no","translation":"你的"},{"start":2,"end":3,"romanization":"koe","translation":"声音"}]"""
        assertEquals(line.alignment, decode(ranges).alignment)
        val repaired = TranslationProtocol.decode(response("""{"lines":[{"id":0,"text":"君の声","segments":$ranges}]}"""),
            listOf(0), listOf(lines[0].copy(romanization = "")), "fixture", true).single()
        assertEquals("kimi no koe", repaired.romanization)
        assertEquals(line.alignment, repaired.alignment)
        assertTrue(decode(ranges.replace("\"start\":2", "\"start\":1")).alignment.isEmpty())
        assertTrue(decode(ranges.replace("\"end\":3", "\"end\":4")).alignment.isEmpty())
        val mixed = TranslationProtocol.decode(response("""{"lines":[
            {"id":0,"text":"kimi no koe","segments":$ranges},
            {"id":1,"text":"kimi no koe","segments":${ranges.replace("\"start\":2", "\"start\":1")}}]}"""),
            listOf(0,1), listOf(lines[0],lines[0]), "fixture", true)
        assertEquals(line.alignment, mixed[0].alignment)
        assertTrue(mixed[1].alignment.isEmpty())
        for (bad in listOf(valid.replace("君の", "君"), valid.replace("kimi no", "君の"), valid.replace("声音", ""), "[]")) {
            val fallback = decode(bad)
            assertTrue(fallback.alignment.isEmpty())
            assertEquals(lines[0], fallback)
        }
        val json = lyricsJson(Lyrics(listOf(line), Provider.LOCAL))
        assertEquals(line, readLyrics(json).lines.single())
        json.getJSONArray("lines").getJSONObject(0).getJSONArray("alignment").getJSONObject(0).put("text", "別")
        assertThrows(Exception::class.java) { readLyrics(json) }
    }

    @Test fun threeWayUnitsWrapTogetherAndRespectBothToggles() {
        val f = ChoiceFixture()
        val parts = listOf(LyricAlignment("君の", "kimi no", "你的"), LyricAlignment("声", "koe", "声音"),
            LyricAlignment("を聞きながら遠くへ", "o kikinagara tooku e", "听着走向远方"))
        val row = Line(0, 60000, parts.joinToString("") { it.text }, "听着你的声音走向远方",
            "kimi no koe o kikinagara tooku e", alignment = parts)
        try {
            f.main {
                f.graph.preferences(f.original.copy(romanization = true, translation = true, consent = false,
                    autoTranslate = false, free = false, reduced = true, fontSize = 30f))
                f.graph.browseLocal(f.song(), Lyrics(listOf(row), Provider.LOCAL))
            }
            compose.activityRule.scenario.onActivity { it.setContent { FlowApp(f.graph) } }
            fun verify() {
                compose.onNodeWithTag("aligned-lyric").assertExists()
                parts.forEach { part ->
                    val top = compose.onNodeWithText(part.romanization).getUnclippedBoundsInRoot()
                    val middle = compose.onNodeWithText(part.text).getUnclippedBoundsInRoot()
                    val bottom = compose.onNodeWithText(part.translation).getUnclippedBoundsInRoot()
                    assertTrue(top.bottom <= middle.top && middle.bottom <= bottom.top)
                    assertEquals(top.left.value, middle.left.value, .5f)
                    assertEquals(middle.right.value, bottom.right.value, .5f)
                }
            }
            verify()
            compose.onNodeWithTag("lyric-viewport").performClick()
            verify()
            val screenshot = File(compose.activity.getExternalFilesDir(null), "alignment-reader.png")
            screenshot.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
            compose.onNodeWithTag("lyric-viewport").performClick()
            compose.onNodeWithContentDescription("显示或隐藏译文").performClick()
            parts.forEach { compose.onNodeWithText(it.translation).assertDoesNotExist() }
            compose.onNodeWithText(parts[0].romanization).assertExists()
            compose.onNodeWithTag("romanization-toggle").performClick()
            compose.onNodeWithTag("aligned-lyric").assertDoesNotExist()
            compose.onNodeWithText(row.text).assertExists()
        } finally { f.clean() }
    }
}
