package com.example.lyricsflow

import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.example.lyricsflow.core.*
import com.example.lyricsflow.data.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Optional network probe, no real playback controls, binding writes or translation calls. */
class LrcLibLiveTest {
    @Test
    fun dreamLanternPrefersJapaneseAndReusesTheSearchPayload() = runBlocking {
        val api = MusicApi(Store(InstrumentationRegistry.getInstrumentation().targetContext))
        val current = Track("Dream lantern", "RADWIMPS", "Your Name.", 131000)
        val start = SystemClock.elapsedRealtime()
        val candidates =
            withTimeout(25000) {
                api.searchExpanded(Provider.LRCLIB, "${current.title} ${current.artist}", current)
            }
        val decision = SongSearch.decide(current, candidates)
        InstrumentationRegistry.getInstrumentation()
            .sendStatus(
                0,
                Bundle().apply {
                    putString(
                        "lrclib_search",
                        "ms=${SystemClock.elapsedRealtime()-start}; rows=${candidates.size}; match=${decision.track?.sourceId}; reason=${decision.reason}",
                    )
                },
            )
        assertNotNull("Expected a matching native record: ${decision.reason}", decision.track)
        val lyricsStart = SystemClock.elapsedRealtime()
        val lyrics = api.lyrics(decision.track!!)
        assertTrue(lyrics.lines.any { it.text.any { c -> c in '\u3040'..'\u30ff' } })
        assertTrue(lyrics.lines.any { it.start != null })
        assertEquals(LrcLibClient.REVISION, lyrics.providerRevision)
        InstrumentationRegistry.getInstrumentation()
            .sendStatus(
                0,
                Bundle().apply {
                    putString(
                        "lrclib_lyrics",
                        "ms=${SystemClock.elapsedRealtime()-lyricsStart}; rows=${lyrics.lines.size}; native=true; synced=true",
                    )
                },
            )
    }
}
