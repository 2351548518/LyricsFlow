package com.example.lyricsflow

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.lyricsflow.core.LyricsFlowApp
import com.example.lyricsflow.playback.MusicNotificationListener
import com.example.lyricsflow.playback.PlayerMetadata
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in only: controls a real player's existing song, then restores its position/play state. */
@RunWith(AndroidJUnit4::class)
class RealPlayerIntegrationTest {
    @Test
    fun inspectPlayerMetadata() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("allowRealPlayer") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val player =
            context
                .getSystemService(MediaSessionManager::class.java)
                .getActiveSessions(ComponentName(context, MusicNotificationListener::class.java))
                .first { it.packageName == args.getString("playerPackage") }
        val metadata = player.metadata!!
        val keys =
            listOf(
                MediaMetadata.METADATA_KEY_TITLE,
                MediaMetadata.METADATA_KEY_ARTIST,
                MediaMetadata.METADATA_KEY_ALBUM,
                MediaMetadata.METADATA_KEY_DISPLAY_TITLE,
                MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
                MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION,
                MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
                "ucar.media.metadata.UCAR_TITLE",
                "ucar.media.metadata.UCAR_ARTIST",
            )
        report(
            "metadata",
            keys.joinToString("; ") { "$it=${metadata.getString(it)}" } +
                "; keys=" +
                metadata.keySet().sorted(),
        )
    }

    @Test
    fun existingPlayerPublishesLivePlayback() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Requires explicit real-device opt-in",
            args.getString("allowRealPlayer") == "true",
        )
        val pkg = args.getString("playerPackage").orEmpty()
        require(pkg in setOf("com.spotify.music", "com.tencent.qqmusic", "com.netease.cloudmusic"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val graph = (context.applicationContext as LyricsFlowApp).graph
        val manager = context.getSystemService(MediaSessionManager::class.java)
        val component = ComponentName(context, MusicNotificationListener::class.java)
        val controller = manager.getActiveSessions(component).firstOrNull { it.packageName == pkg }
        assertNotNull("Open $pkg and select a song before running this test", controller)
        val player = controller!!
        val originalState = player.playbackState
        val originalPosition = originalState?.position ?: -1
        val originalPlaying = originalState?.state == PlaybackState.STATE_PLAYING
        val originalId = player.metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
        val title = PlayerMetadata.track(pkg, player.metadata)?.title.orEmpty()
        assertTrue("Player must expose a song title", title.isNotBlank())
        val originalPrefs = graph.state.value.prefs
        var scenario: ActivityScenario<MainActivity>? = null
        fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
        fun await(label: String, timeout: Long = 20000, predicate: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + timeout
            while (!predicate() && SystemClock.elapsedRealtime() < end) Thread.sleep(100)
            assertTrue(
                "$pkg: $label; player=${player.playbackState?.state}, app=${graph.state.value.playback.packageName}/${graph.state.value.playback.playing}",
                predicate(),
            )
        }
        fun assertPositionMatchesPlayer() {
            val state = player.playbackState!!
            val now = SystemClock.elapsedRealtime()
            val expected =
                state.position +
                    if (
                        state.state == PlaybackState.STATE_PLAYING &&
                            state.lastPositionUpdateTime > 0
                    )
                        ((now - state.lastPositionUpdateTime).coerceAtLeast(0) *
                                state.playbackSpeed)
                            .toLong()
                    else 0
            val actual = graph.state.value.playback
            assertTrue("Player must expose a real position", actual.positionKnown)
            assertTrue(
                "Position differs by ${abs(actual.at(now) - expected)} ms",
                abs(actual.at(now) - expected) < 1500,
            )
        }
        try {
            scenario = ActivityScenario.launch(MainActivity::class.java)
            main {
                graph.preferences(originalPrefs.copy(playbackApp = "", autoTranslate = false))
                graph.live()
                graph.playback.connect()
                val duration = player.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0
                if (
                    duration > 60000 &&
                        originalPosition > duration - 30000 &&
                        (originalState?.actions ?: 0) and PlaybackState.ACTION_SEEK_TO != 0L
                ) {
                    player.transportControls.seekTo(20000)
                }
                player.transportControls.play()
            }
            await("automatic selection must follow the playing real app") {
                val p = graph.state.value.playback
                p.packageName == pkg && p.track?.title == title && p.playing
            }
            assertEquals(
                "Verify playback is on this phone",
                MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL,
                player.playbackInfo?.playbackType,
            )
            val start = graph.state.value.playback.at(SystemClock.elapsedRealtime())
            Thread.sleep(1800)
            assertTrue(
                "Live timeline must advance",
                graph.state.value.playback.at(SystemClock.elapsedRealtime()) - start >= 1000,
            )
            assertPositionMatchesPlayer()
            val stableTrack = graph.state.value.playback.track?.key
            Thread.sleep(4000)
            assertEquals(
                "Lyric metadata updates must not change song identity",
                stableTrack,
                graph.state.value.playback.track?.key,
            )
            main { player.transportControls.pause() }
            await("pause must reach LyricsFlow") {
                player.playbackState?.state == PlaybackState.STATE_PAUSED &&
                    !graph.state.value.playback.playing
            }
            val paused = graph.state.value.playback.at(SystemClock.elapsedRealtime())
            Thread.sleep(1200)
            assertEquals(
                "Paused timeline must stay still",
                paused,
                graph.state.value.playback.at(SystemClock.elapsedRealtime()),
            )
            assertPositionMatchesPlayer()
            main {
                graph.playback.disconnect()
                graph.playback.connect()
            }
            await("reconnection must retain the most recently paused player") {
                graph.state.value.playback.packageName == pkg && !graph.state.value.playback.playing
            }
            assertPositionMatchesPlayer()
            val canSeek =
                (player.playbackState?.actions ?: 0) and PlaybackState.ACTION_SEEK_TO != 0L
            if (canSeek) {
                val duration = player.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0
                val target =
                    if (paused > 30000) 15000L
                    else 45000L.coerceAtMost((duration - 5000).coerceAtLeast(0))
                main { player.transportControls.seekTo(target) }
                await("seek must reach LyricsFlow") {
                    abs(graph.state.value.playback.position - target) < 1500 &&
                        abs((player.playbackState?.position ?: -9999) - target) < 1500
                }
                assertPositionMatchesPlayer()
            }
            main { player.transportControls.play() }
            await("resume must reach LyricsFlow") {
                graph.state.value.playback.packageName == pkg && graph.state.value.playback.playing
            }
            // Allow the normal automatic provider lookup to finish; lyric availability is a
            // separate result.
            await("automatic lyric lookup must terminate", 60000) { !graph.state.value.busy }
            val result = graph.state.value
            report(
                "result",
                "REAL_PLAYER package=$pkg title=$title local=true automatic=true advancing=true pause=true seek=$canSeek resume=true positionDeltaMs=${abs(result.playback.at(SystemClock.elapsedRealtime()) - playerPosition(player))} lyrics=${result.lyrics?.lines?.size ?: 0} source=${result.source} message=${result.message}",
            )
            if (args.getString("requireLyrics") == "true") {
                assertTrue(
                    "Expected real timestamped lyrics: ${result.message}",
                    result.lyrics?.lines?.any { it.start != null } == true,
                )
                assertTrue(
                    "Active lyric row must follow the player",
                    result.lyrics!!.active(
                        result.playback.at(SystemClock.elapsedRealtime()) - result.delay
                    ) >= 0,
                )
            }
            if (args.getString("captureWindow") == "true") Thread.sleep(15000)
        } finally {
            main {
                // Never seek a different song if the player changed its queue independently.
                val sameSong =
                    player.metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID) == originalId &&
                        PlayerMetadata.track(pkg, player.metadata)?.title == title
                if (sameSong) {
                    if (
                        originalPosition >= 0 &&
                            (player.playbackState?.actions ?: 0) and PlaybackState.ACTION_SEEK_TO !=
                                0L
                    )
                        player.transportControls.seekTo(originalPosition)
                    if (originalPlaying) player.transportControls.play()
                    else player.transportControls.pause()
                }
                graph.preferences(originalPrefs)
            }
            scenario?.close()
        }
    }

    private fun playerPosition(player: MediaController): Long {
        val p = player.playbackState ?: return 0
        return p.position +
            if (p.state == PlaybackState.STATE_PLAYING && p.lastPositionUpdateTime > 0)
                ((SystemClock.elapsedRealtime() - p.lastPositionUpdateTime).coerceAtLeast(0) *
                        p.playbackSpeed)
                    .toLong()
            else 0
    }

    private fun report(key: String, value: String) {
        Log.i("LyricsFlowQA", value)
        InstrumentationRegistry.getInstrumentation()
            .sendStatus(2, Bundle().apply { putString(key, value) })
    }
}
