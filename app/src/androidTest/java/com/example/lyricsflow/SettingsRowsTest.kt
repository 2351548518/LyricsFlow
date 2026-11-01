package com.example.lyricsflow

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import com.example.lyricsflow.core.AppGraph
import com.example.lyricsflow.ui.*
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SettingsRowsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun choicesFillSeparateRowsWithEqualWidthsOnCompactPhone() {
        val groups = listOf(listOf("经典滚动", "自由排版"), listOf("跟随封面", "苔绿", "陶土", "雾紫", "海蓝"),
            listOf("跟随系统", "浅色", "深色"), listOf("专辑封面", "纯色", "自定义图片", "网状渐变"))
        compose.activityRule.scenario.onActivity {
            it.setContent {
                MaterialTheme {
                    Column(Modifier.width(305.dp)) {
                        groups.forEach { labels ->
                            var selected by remember(labels) { mutableIntStateOf(0) }
                            FlowChoiceRow(labels, selected) { selected = it }
                        }
                    }
                }
            }
        }
        groups.forEach { labels ->
            val row = compose.onNodeWithTag("choices-${labels.first()}").getUnclippedBoundsInRoot()
            val buttons = labels.map { compose.onNodeWithTag("choice-$it").getUnclippedBoundsInRoot() }
            assertEquals(row.left.value, buttons.first().left.value, .5f)
            assertEquals(row.right.value, buttons.last().right.value, .5f)
            buttons.forEach {
                assertEquals(buttons.first().right.value - buttons.first().left.value, it.right.value - it.left.value, .5f)
                assertEquals(buttons.first().top.value, it.top.value, .5f)
                assertEquals(buttons.first().bottom.value, it.bottom.value, .5f)
            }
            compose.onNodeWithTag("choice-${labels.last()}").performClick().assertIsSelected()
            compose.onNodeWithTag("selected-choice-${labels.last()}", useUnmergedTree = true).assertExists()
            labels.forEach { label ->
                val layouts = mutableListOf<TextLayoutResult>()
                compose.onNodeWithText(label, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                val layout = layouts.single()
                assertFalse("Button label must fit without clipping: $label (width=${layout.didOverflowWidth}, height=${layout.didOverflowHeight}, size=${layout.size})", layout.hasVisualOverflow)
            }
        }
    }

    @Test fun sourceSaveButtonFillsContentWidth() {
        val graph = AppGraph(compose.activity.application)
        try {
            compose.activityRule.scenario.onActivity {
                it.setContent {
                    MaterialTheme {
                        Box(Modifier.fillMaxSize().padding(24.dp)) {
                            val state by graph.state.collectAsState()
                            SourceSettings(state, graph) {}
                        }
                    }
                }
            }
            val save = compose.onNodeWithText("保存来源设置").performScrollTo().getUnclippedBoundsInRoot()
            val field = compose.onNode(hasSetTextAction()).getUnclippedBoundsInRoot()
            assertEquals(field.left.value, save.left.value, .5f)
            assertEquals(field.right.value, save.right.value, .5f)
        } finally { graph.scope.cancel() }
    }
}
