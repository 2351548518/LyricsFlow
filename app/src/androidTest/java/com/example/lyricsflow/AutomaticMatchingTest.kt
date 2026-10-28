package com.example.lyricsflow

import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.lyricsflow.core.*
import com.example.lyricsflow.data.*
import java.util.UUID
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AutomaticMatchingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val graph = (instrumentation.targetContext.applicationContext as LyricsFlowApp).graph

    private fun report(value: String) =
        instrumentation.sendStatus(2, Bundle().apply { putString("matching", value) })

    private fun await(condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 40000
        while (!condition() && SystemClock.elapsedRealtime() < end) Thread.sleep(100)
        assertTrue("Matching state: ${graph.state.value.message}", condition())
    }

    @Test
    fun avidFindsTheLocalizedMultiArtistCredit(): Unit = runBlocking {
        val current = Track("Avid", "SawanoHiroyuki[nZk], mizuki", duration = 249000)
        val candidates =
            graph.api.searchExpanded(
                Provider.NETEASE,
                "${current.title} ${current.artist}",
                current,
            )
        val match = SongSearch.decide(current, candidates)
        assertNotNull("Avid: ${match.reason}", match.track)
        assertTrue(match.track!!.title.equals("Avid", true))
        assertTrue(match.track.artist.contains("mizuki", true))
        assertTrue(kotlin.math.abs(match.track.duration - current.duration) <= 2000)
        assertTrue(graph.api.lyrics(match.track).lines.any { it.start != null })
        report(
            "Avid candidates=${candidates.size} id=${match.track.sourceId} duration=${match.track.duration} reason=${match.reason}"
        )
    }

    @Test
    fun holonesKeepsTheTranslatedTitleAndArtistAlias(): Unit = runBlocking {
        val current = Track("ホロネス", "美波", "カワキヲアメク", 317000)
        val candidates =
            graph.api.searchExpanded(Provider.QQ, "${current.title} ${current.artist}", current)
        val match = SongSearch.decide(current, candidates)
        assertNotNull(match.track)
        assertEquals("004d2PCl0QeQvl", match.track!!.mid)
        assertTrue(graph.api.lyrics(match.track).lines.any { it.start != null })
        report(
            "Holones candidates=${candidates.size} mid=${match.track.mid} reason=${match.reason}"
        )
    }

    @Test
    fun durationMetadataArrivalRetriesAnUnresolvedSong() {
        val graph = AppGraph(this.graph.app)
        val previous = graph.state.value
        val preferences = graph.app.getSharedPreferences("settings", 0)
        val priority = preferences.getString("lyricPriority", null)
        val fixture =
            Track("Avid", "SawanoHiroyuki[nZk], mizuki", "QA pending album ${UUID.randomUUID()}")
        val key = fixture.key + ":NETEASE"
        try {
            instrumentation.runOnMainSync {
                graph.preferences(previous.prefs.copy(autoSources = false, autoTranslate = false))
                graph.live()
                preferences.edit().putString("lyricPriority", "NETEASE").commit()
                graph.onPlayback(Playback(fixture, "com.spotify.music"))
            }
            await { !graph.state.value.busy }
            assertNull(
                "Unknown duration and ambiguous albums should need more metadata",
                graph.state.value.lyrics,
            )
            instrumentation.runOnMainSync {
                graph.onPlayback(Playback(fixture.copy(duration = 249000), "com.spotify.music"))
            }
            await { !graph.state.value.busy && graph.state.value.lyrics != null }
            assertEquals(Provider.NETEASE, graph.state.value.source)
            assertTrue(graph.state.value.lyrics!!.lines.any { it.start != null })
            report("Duration 0 -> 249000 ms retried unchanged track key and resolved Avid")
            instrumentation.runOnMainSync {
                graph.preferences(graph.state.value.prefs.copy(autoSources = true))
                graph.onPlayback(Playback(fixture.copy(duration = 249000), "com.spotify.music"))
                graph.matchAutomatically()
            }
            await { !graph.state.value.busy && graph.state.value.lyrics != null }
            assertEquals(Provider.NETEASE, graph.state.value.source)
            assertTrue(graph.state.value.matchNotice.contains("已重新自动匹配到 网易云音乐"))
            report(
                "Preferred QQ rejected unknown mizuki alias; AppGraph automatically used NetEase and displayed fallback notice"
            )
        } finally {
            preferences
                .edit()
                .apply {
                    if (priority == null) remove("lyricPriority")
                    else putString("lyricPriority", priority)
                }
                .commit()
            instrumentation.runOnMainSync {
                graph.onPlayback(previous.playback)
                graph.preferences(previous.prefs)
                graph.live()
            }
            graph.store.remove("binding", key)
            graph.store.remove("lyrics", key)
            preferences.edit().remove("source:${fixture.key}").apply()
            graph.scope.cancel()
        }
    }

    @Test
    fun quickSongSwitchCannotPublishThePreviousSongLyrics() {
        val graph = AppGraph(this.graph.app)
        val previous = graph.state.value
        val preferences = graph.app.getSharedPreferences("settings", 0)
        val priority = preferences.getString("lyricPriority", null)
        val a = Track("QA first ${UUID.randomUUID()}")
        val b = Track("QA second ${UUID.randomUUID()}")
        for ((track, text) in listOf(a to "First", b to "Second")) {
            graph.store.put(
                "binding",
                track.key + ":QQ",
                trackJson(track.copy(provider = Provider.QQ, sourceId = "qa-${track.key}"))
                    .toString(),
            )
            graph.store.put(
                "lyrics",
                track.key + ":QQ",
                lyricsJson(
                        Lyrics(
                            listOf(Line(0, 10000, text)),
                            Provider.QQ,
                            providerRevision = QqLyricCodec.REVISION,
                        )
                    )
                    .toString(),
            )
        }
        try {
            instrumentation.runOnMainSync {
                graph.preferences(previous.prefs.copy(autoSources = false, autoTranslate = false))
                graph.live()
                preferences.edit().putString("lyricPriority", "QQ").commit()
                graph.onPlayback(Playback(a, "com.spotify.music"))
                graph.onPlayback(Playback(b, "com.spotify.music"))
                graph.onPlayback(Playback(b.copy(duration = 30000), "com.spotify.music"))
            }
            await { !graph.state.value.busy && graph.state.value.lyrics != null }
            assertEquals(b.key, graph.state.value.playback.track?.key)
            assertEquals(30000L, graph.state.value.playback.track?.duration)
            assertEquals("Second", graph.state.value.lyrics?.lines?.single()?.text)
            report("Quick switch published only the second track's cached lyrics")
        } finally {
            preferences
                .edit()
                .apply {
                    if (priority == null) remove("lyricPriority")
                    else putString("lyricPriority", priority)
                }
                .commit()
            instrumentation.runOnMainSync {
                graph.onPlayback(previous.playback)
                graph.preferences(previous.prefs)
                graph.live()
            }
            for (track in listOf(a, b)) for (kind in listOf("binding", "lyrics")) graph.store
                .remove(kind, track.key + ":QQ")
            graph.scope.cancel()
        }
    }
}
