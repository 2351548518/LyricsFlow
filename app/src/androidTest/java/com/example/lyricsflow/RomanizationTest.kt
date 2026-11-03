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
        for (bad in listOf("""{"lines":[]}""", """{"lines":[{"id":0,"text":"君の声"}]}""", """{"lines":[{"id":0,"text":"kimi"},{"id":0,"text":"koe"}]}"""))
            assertThrows(Exception::class.java) { TranslationProtocol.decode(response(bad), listOf(0), lines, "model", true) }
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
            val romanized = original.lines.map { it.copy(romanization = "kimi no koe") }
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
            val l = Lyrics(listOf(Line(0, 60000, "君の声", "你的声音", "kimi no koe")), Provider.QQ, providerRevision = 99)
            f.graph.store.put("lyrics", "${t.key}:QQ", lyricsJson(l).toString())
            f.backend.fetch = { _, _ -> l.copy(lines = l.lines.map { it.copy(translation = "", romanization = "") }) }
            val result = SongLyrics(f.graph.store, f.backend).lookup(t, Provider.QQ, true, true, record).resolved!!
            assertEquals("kimi no koe", result.lyrics.lines.single().romanization)
            assertEquals("你的声音", result.lyrics.lines.single().translation)
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
}
