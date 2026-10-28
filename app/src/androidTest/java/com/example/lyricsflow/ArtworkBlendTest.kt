package com.example.lyricsflow

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.example.lyricsflow.core.*
import com.example.lyricsflow.ui.*
import java.io.File
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ArtworkBlendTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun brightArtworkMeetsSurroundingsWithoutASeamAndKeepsItsCentre() {
        val background = 0xff526375.toInt()
        val white = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(-1) }
        val state =
            ScreenState(
                playback = Playback(artwork = white),
                prefs = Preferences(background = "cover", coverBlur = 0f),
            )
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                Box(Modifier.size(300.dp, 360.dp).background(Color(background)).testTag("blend")) {
                    CompositionLocalProvider(LocalCoverAverage provides -1) {
                        LyricBackground(state) {}
                    }
                }
            }
        }
        val image = compose.onNodeWithTag("blend").captureToImage().asAndroidBitmap()
        fun distance(a: Int, b: Int): Int =
            (0..2).maxOf { c ->
                kotlin.math.abs((a ushr (c * 8) and 255) - (b ushr (c * 8) and 255))
            }
        for (x in listOf(0, image.width / 4, image.width / 2, image.width - 1)) {
            assertTrue(
                "Top edge must reveal the same page",
                distance(background, image.getPixel(x, 0)) <= 3,
            )
            assertTrue(
                "Bottom edge must reveal the same page",
                distance(background, image.getPixel(x, image.height - 1)) <= 3,
            )
        }
        assertEquals(
            "No white veil or tint over the centre",
            -1,
            image.getPixel(image.width / 2, image.height / 2),
        )
        val column = IntArray(image.height / 3) { image.getPixel(image.width / 2, it) }
        assertTrue(
            "Feather has no abrupt stripe",
            column.asList().zipWithNext().maxOf { (a, b) -> distance(a, b) } <= 8,
        )
    }

    @Test
    fun whiteColourfulAndDarkCoversHaveReadableFullBleedHomePreviews() {
        val graph = AppGraph(compose.activity.application)
        @Suppress("UNCHECKED_CAST")
        val state =
            AppGraph::class
                .java
                .getDeclaredField("mutable")
                .apply { isAccessible = true }
                .get(graph) as MutableStateFlow<ScreenState>
        try {
            for (variant in 0..3) {
                val cover = fixture(variant)
                val title = listOf("柔光与花", "绿色回声", "夜色流动", "暖色留白")[variant]
                compose.activityRule.scenario.onActivity { activity ->
                    state.value =
                        ScreenState(
                            demo = true,
                            playback =
                                Playback(
                                    track = Track(title, "LyricsFlow", duration = 90000),
                                    artwork = cover,
                                    position = 15000,
                                ),
                            lyrics =
                                Lyrics(
                                    listOf(
                                        Line(
                                            0,
                                            10000,
                                            "微光沿着水面流淌",
                                            "Let the light flow across the water",
                                        ),
                                        Line(
                                            10000,
                                            20000,
                                            "让颜色慢慢融入远方",
                                            "Let the colours blend into the distance",
                                        ),
                                        Line(20000, 30000, "每一句都清晰可见", "Every word stays clear"),
                                        Line(30000, 40000, "再留一点温柔给夜晚"),
                                    ),
                                    Provider.LOCAL,
                                ),
                            prefs =
                                graph.state.value.prefs.copy(
                                    palette = 4,
                                    coverMesh = true,
                                    reduced = true,
                                    background = "cover",
                                    coverBlur = 8f,
                                    dark = "light",
                                    fontSize = 25f,
                                    free = false,
                                ),
                        )
                    activity.setContent { FlowApp(graph) }
                }
                compose.onNodeWithTag("track-title").assertIsDisplayed()
                compose.onNodeWithText("让颜色慢慢融入远方").assertIsDisplayed()
                // Allow asynchronous bitmap sampling to finish; animations are disabled for
                // snapshots.
                Thread.sleep(700)
                compose.waitForIdle()
                val image = compose.onRoot().captureToImage().asAndroidBitmap()
                File(compose.activity.externalCacheDir, "artwork-blend-$variant.png")
                    .outputStream()
                    .use {
                        image.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
            }
        } finally {
            graph.scope.cancel()
        }
    }

    private fun fixture(variant: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(
            listOf(-1, 0xff70bd28.toInt(), 0xff24242a.toInt(), 0xfffff5ed.toInt())[variant]
        )
        val colors =
            listOf(
                listOf(0xffa2bfa5.toInt(), 0xffb1a3d5.toInt(), 0xffd6c85b.toInt()),
                listOf(0xff422b59.toInt(), 0xff262235.toInt(), 0xff9f7caf.toInt()),
                listOf(0xff444751.toInt(), 0xff777581.toInt(), 0xff111116.toInt()),
                listOf(0xffb97986.toInt(), 0xff9b7764.toInt(), 0xffdec2aa.toInt()),
            )[variant]
        colors.forEachIndexed { i, color ->
            paint.color = color
            canvas.drawCircle(110f + i * 80f, 190f + (i % 2) * 60f, 72f, paint)
        }
        paint.color = colors[0]
        canvas.drawRect(85f, 285f, 320f, 340f, paint)
        return bitmap
    }
}
