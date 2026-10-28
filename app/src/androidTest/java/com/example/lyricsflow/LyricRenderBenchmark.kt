package com.example.lyricsflow

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.example.lyricsflow.core.*
import com.example.lyricsflow.ui.FlowApp
import java.io.File
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test

/** Fixed local song: no network, no real playback controls, no persisted preference changes. */
class LyricRenderBenchmark {
    @Test
    fun synchronizedScroll() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as LyricsFlowApp
        val graph = AppGraph(app)
        @Suppress("UNCHECKED_CAST")
        val state =
            AppGraph::class
                .java
                .getDeclaredField("mutable")
                .apply { isAccessible = true }
                .get(graph) as MutableStateFlow<ScreenState>
        val bitmap =
            Bitmap.createBitmap(384, 384, Bitmap.Config.ARGB_8888).apply {
                eraseColor(android.graphics.Color.rgb(45, 85, 120))
            }
        fun shell(command: String) =
            instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor)
                    .bufferedReader()
                    .use { it.readText() }
            }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { activity ->
                    state.value =
                        ScreenState(
                            demo = true,
                            playback =
                                Playback(
                                    Track("流动歌词性能样本", "LyricsFlow", duration = 240000),
                                    playing = true,
                                    position = 10000,
                                    updatedAt = SystemClock.elapsedRealtime(),
                                    artwork = bitmap,
                                ),
                            lyrics =
                                Lyrics(
                                    (0 until 120).map { i ->
                                        Line(
                                            i * 2000L,
                                            (i + 1) * 2000L,
                                            "光の向こうへ流れる音楽 $i",
                                            "让这一句歌词随着流水慢慢抵达远方 $i",
                                        )
                                    },
                                    Provider.LOCAL,
                                ),
                            prefs =
                                Preferences(
                                    fontSize = 25f,
                                    palette = 4,
                                    background = "cover",
                                    coverBlur = 8f,
                                ),
                        )
                    activity.setContent { FlowApp(graph) }
                }
                SystemClock.sleep(5000) // Warm text, image and shaders before measuring.
                shell("dumpsys gfxinfo com.example.lyricsflow reset")
                SystemClock.sleep(25000)
                val report = shell("dumpsys gfxinfo com.example.lyricsflow framestats")
                File(app.getExternalFilesDir(null), "lyric-render-gfxinfo.txt").writeText(report)
            } finally {
                graph.scope.cancel()
            }
        }
    }
}
