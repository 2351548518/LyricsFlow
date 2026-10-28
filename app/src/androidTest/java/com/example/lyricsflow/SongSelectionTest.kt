package com.example.lyricsflow

import android.app.Application
import androidx.test.platform.app.InstrumentationRegistry
import com.example.lyricsflow.core.*
import com.example.lyricsflow.data.*
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

internal class ChoiceBackend : LyricsLookup {
    val searches = CopyOnWriteArrayList<Provider>()
    val fetches = CopyOnWriteArrayList<Pair<Track, Boolean>>()
    val failures = java.util.concurrent.ConcurrentHashMap.newKeySet<Provider>()
    var fetch: (suspend (Track, Boolean) -> Lyrics)? = null
    var search: (suspend (Provider, Track) -> List<Track>)? = null

    fun candidate(t: Track, p: Provider, id: String = "${p.name}-record") =
        t.copy(provider = p, sourceId = id)

    fun payload(t: Track) =
        Lyrics(
            listOf(Line(0, 60000, "${t.provider.label} ${t.sourceId}")),
            t.provider,
            providerRevision = 99,
        )

    override suspend fun searchExpanded(
        provider: Provider,
        query: String,
        current: Track?,
    ): List<Track> {
        searches += provider
        if (provider in failures) throw ApiFailure("HTTP 503")
        return search?.invoke(provider, current!!) ?: listOf(candidate(current!!, provider))
    }

    override suspend fun lyrics(t: Track, refresh: Boolean): Lyrics {
        fetches += t to refresh
        if (t.provider in failures) throw ApiFailure("HTTP 503")
        return fetch?.invoke(t, refresh) ?: payload(t)
    }
}

internal class ChoiceFixture {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val backend = ChoiceBackend()
    val graph = AppGraph(instrumentation.targetContext.applicationContext as Application, backend)
    val original = graph.state.value.prefs
    val tracks = mutableListOf<Track>()

    fun song(): Track =
        Track("Choice QA ${UUID.randomUUID()}", "LyricsFlow", "Fixture", 60000).also {
            tracks += it
        }

    fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    fun waitFor(g: AppGraph = graph, predicate: () -> Boolean = { !g.state.value.busy }) {
        val end = android.os.SystemClock.elapsedRealtime() + 10000
        while (!predicate() && android.os.SystemClock.elapsedRealtime() < end) Thread.sleep(20)
        assertTrue("Timed out: ${g.state.value.message}", predicate())
    }

    fun seed(t: Track, p: Provider): Track {
        val record = backend.candidate(t, p)
        graph.store.put("binding", "${t.key}:${p.name}", trackJson(record).toString())
        graph.store.put(
            "lyrics",
            "${t.key}:${p.name}",
            lyricsJson(backend.payload(record)).toString(),
        )
        return record
    }

    fun play(t: Track, g: AppGraph = graph) {
        main {
            g.preferences(g.state.value.prefs.copy(autoSources = true, autoTranslate = false))
            g.onPlayback(Playback(t, "com.spotify.music"))
        }
        waitFor(g)
    }

    fun choice(t: Track) =
        SongSelection.read(t.key, JSONObject(graph.store.get("selection", t.key)!!))

    fun clean() {
        graph.scope.cancel()
        main { graph.preferences(original) }
        val edit = graph.app.getSharedPreferences("settings", 0).edit()
        for (t in tracks) {
            graph.store.remove("selection", t.key)
            for (p in Provider.entries) {
                graph.store.remove("binding", "${t.key}:${p.name}")
                graph.store.remove("lyrics", "${t.key}:${p.name}")
                edit.remove("delay:${t.key}:${p.name}")
            }
            edit.remove("source:${t.key}")
        }
        edit.commit()
    }
}

class SongSelectionTest {
    @Test
    fun lateDurationRetriesTheRequestedSourceInsteadOfResettingToTheGlobalOrder() {
        val f = ChoiceFixture()
        try {
            val song = f.song().copy(duration = 0)
            f.backend.search = { provider, t ->
                listOf(
                    f.backend.candidate(t.copy(duration = 60000), provider, "full"),
                    f.backend.candidate(t.copy(duration = 120000), provider, "other"),
                )
            }
            f.play(song)
            f.main { f.graph.selectSource(Provider.LRCLIB) }
            f.waitFor()
            assertNull(f.graph.state.value.lyrics)
            f.main {
                f.graph.onPlayback(Playback(song.copy(duration = 60000), "com.spotify.music"))
            }
            f.waitFor()
            assertEquals(Provider.LRCLIB, f.graph.state.value.source)
            assertEquals("full", f.graph.state.value.binding?.sourceId)
        } finally {
            f.clean()
        }
    }

    @Test
    fun switchingUsesOfflineTargetCacheAndRestoresItBeforeGlobalAutoOrder() {
        val f = ChoiceFixture()
        var restarted: AppGraph? = null
        try {
            val t = f.song()
            f.seed(t, Provider.QQ)
            val chosen = f.seed(t, Provider.LRCLIB)
            f.backend.failures.addAll(LYRIC_PROVIDERS)
            f.play(t)
            f.main { f.graph.selectSource(Provider.LRCLIB) }
            f.waitFor()
            assertEquals(Provider.LRCLIB, f.graph.state.value.source)
            assertEquals(chosen.sourceId, f.choice(t).record?.sourceId)
            assertTrue(f.backend.searches.isEmpty() && f.backend.fetches.isEmpty())
            restarted = AppGraph(f.graph.app, f.backend)
            f.play(t, restarted)
            assertEquals(chosen, restarted.state.value.binding)
            assertEquals("source", restarted.state.value.selectionMode)
            val timestamp = f.choice(t).updatedAt
            val snapshot = SnapshotFiles.unzip(Transfer.backup(restarted)).getValue("data.json")
            f.main {
                restarted.onPlayback(Playback())
                restarted.onPlayback(Playback(t, "com.spotify.music"))
            }
            f.waitFor(restarted)
            assertEquals(timestamp, f.choice(t).updatedAt)
            assertArrayEquals(
                snapshot,
                SnapshotFiles.unzip(Transfer.backup(restarted)).getValue("data.json"),
            )
        } finally {
            restarted?.scope?.cancel()
            f.clean()
        }
    }

    @Test
    fun failedSwitchKeepsActualSourceBindingAndPreviousSavedChoice() {
        val f = ChoiceFixture()
        try {
            val t = f.song()
            val old = f.seed(t, Provider.QQ)
            f.play(t)
            val saved = f.graph.store.get("selection", t.key)
            f.backend.failures += Provider.LRCLIB
            f.main { f.graph.selectSource(Provider.LRCLIB) }
            f.waitFor()
            val state = f.graph.state.value
            assertEquals(Provider.QQ, state.source)
            assertEquals(old, state.binding)
            assertEquals(Provider.LRCLIB, state.requestedSource)
            assertTrue(
                state.matchNotice.contains("LRCLIB") &&
                    state.matchNotice.contains("503") &&
                    state.matchNotice.contains("仍显示 QQ")
            )
            assertEquals(saved, f.graph.store.get("selection", t.key))
            assertNotEquals(
                "LRCLIB",
                f.graph.app.getSharedPreferences("settings", 0).getString("source:${t.key}", null),
            )
        } finally {
            f.clean()
        }
    }

    @Test
    fun exactBindingAndRefreshKeepTheRecordAndFailedRefreshPreservesIt() {
        val f = ChoiceFixture()
        try {
            val t = f.song()
            f.seed(t, Provider.QQ)
            f.play(t)
            val record = f.backend.candidate(t, Provider.LRCLIB, "chosen-upload-2")
            f.main { f.graph.bind(record) }
            f.waitFor()
            assertEquals("record", f.choice(t).mode)
            val before = f.graph.store.get("selection", t.key)
            f.main { f.graph.selectSource(Provider.LRCLIB) }
            assertEquals("record", f.choice(t).mode)
            f.main { f.graph.refresh() }
            f.waitFor()
            assertEquals(record to true, f.backend.fetches.last())
            assertTrue(f.backend.searches.isEmpty())
            assertEquals(before, f.graph.store.get("selection", t.key))
            f.backend.failures += Provider.LRCLIB
            f.main { f.graph.refresh() }
            f.waitFor()
            assertEquals(record, f.graph.state.value.binding)
            assertEquals(Provider.LRCLIB, f.graph.state.value.source)
            assertTrue(f.graph.state.value.matchNotice.contains("刷新 LRCLIB 失败"))
            assertEquals(before, f.graph.store.get("selection", t.key))
        } finally {
            f.clean()
        }
    }

    @Test
    fun explicitAutoRematchStoresAnAutoMarkerThatCanReplaceAnOlderPinOnRestore() = runBlocking {
        val f = ChoiceFixture()
        try {
            val t = f.song()
            f.seed(t, Provider.QQ)
            f.play(t)
            f.main { f.graph.bind(f.backend.candidate(t, Provider.LRCLIB)) }
            f.waitFor()
            val pinned = f.graph.store.get("selection", t.key)!!
            f.backend.failures.addAll(LYRIC_PROVIDERS)
            f.main { f.graph.matchAutomatically() }
            f.waitFor()
            assertEquals(pinned, f.graph.store.get("selection", t.key))
            assertEquals(Provider.LRCLIB, f.graph.state.value.source)
            f.backend.failures.clear()
            f.backend.searches.clear()
            f.main { f.graph.matchAutomatically() }
            f.waitFor()
            assertEquals(listOf(Provider.QQ), f.backend.searches.toList())
            assertEquals("auto", f.choice(t).mode)
            val backup = Transfer.validateBackup(Transfer.backup(f.graph))
            f.main { f.graph.onPlayback(Playback()) }
            f.graph.store.put("selection", t.key, pinned)
            withContext(Dispatchers.Main) { restoreBackup(f.graph, backup) }
            f.play(t)
            assertEquals("auto", f.choice(t).mode)
            assertEquals(Provider.QQ, f.graph.state.value.source)
        } finally {
            f.clean()
        }
    }

    @Test
    fun rapidSwitchCannotCommitAnOlderRequestAfterTheLatestChoice() = runBlocking {
        val f = ChoiceFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            val t = f.song()
            f.seed(t, Provider.QQ)
            f.seed(t, Provider.NETEASE)
            f.play(t)
            f.backend.fetch = { record, _ ->
                if (record.provider == Provider.LRCLIB) {
                    entered.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                }
                f.backend.payload(record)
            }
            f.main { f.graph.selectSource(Provider.LRCLIB) }
            withTimeout(3000) { entered.await() }
            f.main { f.graph.selectSource(Provider.NETEASE) }
            f.waitFor()
            release.complete(Unit)
            delay(150)
            assertEquals(Provider.NETEASE, f.choice(t).source)
            assertEquals(Provider.NETEASE, f.graph.state.value.source)
            assertNull(f.graph.store.get("binding", "${t.key}:LRCLIB"))
        } finally {
            release.complete(Unit)
            f.clean()
        }
    }

    @Test
    fun privateGitHubSnapshotRoundtripRestoresTheSpecificRecordAndCalibrationOffline() =
        runBlocking {
            val f = ChoiceFixture()
            var restarted: AppGraph? = null
            try {
                val t = f.song()
                f.seed(t, Provider.QQ)
                f.play(t)
                val record = f.backend.candidate(t, Provider.LRCLIB, "user-upload-7")
                f.main { f.graph.bind(record) }
                f.waitFor()
                f.main { f.graph.setLyricOffset(650) }
                val server = MemoryGitHub()
                val api = GitHubApi(server)
                val repo = GitHubRepo("lyricsflow-sync", "main", 73)
                val files = SnapshotFiles.unzip(Transfer.backup(f.graph))
                assertTrue(
                    Transfer.validateBackup(SnapshotFiles.zip(files)).first.any {
                        it.first == "selection" && it.second == t.key
                    }
                )
                val empty = api.snapshot("fake", "qa-user", repo)
                api.upload("fake", "qa-user", empty, files, "")
                val remote = api.snapshot("fake", "qa-user", repo)
                f.main { f.graph.onPlayback(Playback()) }
                f.graph.store.remove("selection", t.key)
                f.graph.store.remove("binding", "${t.key}:LRCLIB")
                f.graph.store.remove("lyrics", "${t.key}:LRCLIB")
                f.graph.app
                    .getSharedPreferences("settings", 0)
                    .edit()
                    .remove("source:${t.key}")
                    .remove("delay:${t.key}:LRCLIB")
                    .commit()
                withContext(Dispatchers.Main) {
                    restoreBackup(f.graph, Transfer.validateBackup(SnapshotFiles.zip(remote.files)))
                }
                f.backend.failures.addAll(LYRIC_PROVIDERS)
                restarted = AppGraph(f.graph.app, f.backend)
                f.play(t, restarted)
                assertEquals(record, restarted.state.value.binding)
                assertEquals(650L, restarted.state.value.delay)
                assertEquals("record", restarted.state.value.selectionMode)
                assertEquals(Provider.LRCLIB, restarted.state.value.source)
            } finally {
                restarted?.scope?.cancel()
                f.clean()
            }
        }

    @Test
    fun localImportIsRememberedAndReloadsWithoutAnyProviderRequest() {
        val f = ChoiceFixture()
        var restarted: AppGraph? = null
        try {
            val t = f.song()
            f.seed(t, Provider.QQ)
            f.play(t)
            f.main {
                f.graph.installLyrics(null, Lyrics(listOf(Line(0, 60000, "本地选择")), Provider.LOCAL))
            }
            f.waitFor { f.graph.state.value.source == Provider.LOCAL }
            restarted = AppGraph(f.graph.app, f.backend)
            f.play(t, restarted)
            assertEquals(Provider.LOCAL, restarted.state.value.source)
            assertEquals("本地选择", restarted.state.value.lyrics!!.lines.single().text)
            assertTrue(f.backend.fetches.isEmpty() && f.backend.searches.isEmpty())
        } finally {
            restarted?.scope?.cancel()
            f.clean()
        }
    }

    @Test
    fun restoreRejectsChoiceWithForeignSongKeyOrProvider() {
        val f = ChoiceFixture()
        try {
            val t = f.song()
            val choice =
                SongSelection(t, Provider.QQ, f.backend.candidate(t, Provider.NETEASE), "record")
            val files = SnapshotFiles.unzip(Transfer.backup(f.graph)).toMutableMap()
            files["data.json"] =
                JSONArray()
                    .put(
                        JSONObject()
                            .put("kind", "selection")
                            .put("id", t.key)
                            .put("body", choice.json().toString())
                    )
                    .toString()
                    .toByteArray()
            assertThrows(IllegalArgumentException::class.java) {
                Transfer.validateBackup(SnapshotFiles.zip(files))
            }
            assertThrows(IllegalArgumentException::class.java) {
                SongSelection.read("foreign-key", choice.json())
            }
            val incompleteLegacy =
                SongSelection(t, Provider.QQ, t.copy(provider = Provider.QQ), "auto")
            assertNull(SongSelection.read(t.key, incompleteLegacy.json()).record)
        } finally {
            f.clean()
        }
    }
}
