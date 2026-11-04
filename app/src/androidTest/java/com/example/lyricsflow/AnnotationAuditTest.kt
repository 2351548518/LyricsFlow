package com.example.lyricsflow

import com.example.lyricsflow.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class AnnotationAuditTest {
    @Test fun onlyRelevantAnnotationInputsRestartOrCancelRequests() = runBlocking {
        val lyrics = Lyrics(listOf(Line(0,60000,"君の声")), Provider.LOCAL)
        val initial = ScreenState(playback = Playback(Track("Fixture")), lyrics = lyrics,
            prefs = Preferences(consent = true, autoTranslate = true, romanization = true,
                translationUrl = "https://example.invalid", model = "model-a"))
        val state = MutableStateFlow(initial)
        var requests = 0
        var cancelled = 0
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            state.map { it.annotationRequestKey(false) }.distinctUntilChanged().collectLatest {
                requests++
                try { awaitCancellation() } finally { cancelled++ }
            }
        }
        suspend fun settle() { delay(30) }
        try {
            settle()
            val before = requests
            state.value = initial.copy(lyrics = lyrics.copy(lines = lyrics.lines.map { it.copy(romanization = "kimi no koe") }))
            settle()
            assertEquals("Romanization must not retry a failed translation", before, requests)
            state.value = state.value.copy(prefs = initial.prefs.copy(fontSize = 40f))
            settle(); assertEquals(before, requests)
            state.value = state.value.copy(prefs = initial.prefs.copy(model = "model-b"))
            settle(); assertEquals(before + 1, requests); assertEquals(1, cancelled)
            state.value = state.value.copy(prefs = initial.prefs.copy(consent = false))
            settle(); assertEquals(before + 2, requests)
            assertFalse(state.value.annotationRequestKey(false).enabled)
            assertEquals(initial.annotationRequestKey(true), initial.copy(lyrics = lyrics.copy(lines =
                lyrics.lines.map { it.copy(translation = "你的声音") })).annotationRequestKey(true))
            assertTrue(lyrics.needsTranslation); assertTrue(lyrics.needsWordAlignment)
        } finally { job.cancelAndJoin() }
    }

    @Test fun waitingRequestUsesLatestAnnotationsAndStaleResultsCannotReplaceThem() = runBlocking {
        val f = ChoiceFixture()
        try {
            f.main {
                f.graph.preferences(f.original.copy(consent = true, autoTranslate = false, romanization = false))
                f.graph.browseLocal(f.song(), Lyrics(listOf(Line(0,60000,"君の声")), Provider.LOCAL))
            }
            val captured = f.graph.state.value
            val completed = captured.lyrics!!.lines.map { it.copy(translation = "新的来源译文", translationKind = "provider") }
            f.main { assertTrue(f.graph.applyTranslation(completed, captured.lyrics!!.hash,
                captured.playback.track!!.key, Provider.LOCAL)) }
            // A stale snapshot had missing text. No URL/key exists: using it would attempt a paid request or fail.
            val rows = withContext(Dispatchers.Main) { f.graph.translate(captured) }
            assertEquals("新的来源译文", rows.single().translation)
            f.main { f.graph.applyTranslation(completed.map { it.copy(translation = "过期结果", translationKind = "machine") },
                captured.lyrics!!.hash, captured.playback.track!!.key, Provider.LOCAL) }
            assertEquals("新的来源译文", f.graph.state.value.lyrics!!.lines.single().translation)
            val old = f.graph.state.value
            f.main { f.graph.preferences(old.prefs.copy(model = "changed-model")) }
            try { withContext(Dispatchers.Main) { f.graph.translate(old) }; fail("Old configuration accepted") }
            catch (_: CancellationException) {}
        } finally { f.clean() }
    }
}
