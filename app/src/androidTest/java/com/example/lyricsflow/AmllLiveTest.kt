package com.example.lyricsflow

import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.example.lyricsflow.core.*
import com.example.lyricsflow.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class AmllLiveTest {
    @Test
    fun officialRepositorySearchIdsAndWordLyrics() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val api = MusicApi(Store(instrumentation.targetContext))
        val start = SystemClock.elapsedRealtime()
        val current =
            Track("Avid", "SawanoHiroyuki[nZk], mizuki", "Avid / Hands Up to the Sky", 249000)
        val results =
            withTimeout(25000) {
                api.searchExpanded(Provider.AMLL, "${current.title} ${current.artist}", current)
            }
        val matched = SongSearch.automatic(current, results)
        assertNotNull("AMLL metadata aliases should match Avid", matched)
        val lyrics = withTimeout(15000) { api.lyrics(matched!!) }
        assertTrue(lyrics.lines.any { it.words.isNotEmpty() })
        assertTrue(lyrics.lines.any { it.translation.isNotBlank() })
        assertTrue(lyrics.lines.any { it.romanization.isNotBlank() })
        assertTrue(lyrics.lines.all { row -> row.words.all { it.end >= it.start } })
        assertEquals(Provider.AMLL, lyrics.source)
        val sunny = api.resolveAmll("186016")
        assertEquals("晴天", sunny.title)
        instrumentation.sendStatus(
            0,
            Bundle().apply {
                putString(
                    "amll_result",
                    "ms=${SystemClock.elapsedRealtime()-start}; Avid=${matched!!.sourceId}; rows=${lyrics.lines.size}; words=${lyrics.lines.sumOf {it.words.size}}; translation=true; roman=true; Sunny=${sunny.sourceId}",
                )
            },
        )
    }
}
