package com.example.lyricsflow

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.example.lyricsflow.core.AppGraph
import com.example.lyricsflow.data.*
import com.example.lyricsflow.ui.*
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GitHubUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun registrationGuideAndUnavailableLoginAreClear() {
        val graph = AppGraph(compose.activity.application)
        val slot = "github-ui-guide-test"
        val sync = GitHubSync(graph, GitHubApi(MemoryGitHub()), slot, defaultClientId = "") {}
        try {
            compose.activityRule.scenario.onActivity {
                it.setContent {
                    MaterialTheme(
                        colorScheme = flowColorScheme(flowPalette(4, 0xff63574c.toInt(), false))
                    ) {
                        Surface {
                            Box(Modifier.safeDrawingPadding().padding(24.dp)) {
                                GitHubSettings(sync) { _, _ -> fail("unexpected export") }
                            }
                        }
                    }
                }
            }
            compose.onNodeWithText("保存并登录 GitHub").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText("还没有 Client ID？查看注册指引").performScrollTo().performClick()
            compose.onNodeWithText("打开 GitHub 注册页面").performScrollTo().assertIsEnabled()
            capture("registration")
            compose.onNodeWithText("收起注册指引").performScrollTo().performClick()
            compose.onNodeWithText("OAuth App Client ID").performScrollTo()
            compose.onNode(hasSetTextAction()).performTextInput("test-public-client-id")
            compose.onNodeWithText("保存并登录 GitHub").performScrollTo().assertIsEnabled()
        } finally {
            graph.scope.cancel()
            compose.activity.getSharedPreferences(slot, 0).edit().clear().commit()
        }
    }

    @Test
    fun uploadNeedsPreviewAndDownloadNeedsExplicitRestore() {
        val graph = AppGraph(compose.activity.application)
        val slot = "github-ui-connected-test"
        val server = MemoryGitHub()
        val sync = GitHubSync(graph, GitHubApi(server), slot) {}
        try {
            compose.activityRule.scenario.onActivity {
                sync.configure("test-public-client-id", "lyricsflow-sync")
                sync.signIn()
                it.setContent {
                    MaterialTheme(
                        colorScheme = flowColorScheme(flowPalette(4, 0xff637582.toInt(), false))
                    ) {
                        Surface {
                            Box(Modifier.safeDrawingPadding().padding(24.dp)) {
                                GitHubSettings(sync) { _, _ -> fail("unexpected export") }
                            }
                        }
                    }
                }
            }
            compose.waitUntil(15000) { !sync.state.value.busy }
            compose.onNodeWithText("预览并上传本机资料").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText("连接 / 创建私有仓库").performScrollTo().performClick()
            compose.waitUntil(15000) { !sync.state.value.busy }
            compose.onNodeWithText("预览并上传本机资料").performScrollTo().performClick()
            compose.waitUntil(15000) { !sync.state.value.busy }
            compose.onNodeWithText("上传到私有仓库？").assertExists()
            assertEquals(0, server.patchCount)
            capture("upload-preview")
            compose.onNodeWithText("确认上传").performClick()
            compose.waitUntil(15000) { !sync.state.value.busy }
            assertEquals(1, server.patchCount)
            compose.onNodeWithText("下载云端资料并预览").performScrollTo().performClick()
            compose.waitUntil(15000) { !sync.state.value.busy }
            compose.onNodeWithText("合并云端资料？").assertExists()
            capture("download-preview")
            compose.onNodeWithText("取消").performClick()
            compose.onNodeWithText("下载云端资料并预览").assertExists()
        } finally {
            graph.scope.cancel()
            compose.activity.getSharedPreferences(slot, 0).edit().clear().commit()
            Secrets(compose.activity, slot).write("")
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        Thread.sleep(280)
        val root = File(compose.activity.getExternalFilesDir(null), "github-qa").apply { mkdirs() }
        // PixelCopy captures the Compose window without taking over the device-wide
        // UiAutomation service already used by Android CLI on some physical devices.
        val bitmap =
            compose
                .onAllNodes(isRoot(), useUnmergedTree = true)
                .onLast()
                .captureToImage()
                .asAndroidBitmap()
        File(root, "$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
