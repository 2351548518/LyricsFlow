package com.example.lyricsflow

import android.app.Application
import androidx.test.platform.app.InstrumentationRegistry
import com.example.lyricsflow.core.*
import com.example.lyricsflow.data.*
import java.net.URLEncoder
import java.util.UUID
import kotlinx.coroutines.cancel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LrcOriginalCacheTest {
    @Test
    fun revisionTwoRomajiBindingIsReselectedWithoutAssigningItsTranslationToJapanese() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val graph = AppGraph(instrumentation.targetContext.applicationContext as Application)
        val previous = graph.state.value.prefs
        val current =
            Track("QA Sparkle ${UUID.randomUUID()} - movie ver.", "RADWIMPS", "Exact album", 537000)
        val roman = current.copy(provider = Provider.LRCLIB, sourceId = "900000000000000001")
        val native =
            roman.copy(
                title = current.title.replace("- movie ver.", "(Movie Version)"),
                sourceId = "900000000000000002",
                duration = 534000,
                album = "Other release",
            )
        val key = current.key + ":LRCLIB"
        val path =
            "/api/search?q=" +
                URLEncoder.encode("${SongSearch.discoveryTitle(current.title)} RADWIMPS", "UTF-8")
                    .replace("+", "%20")
        val cacheKeys =
            listOf(path, "/api/get/${roman.sourceId}", "/api/get/${native.sourceId}").map {
                digest("https://lrclib.net$it")
            }
        val prefs = graph.app.getSharedPreferences("settings", 0)
        try {
            val old =
                Lyrics(
                    listOf(
                        Line(0, 31400, "Opening", "已翻译的开场", translationKind = "machine"),
                        Line(
                            31400,
                            537000,
                            "Bokutachi wa hikari no mukou e",
                            "旧罗马音译文",
                            translationKind = "machine",
                        ),
                    ),
                    Provider.LRCLIB,
                    providerRevision = 2,
                )
            fun record(t: Track, text: String) =
                JSONObject()
                    .put("id", t.sourceId)
                    .put("trackName", t.title)
                    .put("artistName", t.artist)
                    .put("albumName", t.album)
                    .put("duration", t.duration / 1000.0)
                    .put("syncedLyrics", text)
                    .put("plainLyrics", JSONObject.NULL)
            val payload =
                JSONObject()
                    .put(
                        "items",
                        JSONArray()
                            .put(record(roman, "[00:31.40]Bokutachi wa hikari no mukou e"))
                            .put(record(native, "[00:00.00]Opening\n[00:31.40]僕たちは光の向こうへ")),
                    )
            graph.store.put("cache", cacheKeys[0], payload.toString())
            graph.store.put("binding", key, trackJson(roman).toString())
            graph.store.put("lyrics", key, lyricsJson(old).toString())
            prefs.edit().putString("source:${current.key}", "LRCLIB").commit()
            instrumentation.runOnMainSync {
                graph.preferences(previous.copy(autoSources = false, autoTranslate = false))
                graph.onPlayback(Playback(current, "com.spotify.music"))
            }
            val deadline = android.os.SystemClock.elapsedRealtime() + 5000
            while (
                (graph.state.value.busy ||
                    graph.state.value.binding?.sourceId != native.sourceId) &&
                    android.os.SystemClock.elapsedRealtime() < deadline
            ) Thread.sleep(25)
            assertEquals(native.sourceId, graph.state.value.binding?.sourceId)
            val result = graph.state.value.lyrics!!
            assertEquals(LrcLibClient.REVISION, result.providerRevision)
            assertEquals("僕たちは光の向こうへ", result.lines.last().text)
            assertEquals("", result.lines.last().translation)
            assertEquals("已翻译的开场", result.lines.first().translation)
            val saved = readTrack(JSONObject(graph.store.get("binding", key)!!))
            assertEquals(native.sourceId, saved.sourceId)
        } finally {
            graph.scope.cancel()
            instrumentation.runOnMainSync { graph.preferences(previous) }
            prefs.edit().remove("source:${current.key}").commit()
            graph.store.remove("binding", key)
            graph.store.remove("lyrics", key)
            cacheKeys.forEach { graph.store.remove("cache", it) }
        }
    }
}
