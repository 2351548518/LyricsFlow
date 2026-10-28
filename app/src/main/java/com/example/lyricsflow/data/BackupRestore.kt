package com.example.lyricsflow.data

import android.util.Base64
import com.example.lyricsflow.core.AppGraph
import com.example.lyricsflow.core.OverlayColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Local import and cloud restore share validation, merge semantics and permission safeguards. */
suspend fun restoreBackup(
    graph: AppGraph,
    backup: Pair<List<Triple<String, String, String>>, JSONObject>,
) {
    val o = backup.second
    val p = graph.state.value.prefs
    withContext(Dispatchers.IO) {
        // Validate/import the image before mutating database records.
        if (o.has("backgroundImage"))
            BackgroundFiles.importImage(
                graph.app,
                Base64.decode(o.getString("backgroundImage"), Base64.DEFAULT),
            )
        graph.store.restore(backup.first)
    }
    o.optJSONObject("trackPreferences")?.let { graph.restoreTrackPreferences(it) }
    fun number(key: String, default: Float, min: Float, max: Float) =
        o.optDouble(key, default.toDouble()).toFloat().takeIf { it.isFinite() }?.coerceIn(min, max)
            ?: default
    graph.preferences(
        p.copy(
            translation = o.optBoolean("translation", p.translation),
            romanization = o.optBoolean("romanization", p.romanization),
            free = o.optBoolean("free", p.free),
            fontSize = number("fontSize", 30f, 10f, 96f),
            dark =
                o.optString("dark", "system").takeIf { it in listOf("light", "dark", "system") }
                    ?: "system",
            palette = o.optInt("palette", 0).coerceIn(0, 4),
            reduced = o.optBoolean("reduced", false),
            autoSources = o.optBoolean("autoSources", p.autoSources),
            overlaySize = number("overlaySize", 22f, 12f, 48f),
            overlayAlpha = number("overlayAlpha", .85f, 0f, 1f),
            overlayTranslation = o.optBoolean("overlayTranslation", true),
            overlayColor = OverlayColors.normalize(o.optString("overlayColor", "")).orEmpty(),
            autoTranslate = false,
            consent = false,
            coverBlur = number("coverBlur", p.coverBlur, 0f, 24f),
            coverMesh = o.optBoolean("coverMesh", p.coverMesh),
            background =
                o.optString("background", "color").takeIf {
                    it in listOf("color", "cover", "custom", "mesh")
                } ?: "color",
        )
    )
    graph.backgroundChanged()
    graph.reloadStyles()
    graph.reloadRememberedLyrics()
}
