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
import androidx.core.graphics.drawable.toBitmap
import com.example.lyricsflow.core.*
import kotlinx.coroutines.*

class MusicNotificationListener : NotificationListenerService() {
    companion object {
        private var listener = java.lang.ref.WeakReference<MusicNotificationListener>(null)
        @Suppress("DEPRECATION")
        fun coverIcon(packageName: String, title: String, token: android.media.session.MediaSession.Token): android.graphics.drawable.Icon? =
            runCatching {
                listener.get()?.activeNotifications?.firstNotNullOfOrNull { notification ->
                if (notification.packageName != packageName) return@firstNotNullOfOrNull null
                val n = notification.notification
                val session = n.extras.getParcelable<android.media.session.MediaSession.Token>(android.app.Notification.EXTRA_MEDIA_SESSION)
                if (session != token || n.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()?.trim() != title.trim())
                    null else n.getLargeIcon()
            } }.getOrNull()
    }
    override fun onListenerConnected() {
        super.onListenerConnected()
        listener = java.lang.ref.WeakReference(this)
        (application as LyricsFlowApp).graph.playback.connect()
    }

    override fun onListenerDisconnected() {
        listener.clear()
        (application as LyricsFlowApp).graph.playback.disconnect()
    }
    override fun onNotificationPosted(notification: android.service.notification.StatusBarNotification?) {
        notification ?: return
        (application as LyricsFlowApp).graph.playback.artworkNotificationArrived(notification)
    }
    // Only a matching music session's title and artwork are inspected, never chat notifications.
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
    private val loadArtwork: (suspend (List<String>) -> android.graphics.Bitmap?)? = null,
    private val alternativeArtwork: suspend (Track) -> android.graphics.Bitmap? = { null },
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
    private val artworkScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var artworkJob: Job? = null
    private var artworkRequest: Triple<android.media.session.MediaSession.Token, String, List<String>>? = null
    private var artworkOwner: android.media.session.MediaSession.Token? = null
    private var notificationRetriedFor: String? = null
    private var hasArtwork = false
    private var artworkRetryAfter = 0L
    /** Explicit refresh / a newly available network can recover an exhausted request. */
    fun retryArtwork() {
        if (hasArtwork) return
        cancelArtwork()
        refresh()
    }
    @Suppress("DEPRECATION")
    fun artworkNotificationArrived(notification: android.service.notification.StatusBarNotification) {
        val controller = selected ?: return
        if (hasArtwork || notification.packageName != controller.packageName) return
        val track = PlayerMetadata.track(controller.packageName, controller.metadata) ?: return
        val n = notification.notification
        if (notificationRetriedFor == track.key || n.getLargeIcon() == null ||
            n.extras.getParcelable<android.media.session.MediaSession.Token>(android.app.Notification.EXTRA_MEDIA_SESSION) != controller.sessionToken ||
            n.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()?.trim() != track.title.trim()) return
        notificationRetriedFor = track.key
        cancelArtwork()
        emit()
    }
    private fun cancelArtwork() {
        artworkJob?.cancel()
        artworkJob = null
        artworkRequest = null
        artworkRetryAfter = 0L
    }
    private var emptyScheduled = false
    private val emptyPlayback = Runnable {
        emptyScheduled = false
        cancelArtwork()
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
        cancelArtwork()
        artworkOwner = null
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
        val incoming = list.filter { it.packageName in allowed }
        val retained = controllers.associateBy { it.sessionToken }
        val tokens = incoming.map { it.sessionToken }.toSet()
        controllers.filter { it.sessionToken !in tokens }.forEach { it.unregisterCallback(callback) }
        controllers.clear()
        incoming.distinctBy { it.sessionToken }.forEach { controller ->
            val existing = retained[controller.sessionToken]
            controllers += existing ?: controller.also { it.registerCallback(callback, handler) }
        }
        select(incoming)
    }

    private fun select(fresh: List<MediaController>? = null) {
        val playing = controllers.filter { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        selected =
            controllers.firstOrNull { preferred.isNotBlank() && it.packageName == preferred }
                ?: playing.firstOrNull { it.packageName == "com.spotify.music" }
                ?: playing.maxByOrNull { it.playbackState?.lastPositionUpdateTime ?: 0 }
                // Pausing a player must retain its song/position rather than jump to an older
                // paused Spotify session. Another playing app still wins above.
                ?: controllers.firstOrNull { it.sessionToken == selected?.sessionToken }
                ?: controllers.maxByOrNull { it.playbackState?.lastPositionUpdateTime ?: 0 }
        emit(fresh?.firstOrNull { it.sessionToken == selected?.sessionToken }?.metadata)
    }

    private fun emit(metadataOverride: MediaMetadata? = null) {
        val c =
            selected
                ?: run {
                    scheduleEmptyPlayback()
                    return
                }
        val m = metadataOverride ?: c.metadata
        val p = c.playbackState
        val t =
            PlayerMetadata.track(c.packageName, m)
                ?: run {
                    scheduleEmptyPlayback()
                    return
                }
        // ART can be a small thumbnail even when ALBUM_ART holds the full cover.
        cancelEmptyPlayback()
        if (artworkOwner != c.sessionToken) {
            cancelArtwork()
            artworkMemory.clear()
            artworkOwner = c.sessionToken
        }
        val artwork = artworkMemory.select(t.key, PlayerMetadata.artwork(m))
        hasArtwork = artwork != null
        recoverArtwork(c, t, artwork, m, metadataOverride != null)
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

    private fun recoverArtwork(c: MediaController, track: Track, image: android.graphics.Bitmap?, metadata: MediaMetadata?, allowRepeat: Boolean) {
        if (image != null) {
            cancelArtwork()
            return
        }
        val request = Triple(c.sessionToken, track.key, PlayerMetadata.artworkUris(metadata))
        if (request == artworkRequest &&
            (!allowRepeat || artworkJob?.isActive == true || SystemClock.elapsedRealtime() < artworkRetryAfter)) return
        cancelArtwork()
        artworkRequest = request
        artworkJob = artworkScope.launch {
            for (wait in listOf(0L, 700L, 2000L, 5000L, 12000L)) {
                delay(wait)
                if (selected?.sessionToken != request.first) return@launch
                // A retained controller can cache metadata if a player misses its callback.
                val snapshot = MediaController(context, c.sessionToken).metadata
                if (PlayerMetadata.track(c.packageName, snapshot)?.key != track.key) return@launch
                suspend fun attempt(block: suspend () -> android.graphics.Bitmap?): android.graphics.Bitmap? = try {
                    block()?.takeIf(ArtworkQuality::usable)
                } catch (e: CancellationException) { throw e }
                  catch (_: Exception) { null }
                val recovered = PlayerMetadata.artwork(snapshot) ?: attempt {
                    withContext(Dispatchers.IO) {
                        val icon = MusicNotificationListener.coverIcon(c.packageName, track.title, c.sessionToken)
                        val drawable = icon?.loadDrawable(context)
                        if (drawable is android.graphics.drawable.BitmapDrawable) drawable.bitmap
                        else drawable?.toBitmap(512, 512)
                    }
                } ?: attempt {
                    val uris = PlayerMetadata.artworkUris(snapshot)
                    if (loadArtwork != null) loadArtwork.invoke(uris)
                    else ArtworkLoader(context, cacheNamespace = track.key).load(uris)
                }
                    ?: attempt { alternativeArtwork(track) }
                ensureActive()
                if (artworkRequest != request || selected?.sessionToken != request.first ||
                    PlayerMetadata.track(c.packageName, c.metadata)?.key != track.key) return@launch
                if (recovered != null) {
                    artworkMemory.select(track.key, recovered)
                    emit(snapshot) // Re-read position; never publish an old Playback snapshot.
                    return@launch
                }
            }
            // The existing foreground refresh checks this cooldown. No perpetual timer is added.
            artworkRetryAfter = SystemClock.elapsedRealtime() + 30_000L
        }
    }
}
