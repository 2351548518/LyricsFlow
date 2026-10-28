package com.example.lyricsflow

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.lyricsflow.core.*
import com.example.lyricsflow.ui.FlowApp
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SongSelectionUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun sourceDialogExplainsRefreshAndFailedSwitchOpensTheRequestedProvider() {
        val f = ChoiceFixture()
        try {
            val t = f.song()
            f.seed(t, Provider.QQ)
            f.play(t)
            f.main { f.graph.preferences(f.graph.state.value.prefs.copy(fontSize = 30f)) }
            compose.activityRule.scenario.onActivity { it.setContent { FlowApp(f.graph) } }
            compose.onNodeWithContentDescription("刷新当前来源歌词").assertIsDisplayed()
            compose.onNodeWithText("QQ 音乐").performClick()
            compose.onNodeWithText("重新自动匹配").assertIsDisplayed()
            compose.onNodeWithText("刷新只重新获取当前来源的当前记录，不更换来源。重新自动匹配成功后会解除本曲的固定选择。").assertExists()
            capture("source-actions")
            f.backend.failures += Provider.LRCLIB
            compose.onNodeWithText("LRCLIB").performClick()
            compose.waitUntil(5000) {
                !f.graph.state.value.busy && f.graph.state.value.requestedSource == Provider.LRCLIB
            }
            assertEquals(Provider.QQ, f.graph.state.value.source)
            compose.onNodeWithText("在 LRCLIB 中匹配").assertIsDisplayed()
            capture("failed-switch")
            compose.onNodeWithText("在 LRCLIB 中匹配").performClick()
            compose.onNodeWithText("LRCLIB").assertIsSelected()
            capture("requested-search")
        } finally {
            f.clean()
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        android.os.SystemClock.sleep(350)
        val image =
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                .uiAutomation
                .takeScreenshot() ?: error("Cannot capture QA screen")
        File(compose.activity.externalCacheDir, "selection-$name.png").outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
