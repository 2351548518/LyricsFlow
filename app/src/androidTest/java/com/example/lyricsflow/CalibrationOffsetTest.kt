package com.example.lyricsflow

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.lyricsflow.core.*
import com.example.lyricsflow.data.*
import java.util.UUID
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CalibrationOffsetTest {
    @Test
    fun oneTimeRepairPreservesOtherOffsetsAndFutureManual300() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("offset-qa-${UUID.randomUUID()}", 0)
        try {
            prefs
                .edit()
                .putLong("delay:affected:QQ", 300)
                .putLong("delay:manual:NETEASE", -500)
                .putLong("unrelated", 300)
                .commit()
            assertEquals(1, CalibrationOffsets.repairAccidental300(prefs))
            assertEquals(0L, prefs.getLong("delay:affected:QQ", 0))
            assertEquals(-500L, prefs.getLong("delay:manual:NETEASE", 0))
            assertEquals(300L, prefs.getLong("unrelated", 0))
            prefs.edit().putLong("delay:manualLater:QQ", 300).commit()
            assertEquals(0, CalibrationOffsets.repairAccidental300(prefs))
            assertEquals(300L, prefs.getLong("delay:manualLater:QQ", 0))
        } finally {
            prefs.edit().clear().commit()
        }
    }

    @Test
    fun automaticMatchingWaitsWithoutWritingCalibration() {
        val i = InstrumentationRegistry.getInstrumentation()
        val app = i.targetContext.applicationContext as LyricsFlowApp
        val graph = AppGraph(app)
        val original = graph.state.value.prefs
        val prefs = app.getSharedPreferences("settings", 0)
        val priority = prefs.getString("lyricPriority", null)
        val track = Track("Offset QA ${UUID.randomUUID()}")
        val key = track.key + ":QQ"
        val offsetKey = "delay:$key"
        graph.store.put(
            "binding",
            key,
            trackJson(track.copy(provider = Provider.QQ, sourceId = "qa-${track.key}")).toString(),
        )
        graph.store.put(
            "lyrics",
            key,
            lyricsJson(
                    Lyrics(
                        listOf(Line(0, 10000, "Fixture")),
                        Provider.QQ,
                        providerRevision = QqLyricCodec.REVISION,
                    )
                )
                .toString(),
        )
        try {
            i.runOnMainSync {
                graph.preferences(
                    graph.state.value.prefs.copy(autoSources = false, autoTranslate = false)
                )
                prefs.edit().putString("lyricPriority", "QQ").commit()
                graph.onPlayback(Playback(track, "com.spotify.music"))
            }
            val until = SystemClock.elapsedRealtime() + 5000
            while (graph.state.value.busy && SystemClock.elapsedRealtime() < until) Thread.sleep(20)
            assertNotNull(graph.state.value.lyrics)
            assertEquals(0L, graph.state.value.delay)
            assertFalse(
                "A coroutine wait must not persist a calibration offset",
                prefs.contains(offsetKey),
            )
            i.runOnMainSync { graph.setLyricOffset(200) }
            assertEquals(200L, prefs.getLong(offsetKey, 0))
            i.runOnMainSync { graph.setLyricOffset(0) }
            assertEquals(0L, graph.state.value.delay)
        } finally {
            i.runOnMainSync { graph.preferences(original) }
            graph.scope.cancel()
            prefs
                .edit()
                .remove(offsetKey)
                .apply {
                    if (priority == null) remove("lyricPriority")
                    else putString("lyricPriority", priority)
                }
                .commit()
            graph.store.remove("binding", key)
            graph.store.remove("lyrics", key)
            graph.store.remove("selection", track.key)
        }
    }
}
