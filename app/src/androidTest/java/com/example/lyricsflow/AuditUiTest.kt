package com.example.lyricsflow

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.lyricsflow.core.*
import com.example.lyricsflow.ui.SearchSheet
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AuditUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun changingSongsDiscardsPreviousSearchResultsAndUpdatesQuery() {
        val f = ChoiceFixture()
        val first = f.song()
        val second = f.song()
        val current = mutableStateOf(first)
        try {
            compose.activityRule.scenario.onActivity { activity ->
                activity.setContent {
                    MaterialTheme {
                        SearchSheet(
                            f.graph,
                            current.value,
                            {},
                            initialSource = Provider.QQ,
                            loadPage = { _, _, _, _, track ->
                                SearchPage(
                                    listOf(
                                        track!!.copy(
                                            title = "Old song result",
                                            provider = Provider.QQ,
                                            sourceId = "old-result",
                                        )
                                    )
                                )
                            },
                        ) {}
                    }
                }
            }
            compose.onNodeWithText("搜索歌曲").performScrollTo().performClick()
            compose.onNodeWithText("Old song result").performScrollTo().assertExists()
            compose.runOnIdle { current.value = second }
            compose.onNodeWithText("Old song result").assertDoesNotExist()
            compose.onNodeWithText("${second.title} ${second.artist}").assertExists()
        } finally {
            f.clean()
        }
    }
}
