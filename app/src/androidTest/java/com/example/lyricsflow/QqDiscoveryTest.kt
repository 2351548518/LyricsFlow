package com.example.lyricsflow

import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.lyricsflow.core.*
import com.example.lyricsflow.data.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QqDiscoveryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val graph = (instrumentation.targetContext.applicationContext as LyricsFlowApp).graph

    private fun report(message: String) =
        instrumentation.sendStatus(2, Bundle().apply { putString("discovery", message) })

    @Test
    fun qqTranslationHasAlignedNativeTimestamps(): Unit = runBlocking {
        val track = graph.api.resolveQQ("001CNiOJ3VyAHu")
        val lyrics = graph.api.lyrics(track, refresh = true)
        assertEquals(
            "Must exercise the new service, not just legacy fallback",
            QqLyricCodec.REVISION,
            lyrics.providerRevision,
        )
        assertTrue(lyrics.lines.count { it.translation.isNotBlank() } >= 15)
        assertTrue(
            lyrics.lines
                .filter { it.translation.isNotBlank() }
                .all { it.start != null && it.translationKind == "provider" }
        )
        assertFalse(
            lyrics.lines.any { it.text.contains('\uFFFD') || it.translation.contains('\uFFFD') }
        )
        report(
            "QQ modern lyrics=${lyrics.lines.size} translated=${lyrics.lines.count { it.translation.isNotBlank() }} revision=${lyrics.providerRevision}"
        )
    }

    @Test
    fun qqSearchHasFullResultsPaginationAndAliasMatching(): Unit = runBlocking {
        val first = graph.api.searchPage(Provider.QQ, "rionos")
        assertEquals("qq-full", first.channel)
        assertTrue(first.tracks.size >= 15)
        assertTrue(first.more)
        val next = graph.api.searchPage(Provider.QQ, "rionos", page = 1, channel = first.channel)
        val known = first.tracks.map { it.sourceId }.toSet()
        assertTrue(
            "Second page must contain new provider IDs",
            next.tracks.any { it.sourceId !in known },
        )
        val current = Track("ウィアートル (旅人)", "rionos", duration = 286000)
        val expanded =
            graph.api.searchExpanded(Provider.QQ, "${current.title} ${current.artist}", current)
        assertTrue(expanded.any { it.mid == "001CNiOJ3VyAHu" })
        assertNotNull(
            "Alias difference must not prevent reliable automatic matching",
            SongSearch.automatic(current, expanded),
        )
        report(
            "QQ first=${first.tracks.size} next=${next.tracks.size} total=${first.total} newIds=${next.tracks.count { it.sourceId !in known }} aliasCandidates=${expanded.size}"
        )
    }

    @Test
    fun neteaseSearchAlsoPaginates(): Unit = runBlocking {
        val first = graph.api.searchPage(Provider.NETEASE, "周杰伦")
        assertTrue(first.tracks.size >= 15)
        assertTrue(first.more)
        val next = graph.api.searchPage(Provider.NETEASE, "周杰伦", page = 1, channel = first.channel)
        assertTrue(next.tracks.any { t -> first.tracks.none { it.sourceId == t.sourceId } })
        report("NetEase first=${first.tracks.size} next=${next.tracks.size} total=${first.total}")
    }

    @Test
    fun codecHandlesBase64EntitiesMissingAndEncryptedFields() {
        fun b64(text: String) = Base64.encodeToString(text.toByteArray(), Base64.NO_WRAP)
        val data =
            JSONObject()
                .put("lyric", b64("[00:01.00]A &amp; B\n[00:03.00]C"))
                .put("trans", b64("[00:01.00]甲与乙"))
        val lyrics = QqLyricCodec.lyrics(data, 5000)
        assertEquals("A & B", lyrics.lines.first().text)
        assertEquals("甲与乙", lyrics.lines.first().translation)
        assertEquals("", lyrics.lines.last().translation)
        assertEquals(1000L, lyrics.lines.first().start)
        assertEquals(QqLyricCodec.REVISION, readLyrics(lyricsJson(lyrics)).providerRevision)
        assertEquals(
            0,
            readLyrics(lyricsJson(lyrics).apply { remove("providerRevision") }).providerRevision,
        )
        assertEquals("", QqLyricCodec.decodeField("ABCDEF1234567890ABCDEF1234567890"))
        assertEquals(
            "",
            QqLyricCodec.decodeField(
                Base64.encodeToString(byteArrayOf(-1, -2, 0, 1), Base64.NO_WRAP)
            ),
        )
        assertEquals("", QqLyricCodec.decodeField("null"))
    }

    @Test
    fun staleQqCacheUpgradesAndPreservesExistingTranslation(): Unit = runBlocking {
        val providerTrack = graph.api.resolveQQ("0039MnYb0qxYhV")
        val fresh = graph.api.lyrics(providerTrack)
        val index = fresh.lines.indexOfFirst { it.translation.isBlank() }
        assertTrue(index >= 0)
        val old =
            fresh.copy(
                providerRevision = 0,
                lines =
                    fresh.lines.mapIndexed { i, row ->
                        if (i == index)
                            row.copy(
                                translation = "缓存译文测试",
                                translationKind = "machine",
                                translationModel = "fixture",
                            )
                        else row
                    },
            )
        val fixture = providerTrack.copy(title = "Cache migration QA ${UUID.randomUUID()}")
        val key = fixture.key + ":QQ"
        val previous = graph.state.value
        graph.store.put("binding", key, trackJson(providerTrack).toString())
        graph.store.put("lyrics", key, lyricsJson(old).toString())
        try {
            instrumentation.runOnMainSync {
                graph.preferences(previous.prefs.copy(autoTranslate = false))
                graph.live()
                graph.onPlayback(Playback(fixture, "com.spotify.music"))
            }
            val end = SystemClock.elapsedRealtime() + 60000
            while (graph.state.value.busy && SystemClock.elapsedRealtime() < end) Thread.sleep(100)
            val current = graph.state.value
            assertFalse(current.busy)
            assertEquals(
                "Cache must be upgraded in the normal automatic path: ${current.message}",
                QqLyricCodec.REVISION,
                current.lyrics?.providerRevision,
            )
            assertEquals("缓存译文测试", current.lyrics?.lines?.get(index)?.translation)
            assertEquals(
                QqLyricCodec.REVISION,
                readLyrics(JSONObject(graph.store.get("lyrics", key)!!)).providerRevision,
            )
            report("QQ legacy cache upgraded; existing machine translation preserved")
        } finally {
            instrumentation.runOnMainSync {
                graph.onPlayback(previous.playback)
                graph.preferences(previous.prefs)
                graph.live()
            }
            graph.store.remove("binding", key)
            graph.store.remove("lyrics", key)
        }
    }
}
