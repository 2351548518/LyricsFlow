package com.example.lyricsflow

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.lyricsflow.core.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class UiFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var initial: Preferences

    @Before
    fun capturePreferences() {
        initial = (compose.activity.application as LyricsFlowApp).graph.state.value.prefs
    }

    @After
    fun restorePreferences() {
        compose.activityRule.scenario.onActivity {
            (it.application as LyricsFlowApp).graph.preferences(initial)
        }
    }

    @Test
    fun previewSettingsLibraryAndBack() {
        compose.onNodeWithText("先看看预览 →").performClick()
        compose.onNodeWithText("Somewhere, softer").assertIsDisplayed()
        compose.onNodeWithContentDescription("保存当前歌词").performClick()
        compose.onNodeWithText("我的", useUnmergedTree = true).performClick()
        compose.onNodeWithText("个性化").performClick()
        compose.onNodeWithText("自由排版").performClick()
        compose.onNodeWithText("深色").performScrollTo().performClick()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("本地聆听者").assertIsDisplayed()
        compose.onNodeWithText("首页", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Somewhere, softer").assertIsDisplayed()
        compose.onNodeWithText("我的", useUnmergedTree = true).performClick()
        compose.onNodeWithText("本地歌词库").performClick()
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("Somewhere, softer").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Somewhere, softer").performClick()
        compose.onNodeWithText("Somewhere, softer").assertIsDisplayed()
    }
}
