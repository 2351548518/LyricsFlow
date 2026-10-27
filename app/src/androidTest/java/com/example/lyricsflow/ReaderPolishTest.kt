package com.example.lyricsflow

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import com.example.lyricsflow.core.*
import com.example.lyricsflow.data.*
import com.example.lyricsflow.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReaderPolishTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun currentLineIsLargerAndUnknownPositionDoesNotHighlightEveryLine() {
        val graph = AppGraph(compose.activity.application)
        @Suppress("UNCHECKED_CAST")
        val state =
            AppGraph::class
                .java
                .getDeclaredField("mutable")
                .apply { isAccessible = true }
                .get(graph) as MutableStateFlow<ScreenState>
        try {
            compose.activityRule.scenario.onActivity {
                state.value =
                    ScreenState(
                        demo = true,
                        playback =
                            Playback(
                                track = Track("阅读测试", "LyricsFlow", duration = 60000),
                                position = 10000,
                            ),
                        lyrics =
                            Lyrics(
                                listOf(
                                    Line(0, 10000, "微光浮在水面"),
                                    Line(10000, 20000, "河流把光带向远方", "让这一刻慢慢流淌"),
                                    Line(20000, 30000, "下一句仍在前方"),
                                ),
                                Provider.LOCAL,
                            ),
                        prefs =
                            graph.state.value.prefs.copy(
                                fontSize = 30f,
                                reduced = true,
                                free = false,
                                translation = true,
                            ),
                    )
                it.setContent { FlowApp(graph) }
            }
            compose.waitForIdle()
            assertTrue(
                "Current line must be visibly larger than the chosen base size",
                size("河流把光带向远方") > 33f,
            )
            assertTrue("Nearby lyrics retain the base size", size("下一句仍在前方") <= 30.1f)
            compose.runOnIdle {
                state.value =
                    state.value.copy(playback = state.value.playback.copy(positionKnown = false))
            }
            compose.waitForIdle()
            assertEquals(30f, size("河流把光带向远方"), .1f)
        } finally {
            graph.scope.cancel()
        }
    }

    @Test
    fun opacityCanReachBothEndpointsAndZeroSurvivesBackupRestore() {
        val graph = AppGraph(compose.activity.application)
        val original = graph.state.value.prefs
        try {
            compose.activityRule.scenario.onActivity { activity ->
                graph.preferences(original.copy(overlayAlpha = 0f))
                activity.setContent {
                    val s by graph.state.collectAsState()
                    MaterialTheme { Surface { PermissionSettings(s, graph) {} } }
                }
            }
            compose.onNodeWithText("背景不透明度 · 0%").performScrollTo().assertIsDisplayed()
            val slider =
                compose.onNode(
                    SemanticsMatcher("opacity slider 0 to 1") {
                        it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)?.range ==
                            0f..1f
                    }
                )
            slider.performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
            compose.onNodeWithText("背景不透明度 · 100%").assertExists()
            slider.performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
            val restoredGraph = AppGraph(compose.activity.application)
            try {
                assertEquals(0f, restoredGraph.state.value.prefs.overlayAlpha)
            } finally {
                restoredGraph.scope.cancel()
            }
            val validated = Transfer.validateBackup(Transfer.backup(graph))
            assertEquals(0.0, validated.second.getDouble("overlayAlpha"), .001)
            compose.runOnIdle { graph.preferences(original.copy(overlayAlpha = .85f)) }
            runBlocking {
                withContext(Dispatchers.Main) {
                    restoreBackup(
                        graph,
                        emptyList<Triple<String, String, String>>() to
                            JSONObject().put("overlayAlpha", 0.0),
                    )
                }
            }
            assertEquals(0f, graph.state.value.prefs.overlayAlpha)
        } finally {
            compose.runOnIdle { graph.preferences(original) }
            graph.scope.cancel()
        }
    }

    private fun size(text: String): Float {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
            it(layouts)
        }
        return layouts.single().layoutInput.style.fontSize.value
    }

    @Test
    fun colorPickerRejectsInvalidDraftAndColorSurvivesBackupRestore() {
        val graph = AppGraph(compose.activity.application)
        val original = graph.state.value.prefs
        try {
            compose.activityRule.scenario.onActivity { activity ->
                graph.preferences(original.copy(overlayColor = ""))
                activity.setContent {
                    val s by graph.state.collectAsState()
                    MaterialTheme { Surface { PermissionSettings(s, graph) {} } }
                }
            }
            compose.onNodeWithText("自定义颜色").performScrollTo().performClick()
            compose.onNode(hasSetTextAction()).performTextReplacement("#12")
            compose.onNodeWithText("应用颜色").assertIsNotEnabled()
            assertEquals("", graph.state.value.prefs.overlayColor)
            compose.onNode(hasSetTextAction()).performTextReplacement("#123abc")
            compose.onNodeWithText("应用颜色").assertIsEnabled().performClick()
            assertEquals("#123ABC", graph.state.value.prefs.overlayColor)
            val restarted = AppGraph(compose.activity.application)
            try {
                assertEquals("#123ABC", restarted.state.value.prefs.overlayColor)
            } finally {
                restarted.scope.cancel()
            }
            val backup = Transfer.validateBackup(Transfer.backup(graph))
            assertEquals("#123ABC", backup.second.getString("overlayColor"))
            compose.runOnIdle { graph.preferences(original.copy(overlayColor = "")) }
            runBlocking {
                withContext(Dispatchers.Main) {
                    restoreBackup(
                        graph,
                        emptyList<Triple<String, String, String>>() to
                            JSONObject().put("overlayColor", "#123ABC"),
                    )
                }
            }
            assertEquals("#123ABC", graph.state.value.prefs.overlayColor)
            val files = SnapshotFiles.unzip(Transfer.backup(graph)).toMutableMap()
            files["settings.json"] =
                JSONObject(String(files.getValue("settings.json"), Charsets.UTF_8))
                    .put("overlayColor", "invalid")
                    .toString()
                    .toByteArray()
            assertThrows(IllegalArgumentException::class.java) {
                Transfer.validateBackup(SnapshotFiles.zip(files))
            }
        } finally {
            compose.runOnIdle { graph.preferences(original) }
            graph.scope.cancel()
        }
    }
}
