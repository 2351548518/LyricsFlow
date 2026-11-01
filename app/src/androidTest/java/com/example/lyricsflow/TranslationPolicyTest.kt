package com.example.lyricsflow

import androidx.test.platform.app.InstrumentationRegistry
import com.example.lyricsflow.core.*
import java.net.URISyntaxException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TranslationPolicyTest {
    @Test
    fun legacyDailyCountersDoNotBlockWholeSongTranslation(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as LyricsFlowApp
        val graph = AppGraph(app)
        val previous = graph.state.value.prefs
        val previousSecret = graph.secrets.read()
        val settings = app.getSharedPreferences("settings", 0)
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
        val charsKey = "translation-chars:$day"
        val callsKey = "translation-calls:$day"
        val oldChars = settings.getInt(charsKey, 0).takeIf { settings.contains(charsKey) }
        val oldCalls = settings.getInt(callsKey, 0).takeIf { settings.contains(callsKey) }
        val longSong = (0 until 120).map { Line(it * 1000L, (it + 1) * 1000L, "A whole song lyric line $it ".repeat(6)) } +
            Line(120_000, 121_000, "A very long lyric line ".repeat(250)) +
            Line(121_000, 122_000, "Already translated", translation = "保留已有译文") +
            Line(122_000, 123_000, "这句本来就是中文")
        val pendingChars = longSong.filter { it.translation.isBlank() && needsChineseTranslation(it.text) }.sumOf { it.text.length }
        try {
            settings.edit().putInt(charsKey, 20000).putInt(callsKey, 20).commit()
            instrumentation.runOnMainSync {
                graph.preview()
                @Suppress("UNCHECKED_CAST")
                val state =
                    AppGraph::class
                        .java
                        .getDeclaredField("mutable")
                        .apply { isAccessible = true }
                        .get(graph) as MutableStateFlow<ScreenState>
                state.value =
                    state.value.copy(
                        lyrics = Lyrics(longSong, Provider.LOCAL)
                    )
                // A malformed authority reaches request validation after accounting, without
                // making any network connection or sending a real credential.
                graph.secrets.write("local-test-placeholder")
                graph.preferences(
                    previous.copy(
                        translationUrl = "https://",
                        model = "test",
                        consent = true,
                        autoTranslate = false,
                    )
                )
            }
            val error = runCatching { graph.translate() }.exceptionOrNull()
            assertTrue(
                "Expected local URI validation, not a daily quota rejection: $error",
                error is URISyntaxException,
            )
            assertEquals(21, settings.getInt(callsKey, 0))
            assertEquals("All missing lines, including the >4000-char line, must be in one request", 20000 + pendingChars, settings.getInt(charsKey, 0))
        } finally {
            settings
                .edit()
                .apply {
                    if (oldChars == null) remove(charsKey) else putInt(charsKey, oldChars)
                    if (oldCalls == null) remove(callsKey) else putInt(callsKey, oldCalls)
                }
                .commit()
            instrumentation.runOnMainSync {
                graph.secrets.write(previousSecret)
                graph.preferences(previous)
            }
            graph.scope.cancel()
        }
    }
}
