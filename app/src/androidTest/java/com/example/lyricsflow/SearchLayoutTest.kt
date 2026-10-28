package com.example.lyricsflow

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.lyricsflow.core.*
import com.example.lyricsflow.ui.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SearchLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun providersAndBothActionsShareOneRowAndLabelsStayShort() {
        val f = ChoiceFixture()
        try {
            val t = f.song()
            f.seed(t, Provider.QQ)
            f.play(t)
            compose.activityRule.scenario.onActivity { activity ->
                activity.setContent { FlowApp(f.graph) }
            }
            compose.onNodeWithText("QQ 音乐").performClick()
            assertProviderRow()
            val auto = compose.onNodeWithTag("source-rematch").getUnclippedBoundsInRoot()
            val manual = compose.onNodeWithTag("source-open-match").getUnclippedBoundsInRoot()
            assertEquals(auto.top.value, manual.top.value, .5f)
            assertEquals(
                auto.bottom.value - auto.top.value,
                manual.bottom.value - manual.top.value,
                .5f,
            )
            assertTrue(auto.right <= manual.left)
            capture("source-dialog")
            compose.onNodeWithTag("source-open-match").performClick()
            compose.onNodeWithText("寻找这首歌").assertIsDisplayed()
            assertProviderRow()
            capture("matching-sheet")
        } finally {
            f.clean()
        }
    }

    @Test
    fun initialSearchFailureHasNoPaginationAndTheSearchButtonCanRetry() {
        val f = ChoiceFixture()
        val failing = AtomicBoolean(true)
        try {
            val t = f.song()
            compose.activityRule.scenario.onActivity { activity ->
                activity.setContent {
                    MaterialTheme {
                        SearchSheet(
                            f.graph,
                            t,
                            {},
                            initialSource = Provider.AMLL,
                            loadPage = { provider, _, _, _, _ ->
                                if (failing.get())
                                    throw IllegalArgumentException("Failed requirement.")
                                SearchPage(
                                    listOf(
                                        t.copy(
                                            title = "重试后的候选",
                                            provider = provider,
                                            sourceId = "result-1",
                                        )
                                    )
                                )
                            },
                        ) {}
                    }
                }
            }
            compose.onNodeWithText("搜索歌曲").performScrollTo().performClick()
            compose.waitUntil(5000) {
                compose
                    .onAllNodesWithText("请求未完成，请检查输入后重试，或切换来源。")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onAllNodesWithText("加载更多候选").assertCountEquals(0)
            compose.onAllNodesWithText("重试加载").assertCountEquals(0)
            compose.onNodeWithText("请求未完成，请检查输入后重试，或切换来源。").performScrollTo().assertIsDisplayed()
            capture("first-failure")
            failing.set(false)
            compose.onNodeWithText("搜索歌曲").performScrollTo().performClick()
            compose.onNodeWithText("重试后的候选").performScrollTo().assertIsDisplayed()
            compose.onAllNodesWithText("加载更多候选").assertCountEquals(0)
        } finally {
            f.clean()
        }
    }

    @Test
    fun pageFailureKeepsExistingResultsAndOffersExactlyOneRetry() {
        val f = ChoiceFixture()
        val failNext = AtomicBoolean(true)
        try {
            val t = f.song()
            compose.activityRule.scenario.onActivity { activity ->
                activity.setContent {
                    MaterialTheme {
                        SearchSheet(
                            f.graph,
                            t,
                            {},
                            initialSource = Provider.QQ,
                            loadPage = { provider, _, page, _, _ ->
                                if (page > 0 && failNext.get())
                                    throw IllegalStateException("HTTP 503")
                                SearchPage(
                                    listOf(
                                        t.copy(
                                            title = if (page == 0) "第一条候选" else "第二条候选",
                                            provider = provider,
                                            sourceId = "id-$page",
                                        )
                                    ),
                                    more = page == 0,
                                    total = 2,
                                )
                            },
                        ) {}
                    }
                }
            }
            compose.onNodeWithText("搜索歌曲").performScrollTo().performClick()
            compose.onNodeWithText("第一条候选").performScrollTo().assertIsDisplayed()
            compose.onAllNodesWithText("加载更多候选").assertCountEquals(1)
            compose.onNodeWithText("加载更多候选").performScrollTo().performClick()
            compose.waitUntil(5000) {
                compose.onAllNodesWithText("重试加载").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onAllNodesWithText("加载更多候选").assertCountEquals(0)
            compose.onAllNodesWithText("重试加载").assertCountEquals(1)
            compose.onNodeWithText("第一条候选").assertExists()
            compose.onNodeWithText("重试加载").performScrollTo()
            capture("page-retry")
            failNext.set(false)
            compose.onNodeWithText("重试加载").performClick()
            compose.onNodeWithText("第二条候选").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("第一条候选").assertExists()
            compose.onAllNodesWithText("重试加载").assertCountEquals(0)
            compose.onAllNodesWithText("加载更多候选").assertCountEquals(0)
        } finally {
            f.clean()
        }
    }

    private fun assertProviderRow() {
        val bounds = LYRIC_PROVIDERS.map { provider ->
            compose
                .onNodeWithTag("provider-choice-${provider.name}")
                .assertIsDisplayed()
                .getUnclippedBoundsInRoot()
        }
        bounds.forEach { assertEquals(bounds.first().top.value, it.top.value, .5f) }
        bounds.zipWithNext().forEach { (a, b) -> assertTrue(a.right <= b.left) }
        compose.onNodeWithText("网易云").assertExists()
        compose.onAllNodesWithText("网易云音乐").assertCountEquals(0)
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        android.os.SystemClock.sleep(350)
        val image =
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                .uiAutomation
                .takeScreenshot()!!
        File(compose.activity.externalCacheDir, "search-layout-$name.png").outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
