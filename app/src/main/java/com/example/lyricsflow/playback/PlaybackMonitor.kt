package com.example.lyricsflow.playback

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import com.example.lyricsflow.core.*

class MusicNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        (application as LyricsFlowApp).graph.playback.connect()
    }

    override fun onListenerDisconnected() {
        (application as LyricsFlowApp).graph.playback.disconnect()
    }
    // Notification text is deliberately never read or persisted.
}

class PlaybackMonitor(
    private val context: Context,
    private val allowed: Set<String> =
        setOf(
            "com.spotify.music",
            "com.netease.cloudmusic",
            "com.tencent.qqmusic",
            "com.apple.android.music",
            "com.google.android.apps.youtube.music",
            "org.videolan.vlc",
            "in.krosbits.musicolet",
        ),
    private val publish: (Playback) -> Unit,
) {
    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val component = ComponentName(context, MusicNotificationListener::class.java)
    private val controllers = mutableListOf<MediaController>()
    var preferred = ""
    private var registered = false
    private var selected: MediaController? = null
    private val artworkMemory = ArtworkMemory()
    private var emptyScheduled = false
    private val emptyPlayback = Runnable {
        emptyScheduled = false
        artworkMemory.clear()
        publish(Playback())
    }

    private fun cancelEmptyPlayback() {
        handler.removeCallbacks(emptyPlayback)
        emptyScheduled = false
    }

    private fun scheduleEmptyPlayback() {
        if (!emptyScheduled) {
            emptyScheduled = true
            handler.postDelayed(emptyPlayback, 500)
        }
    }

    private val callback =
        object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) {
                emit()
            }

            override fun onPlaybackStateChanged(state: PlaybackState?) {
                select()
            }

            override fun onSessionDestroyed() {
                refresh()
            }
        }
    private val listener = MediaSessionManager.OnActiveSessionsChangedListener {
        update(it.orEmpty())
    }

    fun connect() {
        if (!registered)
            runCatching {
                manager.addOnActiveSessionsChangedListener(listener, component, handler)
                registered = true
            }
        refresh()
    }

    fun disconnect() {
        cancelEmptyPlayback()
        artworkMemory.clear()
        controllers.forEach { it.unregisterCallback(callback) }
        controllers.clear()
        selected = null
        if (registered) manager.removeOnActiveSessionsChangedListener(listener)
        registered = false
        publish(Playback())
    }

    fun refresh() {
        runCatching { update(manager.getActiveSessions(component)) }.onFailure { disconnect() }
    }

    private fun update(list: List<MediaController>) {
        controllers.forEach { it.unregisterCallback(callback) }
        controllers.clear()
        controllers.addAll(list.filter { it.packageName in allowed })
        controllers.forEach { it.registerCallback(callback, handler) }
        select()
    }

    private fun select() {
        val playing = controllers.filter { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        selected =
            controllers.firstOrNull { preferred.isNotBlank() && it.packageName == preferred }
                ?: playing.firstOrNull { it.packageName == "com.spotify.music" }
                ?: playing.maxByOrNull { it.playbackState?.lastPositionUpdateTime ?: 0 }
                // Pausing a player must retain its song/position rather than jump to an older
                // paused Spotify session. Another playing app still wins above.
                ?: controllers.firstOrNull { it.sessionToken == selected?.sessionToken }
                ?: controllers.maxByOrNull { it.playbackState?.lastPositionUpdateTime ?: 0 }
        emit()
    }

    private fun emit() {
        val c =
            selected
                ?: run {
                    scheduleEmptyPlayback()
                    return
                }
        val m = c.metadata
        val p = c.playbackState
        val t =
            PlayerMetadata.track(c.packageName, m)
                ?: run {
                    scheduleEmptyPlayback()
                    return
                }
        // ART can be a small thumbnail even when ALBUM_ART holds the full cover.
        cancelEmptyPlayback()
        val artwork = artworkMemory.select(t.key, PlayerMetadata.artwork(m))
        publish(
            Playback(
                t,
                c.packageName,
                p?.state == PlaybackState.STATE_PLAYING,
                p?.position?.coerceAtLeast(0) ?: 0,
                p?.lastPositionUpdateTime?.takeIf { it > 0 } ?: SystemClock.elapsedRealtime(),
                p?.playbackSpeed ?: 1f,
                artwork,
                p != null && p.position >= 0,
            )
        )
    }
}
