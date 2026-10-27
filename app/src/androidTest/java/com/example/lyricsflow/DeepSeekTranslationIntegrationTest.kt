package com.example.lyricsflow

import android.os.Bundle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.example.lyricsflow.core.*
import com.example.lyricsflow.data.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Explicit opt-in only. The credential is delivered over stdin into app-private storage, never an
 * APK or runner argument.
 */
class DeepSeekTranslationIntegrationTest {
    @Test
    fun liveManualCacheAndAutomaticTranslation(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(
            "Paid translation requires explicit opt-in",
            InstrumentationRegistry.getArguments().getString("allowPaidTranslation") == "true",
        )
        val context = instrumentation.targetContext
        val configFile = File(context.filesDir, "translation-qa.json")
        assumeTrue("Missing private runtime configuration", configFile.isFile)
        val config =
            try {
                jsonObject(configFile.readText())
            } finally {
                configFile.delete()
            }
        val url = config.getString("baseUrl")
        require(java.net.URI(url).host == "api.deepseek.com")
        val model = config.getString("model")
        val key = config.getString("key")
        val graph = (context.applicationContext as LyricsFlowApp).graph
        val originalPreferences = graph.state.value.prefs
        val originalKey = graph.secrets.read()
        val token = UUID.randomUUID().toString()
        val manual = Track("DeepSeek manual QA $token", "LyricsFlow", duration = 18000)
        val automatic = Track("DeepSeek 自动翻译验证", "LyricsFlow · 自有测试文本 $token", duration = 6000)
        val settings = context.getSharedPreferences("settings", 0)
        val day =
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date())
        val callsBefore = settings.getInt("translation-calls:$day", 0)
        var scenario: ActivityScenario<MainActivity>? = null
        suspend fun cached(track: Track): Lyrics =
            withTimeout(10000) {
                while (true) {
                    val saved =
                        withContext(Dispatchers.IO) {
                            graph.store.get("lyrics", track.key + ":LOCAL")?.let {
                                readLyrics(jsonObject(it))
                            }
                        }
                    if (
                        saved != null &&
                            saved.lines.firstOrNull()?.translation?.isNotBlank() == true
                    )
                        return@withTimeout saved
                    delay(50)
                }
                @Suppress("UNREACHABLE_CODE") error("Unreachable")
            }
        try {
            graph.secrets.write(key)
            val sample =
                Lyrics(
                    listOf(
                        Line(0, 6000, "Leave a little room for the light"),
                        Line(6000, 12000, "小さな夢を風にあずけた"),
                        Line(12000, 18000, "这是无需重复翻译的中文"),
                    ),
                    Provider.LOCAL,
                )
            instrumentation.runOnMainSync {
                graph.preferences(
                    originalPreferences.copy(
                        translationUrl = url,
                        model = model,
                        consent = false,
                        autoTranslate = false,
                    )
                )
                graph.browseLocal(manual, sample)
            }
            assertTrue(runCatching { graph.translate() }.isFailure)
            assertEquals(callsBefore, settings.getInt("translation-calls:$day", 0))
            instrumentation.runOnMainSync {
                graph.preferences(graph.state.value.prefs.copy(consent = true))
            }
            val translated = graph.translate()
            assertTrue(
                translated.take(2).all { it.translation.any { c -> c in '\u3400'..'\u9fff' } }
            )
            assertEquals("", translated[2].translation)
            assertEquals(
                sample.lines.map { it.text to (it.start to it.end) },
                translated.map { it.text to (it.start to it.end) },
            )
            instrumentation.runOnMainSync {
                graph.applyTranslation(translated, sample.hash, manual.key)
            }
            assertEquals(translated, cached(manual).lines)
            assertEquals(translated, graph.translate())
            assertEquals(callsBefore + 1, settings.getInt("translation-calls:$day", 0))
            val autoSample =
                Lyrics(listOf(Line(0, 6000, "Let the morning find us slowly")), Provider.LOCAL)
            instrumentation.runOnMainSync {
                graph.browseLocal(automatic, autoSample)
                graph.preferences(graph.state.value.prefs.copy(autoTranslate = true))
            }
            val autoCached = cached(automatic)
            assertTrue(autoCached.lines.single().translation.isNotBlank())
            assertEquals("machine", autoCached.lines.single().translationKind)
            assertEquals(callsBefore + 2, settings.getInt("translation-calls:$day", 0))
            val backup = withContext(Dispatchers.IO) { Transfer.backup(graph) }
            var leaked = false
            java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(backup)).use { zip ->
                while (zip.nextEntry != null) {
                    val text = String(zip.readBytesLimited(20_000_000), Charsets.UTF_8)
                    if (text.contains(key)) leaked = true
                }
            }
            assertFalse("Credentials must not enter backup", leaked)
            scenario = ActivityScenario.launch(MainActivity::class.java)
            instrumentation.sendStatus(
                2,
                Bundle().apply {
                    putString(
                        "stream",
                        "DeepSeek QA: manual=2 translated, Chinese skipped=1, automatic=1 translated, requests=2, cache reused, key excluded from backup\n",
                    )
                },
            )
            if (InstrumentationRegistry.getArguments().getString("captureWindow") == "true")
                delay(20000)
        } finally {
            instrumentation.runOnMainSync { graph.preferences(originalPreferences) }
            graph.secrets.write(originalKey)
            withContext(Dispatchers.IO) {
                graph.store.remove("lyrics", manual.key + ":LOCAL")
                graph.store.remove("lyrics", automatic.key + ":LOCAL")
            }
            scenario?.close()
            configFile.delete()
            assertTrue("Original credential restored", graph.secrets.read() == originalKey)
        }
    }
}
