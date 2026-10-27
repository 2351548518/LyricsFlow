package com.example.lyricsflow

import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.lyricsflow.core.Playback
import com.example.lyricsflow.playback.PlaybackMonitor
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Needs notification-listener access granted on the QA emulator; no production test backdoor. */
@RunWith(AndroidJUnit4::class)
class PlaybackIntegrationTest {
    @Test
    fun realSessionUpdatesSeekPauseAndSwitch() {
        val i = InstrumentationRegistry.getInstrumentation()
        val context = i.targetContext
        val latest = AtomicReference(Playback())
        lateinit var session: MediaSession
        lateinit var monitor: PlaybackMonitor
        fun metadata(title: String) =
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Fixture artist")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, 80000)
                .build()
        fun state(status: Int, position: Long, speed: Float = 1f) =
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SEEK_TO)
                .setState(status, position, speed, SystemClock.elapsedRealtime())
                .build()
        fun await(assertion: () -> Boolean) {
            val until = SystemClock.elapsedRealtime() + 5000
            while (!assertion() && SystemClock.elapsedRealtime() < until) Thread.sleep(50)
            assertTrue(assertion())
        }
        i.runOnMainSync {
            session = MediaSession(context, "LyricsFlow QA session")
            session.setMetadata(metadata("First track"))
            session.setPlaybackState(state(PlaybackState.STATE_PLAYING, 1000))
            session.isActive = true
            monitor = PlaybackMonitor(context, setOf(context.packageName)) { latest.set(it) }
            monitor.connect()
        }
        try {
            await { latest.get().track?.title == "First track" && latest.get().playing }
            assertTrue(latest.get().at(SystemClock.elapsedRealtime()) >= 1000)
            i.runOnMainSync { session.setPlaybackState(state(PlaybackState.STATE_PAUSED, 22000)) }
            await { !latest.get().playing && latest.get().position == 22000L }
            assertEquals(22000L, latest.get().at(SystemClock.elapsedRealtime() + 5000))
            i.runOnMainSync {
                session.setMetadata(metadata("Second track"))
                session.setPlaybackState(state(PlaybackState.STATE_PLAYING, 5000, 2f))
            }
            await { latest.get().track?.title == "Second track" && latest.get().speed == 2f }
            assertTrue(latest.get().at(SystemClock.elapsedRealtime() + 1000) >= 7000)
        } finally {
            i.runOnMainSync {
                monitor.disconnect()
                session.release()
            }
        }
    }
}
