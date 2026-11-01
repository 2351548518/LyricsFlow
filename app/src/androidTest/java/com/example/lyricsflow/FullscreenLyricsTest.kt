package com.example.lyricsflow

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.height
import com.example.lyricsflow.core.*
import com.example.lyricsflow.ui.FlowApp
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class FullscreenLyricsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var graph: AppGraph
    private lateinit var original: Preferences

    @Before fun setup() {
        graph = AppGraph(compose.activity.application)
        original = graph.state.value.prefs
        compose.runOnUiThread {
            graph.preferences(original.copy(free = false, reduced = false, background = "cover"))
            graph.preview()
            if (graph.state.value.playback.playing) graph.demoPlay()
        }
    }

    @After fun cleanup() {
        compose.runOnUiThread { graph.preferences(original) }
        graph.scope.cancel()
    }

    private fun show() {
        compose.activityRule.scenario.onActivity { activity -> activity.setContent { FlowApp(graph) } }
        compose.waitForIdle()
    }

    @Test fun expandsOnlyLyricsAndKeepsHeadingAndPlaybackWhenReturning() {
        show()
        val before = compose.onNodeWithTag("lyric-viewport").getUnclippedBoundsInRoot()
        val heading = compose.onNodeWithTag("track-title").getUnclippedBoundsInRoot()
        val playback = graph.state.value.playback
        compose.onNodeWithTag("lyric-viewport").performClick()
        compose.onNodeWithTag("fullscreen-lyrics").assertIsDisplayed()
        val after = compose.onNodeWithTag("lyric-viewport").getUnclippedBoundsInRoot()
        assertTrue("Lyrics must use the released control and dock area", after.height.value > before.height.value + 100)
        assertEquals(heading.top.value, compose.onNodeWithTag("track-title").getUnclippedBoundsInRoot().top.value, .5f)
        compose.onNodeWithTag("track-artist").assertIsDisplayed()
        compose.onNodeWithText("首页", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("歌曲匹配").assertDoesNotExist()
        compose.onNodeWithContentDescription("保存当前歌词").assertDoesNotExist()
        compose.onNodeWithTag("lyric-art-layer").assertIsDisplayed()
        assertEquals(playback, graph.state.value.playback)
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("home-lyrics").assertIsDisplayed()
        compose.onNodeWithText("首页", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("歌曲匹配").assertIsDisplayed()
        assertEquals(playback, graph.state.value.playback)
    }

    @Test fun scrollingDoesNotEnterFullscreenAndTappingAgainExits() {
        show()
        compose.onNodeWithContentDescription("同步歌词").performTouchInput { swipeUp(durationMillis = 450) }
        compose.onNodeWithTag("home-lyrics").assertIsDisplayed()
        // A tap during a fling first stops the list. Test entry once that gesture has settled.
        compose.mainClock.advanceTimeBy(1500)
        compose.waitForIdle()
        compose.onNodeWithTag("lyric-viewport").performTouchInput { click(center) }
        compose.onNodeWithTag("fullscreen-lyrics").assertIsDisplayed()
        compose.onNodeWithTag("lyric-viewport").performTouchInput { swipeDown(durationMillis = 450) }
        compose.onNodeWithTag("fullscreen-lyrics").assertIsDisplayed()
        compose.onNodeWithTag("lyric-viewport").performTouchInput { click(center) }
        compose.onNodeWithTag("home-lyrics").assertIsDisplayed()
    }

    @Test fun fullscreenRestoresAndFollowsSongChanges() {
        val restore = StateRestorationTester(compose)
        compose.activityRule.scenario.onActivity {
            it.findViewById<android.view.ViewGroup>(android.R.id.content).removeAllViews()
        }
        restore.setContent { FlowApp(graph) }
        compose.onNodeWithTag("lyric-viewport").performClick()
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("fullscreen-lyrics").assertIsDisplayed()
        compose.runOnUiThread {
            graph.browseLocal(Demo.track.copy(title = "Next song in fullscreen"), Demo.lyrics)
        }
        compose.onNodeWithText("Next song in fullscreen").assertIsDisplayed()
        compose.onNodeWithTag("fullscreen-lyrics").assertIsDisplayed()
        compose.onNodeWithTag("lyric-viewport").performClick()
        compose.onNodeWithTag("home-lyrics").assertIsDisplayed()
    }

    @Test fun reducedMotionAndFreeLayoutStillExpand() {
        compose.runOnUiThread { graph.preferences(graph.state.value.prefs.copy(free = true, reduced = true)) }
        show()
        val before = compose.onNodeWithTag("lyric-viewport").getUnclippedBoundsInRoot().height
        compose.onNodeWithTag("lyric-viewport").performClick()
        compose.onNodeWithTag("fullscreen-lyrics").assertIsDisplayed()
        assertTrue(compose.onNodeWithTag("lyric-viewport").getUnclippedBoundsInRoot().height > before)
        compose.onNodeWithTag("lyric-viewport").performClick()
        compose.onNodeWithTag("home-lyrics").assertIsDisplayed()
    }

    @Test fun expansionAndReturnAnimateThroughIntermediateSizes() {
        show()
        fun height() = compose.onNodeWithTag("lyric-viewport").getUnclippedBoundsInRoot().height.value
        val collapsed = height()
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("lyric-viewport").performClick()
            compose.mainClock.advanceTimeBy(160)
            val opening = height()
            compose.mainClock.advanceTimeBy(500)
            val expanded = height()
            assertTrue("Expansion should animate rather than jump", opening > collapsed + 1 && opening < expanded - 1)
            compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.mainClock.advanceTimeBy(160)
            val closing = height()
            compose.mainClock.advanceTimeBy(500)
            val returned = height()
            assertTrue("Return should animate rather than jump", closing > returned + 1 && closing < expanded - 1)
            assertEquals(collapsed, returned, .5f)
        } finally { compose.mainClock.autoAdvance = true }
    }
}
