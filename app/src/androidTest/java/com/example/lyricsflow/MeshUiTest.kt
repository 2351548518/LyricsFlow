package com.example.lyricsflow

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.lyricsflow.core.*
import com.example.lyricsflow.data.*
import com.example.lyricsflow.ui.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MeshUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun meshPreferenceAndBackgroundSurviveBackupRestore() {
        val graph = AppGraph(compose.activity.application)
        val previous = graph.state.value.prefs
        try {
            compose.activityRule.scenario.onActivity {
                graph.preferences(previous.copy(coverMesh = true))
                it.setContent {
                    val s by graph.state.collectAsState()
                    MaterialTheme { Appearance(s, graph, null, {}, {}, {}, {}) }
                }
            }
            compose.onNodeWithText("网状渐变").performScrollTo().performClick()
            assertEquals("mesh", graph.state.value.prefs.background)
            compose.onNodeWithText("动画网状渐变").performScrollTo().performClick()
            assertFalse(graph.state.value.prefs.coverMesh)
            val backup = Transfer.validateBackup(Transfer.backup(graph))
            assertFalse(backup.second.getBoolean("coverMesh"))
            assertEquals("mesh", backup.second.getString("background"))
            compose.runOnIdle {
                graph.preferences(previous.copy(coverMesh = true, background = "cover"))
            }
            runBlocking {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    restoreBackup(
                        graph,
                        emptyList<Triple<String, String, String>>() to backup.second,
                    )
                }
            }
            assertFalse(graph.state.value.prefs.coverMesh)
            assertEquals("mesh", graph.state.value.prefs.background)
        } finally {
            compose.runOnIdle { graph.preferences(previous) }
            graph.scope.cancel()
        }
    }

    @Test
    fun animatedMeshChangesPixelsWithoutRecomposingAndFreezesWhenPaused() {
        compose.mainClock.autoAdvance = false
        val compositions = AtomicInteger()
        var playing by mutableStateOf(true)
        val palette = flowPalette(4, 0xff35485e.toInt(), true)
        val swatches =
            listOf(0xff124c84.toInt(), 0xff543663.toInt(), 0xff89705c.toInt(), 0xff253238.toInt())
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                SideEffect { compositions.incrementAndGet() }
                Box(Modifier.fillMaxSize().testTag("mesh-preview")) {
                    CoverMesh(palette, swatches, playing, false)
                }
            }
        }
        compose.mainClock.advanceTimeBy(1600)
        compose.waitForIdle()
        fun pixels(): IntArray {
            val bitmap = compose.onNodeWithTag("mesh-preview").captureToImage().asAndroidBitmap()
            val result = IntArray(16)
            for (y in 0..3) for (x in 0..3) result[y * 4 + x] =
                bitmap.getPixel(bitmap.width * (x + 1) / 5, bitmap.height * (y + 1) / 5)
            return result
        }
        val first = pixels()
        val count = compositions.get()
        compose.mainClock.advanceTimeBy(12000)
        compose.waitForIdle()
        val later = pixels()
        assertFalse("Mesh should flow", first.contentEquals(later))
        assertEquals(
            "Animation must read state in drawing, not recompose the parent",
            count,
            compositions.get(),
        )
        compose.runOnIdle { playing = false }
        compose.mainClock.advanceTimeBy(100)
        compose.waitForIdle()
        val paused = pixels()
        compose.mainClock.advanceTimeBy(6000)
        compose.waitForIdle()
        assertArrayEquals("Paused mesh remains still", paused, pixels())
        val image = compose.onNodeWithTag("mesh-preview").captureToImage().asAndroidBitmap()
        File(compose.activity.externalCacheDir, "mesh-preview.png").outputStream().use {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
