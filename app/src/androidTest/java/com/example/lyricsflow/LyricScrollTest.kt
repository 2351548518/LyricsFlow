package com.example.lyricsflow

import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.lyricsflow.core.*
import com.example.lyricsflow.ui.FlowApp
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LyricScrollTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun state(graph: AppGraph): MutableStateFlow<ScreenState> {
        @Suppress("UNCHECKED_CAST")
        return AppGraph::class
            .java
            .getDeclaredField("mutable")
            .apply { isAccessible = true }
            .get(graph) as MutableStateFlow<ScreenState>
    }

    @Test
    fun followsNewLineAndSeekButRespectsManualBrowsing() {
        val graph = AppGraph(compose.activity.application)
        val state = state(graph)
        try {
            compose.activityRule.scenario.onActivity {
                state.value =
                    ScreenState(
                        demo = true,
                        playback = Playback(Track("滚动测试", duration = 100000), position = 20000),
                        lyrics =
                            Lyrics(
                                (0 until 50).map { i ->
                                    Line(i * 2000L, (i + 1) * 2000L, "流动的第 $i 句歌词", "对应的第 $i 句翻译")
                                },
                                Provider.LOCAL,
                            ),
                        prefs = Preferences(fontSize = 25f),
                    )
                it.setContent { FlowApp(graph) }
            }
            compose.waitForIdle()
            fun assertAnchor(index: Int) {
                val stage =
                    compose.onNodeWithContentDescription("同步歌词").fetchSemanticsNode().boundsInRoot
                val line =
                    compose
                        .onNodeWithText("流动的第 $index 句歌词")
                        .assertIsDisplayed()
                        .fetchSemanticsNode()
                        .boundsInRoot
                val expected = stage.top + stage.height * .26f
                assertTrue(
                    "Current line should settle at anchor: ${line.top}, expected $expected",
                    line.top in expected..(expected + 100f),
                )
            }
            assertAnchor(10)
            compose.runOnIdle {
                state.value =
                    state.value.copy(playback = state.value.playback.copy(position = 40000))
            }
            compose.waitForIdle()
            assertAnchor(20)
            compose.onNodeWithContentDescription("同步歌词").performTouchInput { swipeUp() }
            compose.onNodeWithText("回到当前歌词").assertExists()
            compose.runOnIdle {
                state.value =
                    state.value.copy(playback = state.value.playback.copy(position = 44000))
            }
            compose.onNodeWithText("回到当前歌词").assertExists().performClick()
            compose.waitForIdle()
            assertAnchor(22)
        } finally {
            graph.scope.cancel()
        }
    }

    @Test
    fun wordColorsStillAdvanceAfterCachedOutlineRendering() {
        val graph = AppGraph(compose.activity.application)
        val state = state(graph)
        try {
            compose.activityRule.scenario.onActivity {
                state.value =
                    ScreenState(
                        demo = true,
                        playback = Playback(Track("逐字测试", duration = 10000), position = 1500),
                        lyrics =
                            Lyrics(
                                listOf(
                                    Line(
                                        0,
                                        10000,
                                        "Hello world",
                                        words =
                                            listOf(
                                                Word("Hello ", 1000, 2000),
                                                Word("world", 2000, 3000),
                                            ),
                                    )
                                ),
                                Provider.LOCAL,
                            ),
                        prefs = Preferences(fontSize = 25f),
                    )
                it.setContent { FlowApp(graph) }
            }
            fun alpha(index: Int) =
                compose
                    .onNodeWithText("Hello world")
                    .fetchSemanticsNode()
                    .config[SemanticsProperties.Text]
                    .single()
                    .spanStyles[index]
                    .item
                    .color
                    .alpha
            compose.waitForIdle()
            assertEquals(1f, alpha(0))
            assertTrue(alpha(1) < 1f)
            compose.runOnIdle {
                state.value =
                    state.value.copy(playback = state.value.playback.copy(position = 2500))
            }
            compose.waitForIdle()
            assertEquals(1f, alpha(1))
            compose.runOnIdle {
                state.value = state.value.copy(playback = state.value.playback.copy(position = 500))
            }
            compose.waitForIdle()
            assertTrue(alpha(0) < 1f)
            compose.runOnIdle {
                state.value =
                    state.value.copy(playback = state.value.playback.copy(positionKnown = false))
            }
            compose.waitForIdle()
            assertTrue(
                compose
                    .onNodeWithText("Hello world")
                    .fetchSemanticsNode()
                    .config[SemanticsProperties.Text]
                    .single()
                    .spanStyles
                    .isEmpty()
            )
        } finally {
            graph.scope.cancel()
        }
    }
}
