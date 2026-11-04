package com.example.lyricsflow

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import com.example.lyricsflow.core.*
import com.example.lyricsflow.data.*
import com.example.lyricsflow.ui.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class FontSizesTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun size(node: SemanticsNodeInteraction): Float {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single().layoutInput.style.fontSize.value
    }

    @Test fun independentControlsPreviewPersistAndRestore() {
        val f = ChoiceFixture()
        try {
            f.main { f.graph.preferences(f.original.copy(romanization = true, translation = true, consent = false)) }
            compose.activityRule.scenario.onActivity { it.setContent { MaterialTheme {
                val state by f.graph.state.collectAsState()
                Appearance(state, f.graph, null, {}, {}, {}, {})
            } } }
            fun set(tag: String, value: Float) {
                compose.onNodeWithTag(tag).performScrollTo()
                    .performSemanticsAction(SemanticsActions.SetProgress) { it(value) }
            }
            set("original-size", 32f)
            set("translation-size", 22f)
            set("romanization-size", 15f)
            assertEquals(32f, size(compose.onNodeWithTag("size-preview-original")), .01f)
            assertEquals(22f, size(compose.onNodeWithTag("size-preview-translation")), .01f)
            assertEquals(15f, size(compose.onNodeWithTag("size-preview-romanization")), .01f)
            set("original-size", 26f)
            assertEquals(22f, f.graph.state.value.prefs.translationSize, .01f)
            assertEquals(15f, f.graph.state.value.prefs.romanizationSize, .01f)
            val restarted = AppGraph(compose.activity.application, monitorPlayback = false)
            try {
                assertEquals(26f, restarted.state.value.prefs.fontSize, .01f)
                assertEquals(22f, restarted.state.value.prefs.translationSize, .01f)
                assertEquals(15f, restarted.state.value.prefs.romanizationSize, .01f)
            } finally { restarted.scope.cancel() }
            val backup = Transfer.validateBackup(Transfer.backup(f.graph))
            assertEquals(22.0, backup.second.getDouble("translationSize"), .01)
            assertEquals(15.0, backup.second.getDouble("romanizationSize"), .01)
            f.main { f.graph.preferences(f.graph.state.value.prefs.copy(translationSize = 10f, romanizationSize = 9f)) }
            runBlocking { withContext(Dispatchers.Main) { restoreBackup(f.graph, backup) } }
            assertEquals(22f, f.graph.state.value.prefs.translationSize, .01f)
            assertEquals(15f, f.graph.state.value.prefs.romanizationSize, .01f)
            runBlocking { withContext(Dispatchers.Main) {
                restoreBackup(f.graph, emptyList<Triple<String,String,String>>() to JSONObject().put("fontSize", 40))
            } }
            assertEquals(24f, f.graph.state.value.prefs.translationSize, .01f)
            assertEquals(13f, f.graph.state.value.prefs.romanizationSize, .01f)
        } finally { f.clean() }
    }

    @Test fun readerAndAlignedUnitsUseIndependentSizesInAllModes() {
        val f = ChoiceFixture()
        val row = Line(null, null, "光を迎えて", "迎接微光", "hikari o mukaete")
        try {
            f.main {
                f.graph.preferences(f.original.copy(fontSize = 28f, translationSize = 20f,
                    romanizationSize = 12f, romanization = true, translation = true,
                    consent = false, reduced = true, free = false))
                f.graph.browseLocal(f.song(), Lyrics(listOf(row), Provider.LOCAL))
            }
            compose.activityRule.scenario.onActivity { it.setContent { FlowApp(f.graph) } }
            fun verify() {
                assertEquals(28f, size(compose.onNodeWithText(row.text)), .01f)
                assertEquals(20f, size(compose.onNodeWithText(row.translation)), .01f)
                assertEquals(12f, size(compose.onNodeWithText(row.romanization)), .01f)
            }
            verify()
            compose.onNodeWithTag("lyric-viewport").performClick(); verify()
            compose.onNodeWithTag("lyric-viewport").performClick()
            f.main { f.graph.preferences(f.graph.state.value.prefs.copy(free = true)) }; verify()
            f.main {
                f.graph.preferences(f.graph.state.value.prefs.copy(free = false))
                f.graph.browseLocal(f.song(), Lyrics(listOf(row.copy(alignment = listOf(
                    LyricAlignment("光を", "hikari o", "微光"), LyricAlignment("迎えて", "mukaete", "迎接")))), Provider.LOCAL))
            }
            assertEquals(28f, size(compose.onNodeWithText("光を")), .01f)
            assertEquals(20f, size(compose.onNodeWithText("微光")), .01f)
            assertEquals(12f, size(compose.onNodeWithText("hikari o")), .01f)
        } finally { f.clean() }
    }
}
