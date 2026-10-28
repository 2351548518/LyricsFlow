package com.example.lyricsflow

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.lyricsflow.core.*
import com.example.lyricsflow.ui.SearchSheet
import java.io.File
import org.junit.Rule
import org.junit.Test

class SearchUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun lrclibIsVisibleAndKeepsItsSelectionAndRecordIdInput() {
        val graph = (compose.activity.application as LyricsFlowApp).graph
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                MaterialTheme { SearchSheet(graph, null, {}, initialSource = Provider.LRCLIB) {} }
            }
        }
        try {
            compose.onNodeWithText("LRCLIB").performScrollTo().assertIsSelected()
            compose.onNodeWithText("QQ音乐").assertExists()
            compose.onNodeWithText("网易云").assertExists()
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            compose.waitForIdle()
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(instrumentation.targetContext.externalCacheDir, "lrclib-matching.png")
                    .outputStream()
                    .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            compose.onNodeWithText("使用歌曲链接／ID 绑定").performScrollTo().performClick()
            compose.onNodeWithText("LRCLIB 记录 ID / 链接").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("QQ音乐").performScrollTo().performClick()
            compose.onNodeWithText("songmid / 歌曲链接").performScrollTo().assertIsDisplayed()
        } finally {
            compose.waitForIdle()
        }
    }

    @Test
    fun searchLoadsMoreUniqueCandidatesAndClearsStaleResults() {
        val graph = (compose.activity.application as LyricsFlowApp).graph
        val original = graph.state.value.prefs
        val settings = graph.app.getSharedPreferences("settings", 0)
        val priority = settings.getString("lyricPriority", null)
        fun displayedCount(): Int {
            val node =
                compose
                    .onAllNodesWithText("已显示", substring = true)
                    .fetchSemanticsNodes()
                    .firstOrNull()
            val text =
                node?.config?.get(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()
            return Regex("已显示 (\\d+) 首").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        }
        compose.activityRule.scenario.onActivity {
            graph.preferences(original.copy(autoTranslate = false))
            graph.live()
            graph.selectSource(Provider.QQ)
        }
        try {
            compose.onNodeWithText("搜索歌曲与真实评论").performClick()
            compose.onNodeWithText("歌曲 / 歌手").performTextReplacement("rionos")
            compose.onNodeWithText("搜索歌曲").performClick()
            compose.waitUntil(45000) {
                compose
                    .onAllNodesWithText("已显示 30 首", substring = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithText("songmid / 歌曲链接").assertDoesNotExist()
            compose.onAllNodesWithText("加载更多候选").onFirst().performScrollTo().performClick()
            compose.waitUntil(45000) { displayedCount() > 30 }
            val count = displayedCount()
            org.junit.Assert.assertTrue("Two 30-row pages must be deduplicated", count <= 60)
            compose
                .onNodeWithText("已显示 $count 首", substring = true)
                .performScrollTo()
                .assertIsDisplayed()
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(instrumentation.targetContext.externalCacheDir, "qq-search-60.png")
                    .outputStream()
                    .use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                bitmap.recycle()
            }
            compose.onNodeWithText("歌曲 / 歌手").performScrollTo().performTextReplacement("new query")
            compose.onAllNodesWithText("已显示", substring = true).assertCountEquals(0)
            compose.onAllNodesWithText("加载更多候选").assertCountEquals(0)
            compose.onNodeWithText("使用歌曲链接／ID 绑定").performScrollTo().performClick()
            compose.onNodeWithText("songmid / 歌曲链接").performScrollTo().assertIsDisplayed()
        } finally {
            settings
                .edit()
                .apply {
                    if (priority == null) remove("lyricPriority")
                    else putString("lyricPriority", priority)
                }
                .commit()
            compose.activityRule.scenario.onActivity {
                graph.preferences(original)
                graph.live()
            }
        }
    }
}
