package com.example.lyricsflow

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import com.example.lyricsflow.core.*
import com.example.lyricsflow.ui.*
import java.io.File
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class FlowAppearanceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun repeatedMetadataBitmapsNeverResetTheDisplayedColor() {
        val green =
            Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply {
                eraseColor(0xff315144.toInt())
            }
        val red =
            Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply {
                eraseColor(0xff692c44.toInt())
            }
        var image by mutableStateOf<Bitmap?>(green)
        val frames = java.util.Collections.synchronizedList(mutableListOf<Int?>())
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                val average = rememberCoverAverage(image)
                SideEffect { frames.add(average) }
                Box(Modifier.fillMaxSize())
            }
        }
        compose.waitUntil(5000) { frames.lastOrNull() == 0xff315144.toInt() }
        frames.clear()
        repeat(4) {
            compose.runOnIdle { image = green.copy(Bitmap.Config.ARGB_8888, false) }
            compose.waitForIdle()
        }
        compose.runOnIdle { image = null }
        compose.waitForIdle()
        compose.runOnIdle { image = red }
        compose.waitUntil(5000) { frames.lastOrNull() == 0xff692c44.toInt() }
        assertTrue(
            "A metadata refresh must not render fallback: $frames",
            frames.none { it == null },
        )
        compose.runOnIdle { image = null }
        compose.waitUntil(5000) { frames.lastOrNull() == null }
    }

    @Test
    fun coverAverageIsExactCachedAndHandlesTransparentPixels(): Unit = runBlocking {
        for (seed in
            listOf(
                0xff315144.toInt(),
                0xff694069.toInt(),
                0xff808080.toInt(),
                -1,
                0xff000000.toInt(),
            )) {
            val palette = flowPalette(4, seed, false)
            val bg = palette.background
            assertTrue(
                "Cover hue must not become a nearly white page",
                kotlin.math.abs(bg.red * 255 - (seed ushr 16 and 255)) <= 16,
            )
            assertTrue(
                com.example.lyricsflow.core.CoverColors.contrast(
                    palette.ink.toArgb(),
                    bg.toArgb(),
                ) >= 4.5
            )
        }
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(
            intArrayOf(0xffff0000.toInt(), 0xff00ff00.toInt(), 0xff0000ff.toInt(), 0),
            0,
            2,
            0,
            0,
            2,
            2,
        )
        assertEquals(0xff555555.toInt(), averageCover(bitmap))
        bitmap.eraseColor(0xff123456.toInt())
        assertEquals(0xff123456.toInt(), averageCover(bitmap))
        assertSame(bitmap, blurCover(bitmap, 0f))
        assertEquals(0xff123456.toInt(), averageCover(blurCover(bitmap, 6f)!!))
        val split =
            Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply {
                setPixels(
                    IntArray(256) { if (it % 16 < 8) 0xff000000.toInt() else -1 },
                    0,
                    16,
                    0,
                    0,
                    16,
                    16,
                )
            }
        val softened = blurCover(split, 5f)!!
        assertTrue((softened.getPixel(7, 8) and 255) > 0)
        assertTrue((softened.getPixel(8, 8) and 255) < 255)
        split.recycle()
        softened.recycle()
        bitmap.eraseColor(0)
        assertNull(averageCover(bitmap))
        val large = Bitmap.createBitmap(2048, 2048, Bitmap.Config.ARGB_8888)
        large.eraseColor(0xff336699.toInt())
        val begin = SystemClock.elapsedRealtimeNanos()
        assertEquals(0xff336699.toInt(), averageCover(large))
        val scan = (SystemClock.elapsedRealtimeNanos() - begin) / 1_000_000.0
        val cached = SystemClock.elapsedRealtimeNanos()
        repeat(30) { assertEquals(0xff336699.toInt(), averageCover(large)) }
        val cache = (SystemClock.elapsedRealtimeNanos() - cached) / 30_000_000.0
        instrumentation.sendStatus(
            2,
            Bundle().apply {
                putString("cover-timing", "2048x2048 full scan=${scan}ms; cached mean=${cache}ms")
            },
        )
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            val hardware = large.copy(Bitmap.Config.HARDWARE, false)
            assertEquals(0xff336699.toInt(), averageCover(hardware))
            val blurredHardware = blurCover(hardware, 4f)!!
            assertEquals(0xff336699.toInt(), averageCover(blurredHardware))
            blurredHardware.recycle()
            hardware.recycle()
        }
        bitmap.recycle()
        large.recycle()
        runCatching {
            val context = instrumentation.targetContext
            val manager =
                context.getSystemService(android.media.session.MediaSessionManager::class.java)
            val component =
                android.content.ComponentName(
                    context,
                    com.example.lyricsflow.playback.MusicNotificationListener::class.java,
                )
            manager
                .getActiveSessions(component)
                .filter { it.packageName == "com.spotify.music" }
                .forEach { controller ->
                    val metadata = controller.metadata
                    val details =
                        listOf(
                                android.media.MediaMetadata.METADATA_KEY_ART,
                                android.media.MediaMetadata.METADATA_KEY_ALBUM_ART,
                                android.media.MediaMetadata.METADATA_KEY_DISPLAY_ICON,
                            )
                            .mapNotNull { key ->
                                metadata?.getBitmap(key)?.let { "$key=${it.width}x${it.height}" }
                            }
                    val uriKeys =
                        listOf(
                                android.media.MediaMetadata.METADATA_KEY_ART_URI,
                                android.media.MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
                            )
                            .mapNotNull { key ->
                                metadata
                                    ?.getString(key)
                                    ?.let { android.net.Uri.parse(it) }
                                    ?.let { uri ->
                                        val dimensions = runCatching {
                                            val bounds =
                                                android.graphics.BitmapFactory.Options().apply {
                                                    inJustDecodeBounds = true
                                                }
                                            context.contentResolver.openInputStream(uri)?.use {
                                                android.graphics.BitmapFactory.decodeStream(
                                                    it,
                                                    null,
                                                    bounds,
                                                )
                                            }
                                            "${bounds.outWidth}x${bounds.outHeight}"
                                        }
                                            .getOrElse { it.javaClass.simpleName }
                                        val thumbnail =
                                            if (android.os.Build.VERSION.SDK_INT >= 29)
                                                runCatching {
                                                    context.contentResolver
                                                        .loadThumbnail(
                                                            uri,
                                                            android.util.Size(1200, 1200),
                                                            null,
                                                        )
                                                        .let { image ->
                                                            "${image.width}x${image.height}"
                                                                .also { image.recycle() }
                                                        }
                                                }
                                                    .getOrElse { it.javaClass.simpleName }
                                            else "unsupported"
                                        "$key=${uri.scheme}://${uri.host} uri-image=$dimensions typed-image=$thumbnail"
                                    }
                            }
                    instrumentation.sendStatus(
                        2,
                        Bundle().apply {
                            putString("spotify-cover", (details + uriKeys).joinToString())
                        },
                    )
                }
        }
    }

    @Test
    fun fluidHomeAndAppearanceJourney() {
        val graph = AppGraph(compose.activity.application)
        val original = graph.state.value.prefs
        val cover = fixtureCover()
        @Suppress("UNCHECKED_CAST")
        val mutable =
            AppGraph::class
                .java
                .getDeclaredField("mutable")
                .apply { isAccessible = true }
                .get(graph) as MutableStateFlow<ScreenState>
        try {
            compose.activityRule.scenario.onActivity {
                graph.preferences(
                    original.copy(
                        palette = 4,
                        background = "cover",
                        dark = "light",
                        free = false,
                        reduced = false,
                        coverBlur = 8f,
                    )
                )
                graph.preview()
                mutable.value =
                    graph.state.value.let { state ->
                        state.copy(
                            playback =
                                state.playback.copy(
                                    track =
                                        Demo.track.copy(
                                            title =
                                                "Where the water carries every word — a very long title",
                                            artist =
                                                "LyricsFlow Studio, The River Ensemble & Evening Light",
                                        ),
                                    artwork = cover,
                                )
                        )
                    }
                it.setContent { FlowApp(graph) }
            }
            compose.waitUntil(5000) {
                compose.onAllNodesWithTag("track-title").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("关于 LyricsFlow").assertDoesNotExist()
            compose.onNodeWithTag("track-title").assertIsDisplayed()
            compose.onNodeWithTag("track-artist").assertIsDisplayed()
            for (label in listOf("track-title", "track-artist")) {
                val layouts = mutableListOf<TextLayoutResult>()
                compose.onNodeWithTag(label).performSemanticsAction(
                    SemanticsActions.GetTextLayoutResult
                ) {
                    it(layouts)
                }
                assertEquals(1, layouts.single().lineCount)
            }
            compose.onNodeWithText("⌄").assertDoesNotExist()
            val titleLeft =
                compose.onNodeWithTag("track-title").getUnclippedBoundsInRoot().left.value
            val lyricLeft =
                compose
                    .onNodeWithText("Leave a little room for the light")
                    .getUnclippedBoundsInRoot()
                    .left
                    .value
            assertEquals(titleLeft, lyricLeft, .5f)
            val root = compose.onRoot().getUnclippedBoundsInRoot()
            if (root.right.value - root.left.value < 700) {
                val coverBounds =
                    compose.onNodeWithTag("lyric-art-layer").getUnclippedBoundsInRoot()
                assertEquals(root.left.value, coverBounds.left.value, .5f)
                assertEquals(root.right.value, coverBounds.right.value, .5f)
            }
            capture("home-cover-light")
            compose.activityRule.scenario.onActivity {
                graph.preferences(graph.state.value.prefs.copy(background = "color"))
            }
            capture("home-river-light")
            compose.activityRule.scenario.onActivity {
                graph.preferences(graph.state.value.prefs.copy(dark = "dark", background = "cover"))
            }
            capture("home-cover-dark")
            compose.onNodeWithText("我的", useUnmergedTree = true).performClick()
            compose.onNodeWithText("把每一次聆听，调成喜欢的样子。").assertDoesNotExist()
            compose.onNodeWithText("本地聆听者").assertIsDisplayed()
            capture("my-fluid")
            compose.onNodeWithText("个性化").performClick()
            compose.onNodeWithText("跟随封面").performScrollTo().assertIsSelected()
            compose.onNodeWithTag("cover-blur-slider").performScrollTo().performSemanticsAction(
                SemanticsActions.SetProgress
            ) {
                it(12f)
            }
            assertEquals(12f, graph.state.value.prefs.coverBlur, .01f)
            capture("appearance-cover")
            assertEquals(4, graph.state.value.prefs.palette)
            assertEquals(
                4,
                AppGraph(graph.app).also { it.scope.cancel() }.state.value.prefs.palette,
            )
            assertEquals(
                12f,
                AppGraph(graph.app).also { it.scope.cancel() }.state.value.prefs.coverBlur,
                .01f,
            )
        } finally {
            compose.activityRule.scenario.onActivity { graph.preferences(original) }
            graph.scope.cancel()
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val file = File(instrumentation.targetContext.getExternalFilesDir(null), "ui-$name.png")
        file.outputStream().use {
            compose
                .onRoot()
                .captureToImage()
                .asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun fixtureCover(): Bitmap {
        val bitmap = Bitmap.createBitmap(800, 1000, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader =
            LinearGradient(
                0f,
                0f,
                800f,
                1000f,
                intArrayOf(0xff122d45.toInt(), 0xff478c91.toInt(), 0xffdfcba6.toInt()),
                null,
                Shader.TileMode.CLAMP,
            )
        canvas.drawPaint(paint)
        paint.shader = null
        paint.color = 0xfff1dcb4.toInt()
        canvas.drawCircle(600f, 230f, 105f, paint)
        paint.color = 0xff235468.toInt()
        canvas.drawOval(-400f, 500f, 900f, 1300f, paint)
        paint.color = 0xff39818b.toInt()
        canvas.drawOval(200f, 640f, 1400f, 1600f, paint)
        return bitmap
    }
}
