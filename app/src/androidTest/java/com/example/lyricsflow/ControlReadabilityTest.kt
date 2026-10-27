package com.example.lyricsflow

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.example.lyricsflow.core.*
import com.example.lyricsflow.ui.*
import java.io.File
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ControlReadabilityTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun brownCoverSettingsAndMatchingKeepExplicitSelection() {
        val graph = AppGraph(compose.activity.application)
        val original = graph.state.value.prefs
        val brown =
            Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply {
                eraseColor(0xff78685e.toInt())
            }
        val white = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(-1) }
        runBlocking {
            averageCover(brown)
            averageCover(white)
        }
        @Suppress("UNCHECKED_CAST")
        val state =
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
                        dark = "light",
                        fontSize = 30f,
                        free = false,
                        translation = true,
                        romanization = false,
                    )
                )
                graph.preview()
                state.value =
                    state.value.copy(playback = state.value.playback.copy(artwork = brown))
                it.setContent { FlowApp(graph) }
            }
            compose.onNodeWithText("我的", useUnmergedTree = true).performClick()
            capture("brown-my")
            compose.onNodeWithText("个性化").performClick()
            compose.onNodeWithText("经典滚动").assertIsSelected()
            compose.onNodeWithTag("selected-choice-经典滚动", useUnmergedTree = true).assertExists()
            compose.onNodeWithText("自由排版").performClick().assertIsSelected()
            compose.onNodeWithText("经典滚动").assertIsNotSelected().performClick()
            compose
                .onNodeWithText("显示中文译文")
                .performScrollTo()
                .assertIsOn()
                .performClick()
                .assertIsOff()
            assertFalse(graph.state.value.prefs.translation)
            compose.onNodeWithText("显示中文译文").performClick().assertIsOn()
            capture("brown-appearance")
            compose.onNodeWithText("跟随封面").performScrollTo().assertIsSelected()
            capture("brown-colors")
            compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithText("首页", useUnmergedTree = true).performClick()
            compose.onNodeWithText("歌曲匹配").performClick()
            compose.onNodeWithText("QQ 音乐").assertIsSelected()
            compose.onNodeWithText("网易云音乐").performClick().assertIsSelected()
            compose.onNodeWithText("QQ 音乐").assertIsNotSelected()
            capture("brown-matching")
            compose.activityRule.scenario.onActivity {
                state.value =
                    state.value.copy(playback = state.value.playback.copy(artwork = white))
            }
            compose.waitForIdle()
            capture("white-matching")
            // Empty comments deliberately use no remote song; this exercises filters without
            // network.
            compose.activityRule.scenario.onActivity { activity ->
                androidx.core.view.WindowCompat.getInsetsController(
                        activity.window,
                        activity.window.decorView,
                    )
                    .isAppearanceLightStatusBars = false
                activity.setContent {
                    val palette = flowPalette(4, 0xff78685e.toInt(), false)
                    MaterialTheme(colorScheme = flowColorScheme(palette)) {
                        Surface {
                            Box(
                                Modifier.fillMaxSize()
                                    .safeDrawingPadding()
                                    .wrapContentWidth()
                                    .widthIn(max = 840.dp)
                                    .padding(horizontal = 24.dp)
                            ) {
                                CommentsScreen(graph, null, null) {}
                            }
                        }
                    }
                }
            }
            compose.onNodeWithText("热门").assertIsSelected()
            compose.onNodeWithText("最新").performClick().assertIsSelected()
            compose.onNodeWithText("热门").assertIsNotSelected()
            capture("brown-comments-empty")
            compose.activityRule.scenario.onActivity { activity ->
                activity.setContent {
                    MaterialTheme(
                        colorScheme = flowColorScheme(flowPalette(4, 0xff78685e.toInt(), false))
                    ) {
                        Surface {
                            Column(
                                Modifier.fillMaxSize()
                                    .safeDrawingPadding()
                                    .wrapContentWidth()
                                    .widthIn(max = 840.dp)
                                    .padding(horizontal = 24.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                PageTitle("共鸣卡片", "界面验收样例 · 以下不是平台真实评论")
                                FlowCommentCard(
                                    Comment(
                                        "qa-1",
                                        "🌊 雨后聆听者",
                                        "音乐流过的时候，把文字留在这里。\n这段原创示例只用于检查长文本、行距和配色。",
                                        128,
                                        1700000000000,
                                    )
                                )
                                FlowCommentCard(
                                    Comment(
                                        "qa-2",
                                        "界面验收样例",
                                        "A little space between the notes.\n浅色与深色封面下，阅读都应该清晰、轻松。",
                                        32,
                                        1700000000000,
                                    )
                                )
                            }
                        }
                    }
                }
            }
            compose.onNodeWithText("🌊", useUnmergedTree = true).assertExists()
            capture("brown-comment-cards-sample")
        } finally {
            compose.activityRule.scenario.onActivity { graph.preferences(original) }
            graph.scope.cancel()
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        android.os.SystemClock.sleep(280)
        val file = File(compose.activity.getExternalFilesDir(null), "controls-$name.png")
        val image =
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                .uiAutomation
                .takeScreenshot() ?: error("Screenshot unavailable")
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
}
