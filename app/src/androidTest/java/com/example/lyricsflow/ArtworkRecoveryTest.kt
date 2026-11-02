package com.example.lyricsflow

import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.SystemClock
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.lyricsflow.core.Playback
import com.example.lyricsflow.playback.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ArtworkRecoveryTest {
    @Test fun cancellationDoesNotContinueToNextArtworkSource() = runBlocking {
        val attempted = mutableListOf<String>()
        val loader = ArtworkLoader(context) { uri ->
            attempted += uri
            throw CancellationException("Artwork request cancelled")
        }
        try {
            loader.load(listOf("https://example.com/first", "https://example.com/second"))
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertEquals(listOf("https://example.com/first"), attempted)
        }
    }
    @Test fun diskCacheRetainsOnlyValidImagesAndSeparatesTracks() = runBlocking {
        val file = File(context.cacheDir, "updates/artwork-cache-fixture.png")
        file.parentFile!!.mkdirs()
        val uri = FileProvider.getUriForFile(context, context.packageName + ".updates", file).toString()
        fun write(color: Int) {
            Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }.let { image ->
                file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
            }
        }
        val blue = 0xff125ec9.toInt()
        val green = 0xff12a94e.toInt()
        val cached = listOf("cache-track-a", "cache-track-b").map {
            File(context.cacheDir, "covers/${com.example.lyricsflow.core.digest("$it|$uri")}")
        }
        cached.forEach { it.delete() }
        try {
            write(blue)
            assertEquals(blue, ArtworkLoader(context, "cache-track-a").load(listOf(uri))!!.getPixel(1, 1))
            write(0xffaaaaaa.toInt())
            assertNull(ArtworkLoader(context, "cache-track-b").load(listOf(uri)))
            write(green)
            assertEquals(green, ArtworkLoader(context, "cache-track-b").load(listOf(uri))!!.getPixel(1, 1))
            file.delete()
            assertEquals(blue, ArtworkLoader(context, "cache-track-a").load(listOf(uri))!!.getPixel(1, 1))
        } finally { file.delete(); cached.forEach { it.delete() } }
    }
    @Test fun catalogueCoverLookupsUseMatchingRecordingOnly() = runBlocking {
        val store = com.example.lyricsflow.data.Store(context)
        val api = com.example.lyricsflow.data.MusicApi(store)
        val current = com.example.lyricsflow.core.Track("Cover catalogue fixture", "Fixture artist", "Fixture album", 123000)
        val query = java.net.URLEncoder.encode("${current.title} ${current.artist}", "UTF-8")
        val netSearch = "https://music.163.com/api/search/get/web?s=$query&type=1&limit=30&offset=0"
        val netDetail = "https://music.163.com/api/song/detail/?id=42&ids=%5B42%5D"
        val qqSearch = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?w=$query&format=json&n=30&p=1&cr=1&t=0&g_tk=5381"
        val body = org.json.JSONObject()
            .put("comm", org.json.JSONObject().put("ct", 24).put("cv", 0).put("format", "json"))
            .put("detail", org.json.JSONObject().put("module", "music.pf_song_detail_svr")
                .put("method", "get_song_detail_yqq").put("param", org.json.JSONObject()
                    .put("song_mid", "001abcDEFghi12").put("song_id", 0).put("song_type", 0)))
        val qqKey = com.example.lyricsflow.core.digest("https://u.y.qq.com/cgi-bin/musicu.fcg" + body.toString())
        val keys = listOf(com.example.lyricsflow.core.digest(netSearch), com.example.lyricsflow.core.digest(netDetail),
            com.example.lyricsflow.core.digest(qqSearch), qqKey)
        fun net(artist: String) = """{"code":200,"result":{"songs":[{"name":"${current.title}","id":42,"duration":123000,"artists":[{"name":"$artist"}],"album":{"name":"Fixture album"}}]}}"""
        try {
            store.put("cache", keys[0], net(current.artist))
            store.put("cache", keys[1], """{"songs":[{"album":{"picUrl":"http://p1.music.126.net/fixture.jpg"}}]}""")
            assertEquals(listOf("https://p1.music.126.net/fixture.jpg"), api.artworkUrls(current, com.example.lyricsflow.core.Provider.NETEASE))
            store.put("cache", keys[0], net("Different artist"))
            assertTrue(api.artworkUrls(current, com.example.lyricsflow.core.Provider.NETEASE).isEmpty())
            store.put("cache", keys[2], """{"code":0,"data":{"song":{"totalnum":1,"list":[{"title":"${current.title}","id":42,"mid":"001abcDEFghi12","interval":123,"singer":[{"name":"Fixture artist"}],"album":{"name":"Fixture album"}}]}}}""")
            store.put("cache", qqKey, """{"code":0,"detail":{"code":0,"data":{"track_info":{"album":{"mid":"004abcDEFghi12"}}}}}""")
            assertEquals(listOf("https://y.gtimg.cn/music/photo_new/T002R800x800M000004abcDEFghi12.jpg"),
                api.artworkUrls(current, com.example.lyricsflow.core.Provider.QQ))
        } finally { keys.forEach { store.remove("cache", it) }; store.close() }
    }
    @Test fun rejectsGrayAndTransparentPlaceholdersButKeepsMonochromeArt() {
        val image = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        assertFalse(ArtworkQuality.usable(image))
        image.eraseColor(0xffaaaaaa.toInt())
        assertFalse(ArtworkQuality.usable(image))
        for (y in 0 until 64) for (x in 0 until 64) {
            val light = x * 255 / 63
            image.setPixel(x, y, android.graphics.Color.rgb(light, light, light))
        }
        assertTrue("Real grayscale detail is valid", ArtworkQuality.usable(image))
        image.eraseColor(0xff125ec9.toInt())
        assertTrue("Bitmap generation changes invalidate quality results", ArtworkQuality.usable(image))
    }

    @Test fun largeGrayImageCannotReplaceSmallValidCover() {
        val good = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff125ec9.toInt()) }
        val gray = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888).apply { eraseColor(0xffaaaaaa.toInt()) }
        val memory = ArtworkMemory()
        assertSame(good, memory.select("same", good))
        assertSame(good, memory.select("same", gray))
        assertNull(memory.select("other", gray))
        val metadata = MediaMetadata.Builder().putBitmap(MediaMetadata.METADATA_KEY_ART, good)
            .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, gray).build()
        assertSame(good, PlayerMetadata.artwork(metadata))
    }

    @Test fun graySourcesContinueUntilAlternativeProviderReturnsValidCover() {
        val gray = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).apply { eraseColor(0xffaaaaaa.toInt()) }
        val good = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff125ec9.toInt()) }
        val latest = AtomicReference(Playback())
        val alternatives = AtomicInteger()
        lateinit var session: MediaSession
        lateinit var monitor: PlaybackMonitor
        instrumentation.runOnMainSync {
            session = MediaSession(context, "Gray artwork fixture")
            session.setMetadata(metadata("Gray fixture", gray)); session.isActive = true
            monitor = PlaybackMonitor(context, setOf(context.packageName), loadArtwork = { gray },
                alternativeArtwork = { alternatives.incrementAndGet(); good }) { latest.set(it) }
            monitor.connect()
        }
        try {
            await { latest.get().artwork != null }
            assertEquals(64, latest.get().artwork?.width)
            assertEquals(1, alternatives.get())
            instrumentation.runOnMainSync { repeat(5) { monitor.refresh() } }
            assertEquals(1, alternatives.get())
        } finally { instrumentation.runOnMainSync { monitor.disconnect(); session.release() } }
    }

    @Test fun grayDecodedImageDoesNotStopAtFirstUri() = runBlocking {
        fun bytes(color: Int) = java.io.ByteArrayOutputStream().use { output ->
            Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }.let {
                it.compress(Bitmap.CompressFormat.PNG, 100, output); it.recycle()
            }
            output.toByteArray()
        }
        val gray = bytes(0xffaaaaaa.toInt())
        val good = bytes(0xff125ec9.toInt())
        val requested = mutableListOf<String>()
        val image = ArtworkLoader(context) { uri -> requested += uri; if (uri.endsWith("gray")) gray else good }
            .load(listOf("https://example.com/gray", "https://example.com/good"))
        assertNotNull(image)
        assertEquals(2, requested.size)
        assertTrue(ArtworkQuality.usable(image!!))
    }
    @Test fun explicitRetryRestartsSameUriImmediately() {
        val latest = AtomicReference(Playback())
        val attempts = AtomicInteger()
        val ready = java.util.concurrent.atomic.AtomicBoolean(false)
        val cover = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff125ec9.toInt()) }
        lateinit var session: MediaSession
        lateinit var monitor: PlaybackMonitor
        instrumentation.runOnMainSync {
            session = MediaSession(context, "Manual artwork retry fixture")
            session.setMetadata(metadata("Manual retry")); session.isActive = true
            monitor = PlaybackMonitor(context, setOf(context.packageName), loadArtwork = {
                attempts.incrementAndGet()
                if (ready.get()) cover else null
            }) { latest.set(it) }
            monitor.connect()
        }
        try {
            await { attempts.get() >= 1 }
            ready.set(true)
            instrumentation.runOnMainSync { monitor.retryArtwork() }
            await { latest.get().artwork != null }
            assertEquals("Manual retry", latest.get().track?.title)
        } finally { instrumentation.runOnMainSync { monitor.disconnect(); session.release() } }
    }
    @Test fun notificationBitmapRecoversWithoutNetworkAndIgnoresWrongTitle() {
        val latest = AtomicReference(Playback())
        lateinit var session: MediaSession
        lateinit var monitor: PlaybackMonitor
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS").close()
        manager.createNotificationChannel(android.app.NotificationChannel("artwork-test", "Artwork QA", android.app.NotificationManager.IMPORTANCE_LOW))
        fun post(title: String) {
            val bitmap = Bitmap.createBitmap(140, 140, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff125ec9.toInt()) }
            manager.notify(28451, android.app.Notification.Builder(context, "artwork-test")
                .setSmallIcon(R.drawable.ic_flow).setContentTitle(title).setLargeIcon(bitmap)
                .setStyle(android.app.Notification.MediaStyle().setMediaSession(session.sessionToken)).build())
        }
        instrumentation.runOnMainSync {
            session = MediaSession(context, "Notification artwork fixture")
            session.setMetadata(metadata("Notification fixture")); session.isActive = true
            post("Different track")
            monitor = PlaybackMonitor(context, setOf(context.packageName), loadArtwork = { null }) { latest.set(it) }
            monitor.connect()
        }
        try {
            await { latest.get().track != null }
            Thread.sleep(200)
            assertNull(latest.get().artwork)
            instrumentation.runOnMainSync { post("Notification fixture") }
            await { latest.get().artwork != null }
            assertTrue(latest.get().artwork!!.width in 1..140) // Android may scale notification icons.
        } finally {
            instrumentation.runOnMainSync { monitor.disconnect(); session.release() }
            manager.cancel(28451)
        }
    }
    @Test fun spotifyProviderFailureFallsBackToExactImageIdOnOfficialCdn() = runBlocking {
        val id = "ab67616d0000b2730267461fc0a66551d454fa22"
        val uri = "content://com.spotify.mobile.android.mediaapi/spotify%3Aimage%3A$id?transformation=NONE"
        val cdn = "https://i.scdn.co/image/$id"
        assertEquals(cdn, ArtworkLoader.spotifyImageUrl(uri))
        assertNull(ArtworkLoader.spotifyImageUrl(uri.replace("mediaapi", "untrusted")))
        assertNull(ArtworkLoader.spotifyImageUrl(uri.substringBefore('?') + "/extra"))
        assertEquals(cdn, ArtworkLoader.spotifyImageUrl(uri + "&redirect=other")) // Only the ID is used.
        assertNull(ArtworkLoader.spotifyImageUrl(uri.replace(id, "invalid")))
        val bytes = java.io.ByteArrayOutputStream().use { output ->
            Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff125ec9.toInt()) }.let { image ->
                image.compress(Bitmap.CompressFormat.PNG, 100, output); image.recycle()
            }
            output.toByteArray()
        }
        val requested = mutableListOf<String>()
        val loader = ArtworkLoader(context) { source ->
            requested += source
            if (source == uri) throw java.io.FileNotFoundException()
            check(source == cdn)
            bytes
        }
        assertEquals(300, loader.load(listOf(uri))?.width)
        assertEquals(listOf(uri, cdn), requested)
    }
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun metadata(title: String, bitmap: Bitmap? = null) = MediaMetadata.Builder()
        .putString(MediaMetadata.METADATA_KEY_TITLE, title)
        .putString(MediaMetadata.METADATA_KEY_ARTIST, "Artwork fixture")
        .putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, "https://example.com/cover.png")
        .apply { if (bitmap != null) putBitmap(MediaMetadata.METADATA_KEY_ART, bitmap) }.build()
    private fun await(timeout: Long = 6000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!condition() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(30)
        assertTrue(condition())
    }

    @Test fun retriesMissingUriCoverAndPublishesWithoutResettingPosition() {
        val latest = AtomicReference(Playback())
        val attempts = AtomicInteger()
        val cover = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff125ec9.toInt()) }
        lateinit var session: MediaSession
        lateinit var monitor: PlaybackMonitor
        instrumentation.runOnMainSync {
            session = MediaSession(context, "Artwork recovery fixture")
            session.setMetadata(metadata("Retry"))
            session.setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_PAUSED, 22000, 1f).build())
            session.isActive = true
            monitor = PlaybackMonitor(context, setOf(context.packageName), loadArtwork = {
                if (attempts.incrementAndGet() == 1) null else cover
            }) { latest.set(it) }
            monitor.connect()
        }
        try {
            await { latest.get().artwork != null }
            assertEquals(2, attempts.get())
            assertEquals("Retry", latest.get().track?.title)
            assertEquals(22000L, latest.get().position)
            instrumentation.runOnMainSync { repeat(5) { monitor.refresh() } }
            assertEquals(2, attempts.get())
        } finally { instrumentation.runOnMainSync { monitor.disconnect(); session.release() } }
    }

    @Test fun lateOldCoverCannotReplaceNewTrackCover() {
        val latest = AtomicReference(Playback())
        val started = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val old = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff125ec9.toInt()) }
        val next = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff125ec9.toInt()) }
        lateinit var session: MediaSession
        lateinit var monitor: PlaybackMonitor
        instrumentation.runOnMainSync {
            session = MediaSession(context, "Artwork stale fixture")
            session.setMetadata(metadata("Old")); session.isActive = true
            monitor = PlaybackMonitor(context, setOf(context.packageName), loadArtwork = {
                started.incrementAndGet()
                withContext(NonCancellable) { gate.await() }
                old
            }) { latest.set(it) }
            monitor.connect()
        }
        try {
            await { started.get() > 0 }
            instrumentation.runOnMainSync { session.setMetadata(metadata("Next", next)) }
            await { latest.get().track?.title == "Next" && latest.get().artwork?.width == 256 }
            gate.complete(Unit)
            Thread.sleep(200)
            assertEquals("Next", latest.get().track?.title)
            assertEquals(256, latest.get().artwork?.width)
        } finally {
            gate.complete(Unit)
            instrumentation.runOnMainSync { monitor.disconnect(); session.release() }
        }
    }

    @Test fun failedBudgetStopsUntilArtworkUriChanges() {
        val attempts = AtomicInteger()
        val latest = AtomicReference(Playback())
        val cover = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff125ec9.toInt()) }
        lateinit var session: MediaSession
        lateinit var monitor: PlaybackMonitor
        instrumentation.runOnMainSync {
            session = MediaSession(context, "Artwork retry budget fixture")
            session.setMetadata(metadata("Budget")); session.isActive = true
            monitor = PlaybackMonitor(context, setOf(context.packageName), loadArtwork = { uris ->
                attempts.incrementAndGet()
                if (uris.any { it.endsWith("ready.png") }) cover else null
            }) { latest.set(it) }
            monitor.connect()
        }
        try {
            await(25000) { attempts.get() == 5 }
            instrumentation.runOnMainSync { repeat(5) { monitor.refresh() } }
            Thread.sleep(900)
            assertEquals(5, attempts.get())
            instrumentation.runOnMainSync {
                session.setMetadata(MediaMetadata.Builder(metadata("Budget"))
                    .putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, "https://example.com/ready.png").build())
            }
            // Emulate the existing periodic refresh if a URI-only callback is suppressed.
            instrumentation.runOnMainSync { monitor.refresh() }
            await { latest.get().artwork != null }
            assertEquals(6, attempts.get())
        } finally { instrumentation.runOnMainSync { monitor.disconnect(); session.release() } }
    }

    @Test fun readsContentUriAndRejectsUnsupportedSchemes() = runBlocking {
        val file = File(context.cacheDir, "updates/artwork-fixture.png")
        file.parentFile!!.mkdirs()
        Bitmap.createBitmap(2400, 1200, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff125ec9.toInt()) }.let { image ->
            file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
        try {
            val uri = FileProvider.getUriForFile(context, context.packageName + ".updates", file)
            val loaded = ArtworkLoader(context).load(listOf(uri.toString()))!!
            assertTrue(loaded.width <= 1536 && loaded.height <= 1536)
            assertNull(ArtworkLoader(context).load(listOf("file:///not-readable.png", "http://example.com/a.png")))
            file.writeText("invalid image")
            assertNull(ArtworkLoader(context).load(listOf(uri.toString())))
            file.writeBytes(ByteArray(8_000_001))
            assertNull(ArtworkLoader(context).load(listOf(uri.toString())))
        } finally { file.delete() }
    }

    @Test fun uriMetadataPrioritizesAlbumArtAndDeduplicates() {
        val metadata = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, "content://fixture/album")
            .putString(MediaMetadata.METADATA_KEY_ART_URI, "content://fixture/album")
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI, "content://fixture/icon").build()
        assertEquals(listOf("content://fixture/album", "content://fixture/icon"), PlayerMetadata.artworkUris(metadata))
    }
}
