package com.example.lyricsflow

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.lyricsflow.data.*
import com.example.lyricsflow.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AppUpdateUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun latestVersionHasClearResult() {
        var dismissed = false
        compose.activityRule.scenario.onActivity {
            it.setContent {
                MaterialTheme {
                    UpdateDialog(UpdateState(UpdateStage.LATEST, message = "当前已是最新版本"),
                        check = { fail("unexpected retry") }, install = { fail("unexpected installer") }, dismiss = { dismissed = true })
                }
            }
        }
        compose.onNodeWithText("当前已是最新版本").assertIsDisplayed()
        compose.onNodeWithText("知道了").performClick()
        assertTrue(dismissed)
    }

    @Test fun downloadShowsProgressAndCanBeCancelled() {
        var cancelled = false
        compose.activityRule.scenario.onActivity {
            it.setContent {
                MaterialTheme {
                    UpdateDialog(UpdateState(UpdateStage.DOWNLOADING, "1.0.3", 1_000_000, 2_000_000),
                        check = {}, install = { fail("premature install") }, dismiss = { cancelled = true })
                }
            }
        }
        compose.onNodeWithText("正在下载 v1.0.3").assertIsDisplayed()
        compose.onNodeWithText("50%", substring = true).assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        assertTrue(cancelled)
    }

    @Test fun errorHasRetryAndReadyHasContinueInstallation() {
        var retried = false
        compose.activityRule.scenario.onActivity {
            it.setContent {
                MaterialTheme {
                    UpdateDialog(UpdateState(UpdateStage.ERROR, message = "网络连接失败，请检查网络后重试"),
                        check = { retried = true }, install = {}, dismiss = {})
                }
            }
        }
        compose.onNodeWithText("网络连接失败，请检查网络后重试").assertIsDisplayed()
        compose.onNodeWithText("重新检查").performClick()
        assertTrue(retried)
        var installed = false
        compose.activityRule.scenario.onActivity {
            it.setContent {
                MaterialTheme {
                    UpdateDialog(UpdateState(UpdateStage.READY, "1.0.3"),
                        check = {}, install = { installed = true }, dismiss = {})
                }
            }
        }
        compose.onNodeWithText("继续安装").performClick()
        assertTrue(installed)
    }
}
