package com.example.lyricsflow

import com.example.lyricsflow.core.*
import com.example.lyricsflow.data.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AuditStateTest {
    @Test
    fun commentMatchDoesNotChangeLyricsAndSurvivesBackupRestore() = runBlocking {
        val f = ChoiceFixture()
        val song = f.song()
        try {
            val lyricRecord = f.seed(song, Provider.QQ)
            f.play(song)
            val other = f.backend.candidate(song, Provider.QQ, "comment-version")
            f.graph.bindComments(song, other)
            assertEquals(other, f.graph.commentBinding(song, Provider.QQ))
            assertEquals(
                lyricRecord,
                readTrack(JSONObject(f.graph.store.get("binding", "${song.key}:QQ")!!)),
            )
            f.main { f.graph.reloadRememberedLyrics() }
            f.waitFor()
            assertEquals(lyricRecord.sourceId, f.graph.state.value.binding?.sourceId)
            assertEquals(f.backend.payload(lyricRecord), f.graph.state.value.lyrics)
            val backup = Transfer.validateBackup(Transfer.backup(f.graph, false))
            assertTrue(
                backup.first.any { it.first == "commentBinding" && it.second == "${song.key}:QQ" }
            )
            f.graph.store.remove("commentBinding", "${song.key}:QQ")
            f.graph.store.restore(backup.first.filter { it.first == "commentBinding" })
            assertEquals(other, f.graph.commentBinding(song, Provider.QQ))
        } finally {
            f.graph.store.remove("commentBinding", "${song.key}:QQ")
            f.clean()
        }
    }

    @Test
    fun translationFromPreviousSourceCannotOverwriteIdenticalTextInNewSource() {
        val f = ChoiceFixture()
        try {
            val song = f.song()
            val rows = listOf(Line(0, 60000, "Same original"))
            f.seed(song, Provider.QQ)
            f.seed(song, Provider.NETEASE)
            for (provider in listOf(Provider.QQ, Provider.NETEASE)) {
                f.graph.store.put(
                    "lyrics",
                    "${song.key}:${provider.name}",
                    lyricsJson(Lyrics(rows, provider, providerRevision = 99)).toString(),
                )
            }
            f.play(song)
            val old = f.graph.state.value.lyrics!!
            f.main { f.graph.selectSource(Provider.NETEASE) }
            f.waitFor()
            assertEquals(old.hash, f.graph.state.value.lyrics?.hash)
            f.main {
                assertFalse(
                    f.graph.applyTranslation(
                        rows.map { it.copy(translation = "old response") },
                        old.hash,
                        song.key,
                        Provider.QQ,
                    )
                )
            }
            assertEquals("", f.graph.state.value.lyrics!!.lines.single().translation)
            assertEquals(Provider.NETEASE, f.graph.state.value.source)
        } finally {
            f.clean()
        }
    }

    @Test
    fun enteringPreviewClearsPreviousSongSelectionAndSavedStatus() {
        val f = ChoiceFixture()
        try {
            val song = f.song()
            f.seed(song, Provider.QQ)
            f.play(song)
            f.main { f.graph.selectSource(Provider.QQ) }
            f.waitFor()
            assertTrue(f.graph.state.value.matchNotice.isNotBlank())
            f.main { f.graph.preview() }
            assertEquals("", f.graph.state.value.matchNotice)
            assertEquals("auto", f.graph.state.value.selectionMode)
            assertNull(f.graph.state.value.requestedSource)
            assertFalse(f.graph.state.value.saved)
        } finally {
            f.clean()
        }
    }

    @Test
    fun savedSongIsRecognizedAfterLeavingAndReturning() = runBlocking {
        val f = ChoiceFixture()
        val song = f.song()
        try {
            val record = f.seed(song, Provider.QQ)
            f.graph.store.put(
                "saved",
                song.key,
                JSONObject()
                    .put("track", trackJson(song))
                    .put("lyrics", lyricsJson(f.backend.payload(record)))
                    .toString(),
            )
            f.play(song)
            assertTrue("Stored favorite must restore the home checkmark", f.graph.state.value.saved)
            withContext(Dispatchers.Main) { f.graph.removeSaved(song) }
            assertFalse(f.graph.state.value.saved)
        } finally {
            f.graph.store.remove("saved", song.key)
            f.clean()
        }
    }

    @Test
    fun completingAnOldSaveCannotMarkTheNextSongSaved() {
        val f = ChoiceFixture()
        val song = f.song()
        val next = f.song()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            f.seed(song, Provider.QQ)
            f.seed(next, Provider.QQ)
            f.play(song)
            val blocker = Thread {
                synchronized(f.graph.store) {
                    entered.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
            }
                .apply { start() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            f.main {
                f.graph.saveCurrent()
                f.graph.onPlayback(Playback(next, "com.spotify.music"))
            }
            release.countDown()
            blocker.join(5000)
            f.waitFor { f.graph.store.get("saved", song.key) != null }
            f.waitFor()
            assertEquals(next.key, f.graph.state.value.playback.track?.key)
            assertFalse("The save belongs only to the captured song", f.graph.state.value.saved)
        } finally {
            release.countDown()
            f.graph.store.remove("saved", song.key)
            f.graph.store.remove("saved", next.key)
            f.clean()
        }
    }
}
