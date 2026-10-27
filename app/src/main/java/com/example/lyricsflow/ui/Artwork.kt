package com.example.lyricsflow.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.lyricsflow.core.ScreenState
import com.example.lyricsflow.core.ThemeSpec
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SongArt(
    s: ScreenState,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val art = s.playback.artwork
    if (art != null)
        Image(
            art.asImageBitmap(),
            "专辑封面",
            modifier,
            contentScale = contentScale,
            filterQuality = FilterQuality.High,
        )
    else LandscapeCover(modifier)
}

@Composable
fun LyricBackground(s: ScreenState, bleed: Dp = 0.dp, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val custom by
        produceState<Bitmap?>(null, s.prefs.background, s.imageRevision) {
            value =
                if (s.prefs.background == "custom")
                    withContext(Dispatchers.IO) {
                        val f = File(context.filesDir, "background.jpg")
                        if (f.exists()) BitmapFactory.decodeFile(f.path) else null
                    }
                else null
        }
    val theme =
        remember(s.styleRaw) { s.styleRaw.takeIf { it.isNotBlank() }?.let { ThemeSpec.from(it) } }
    val type =
        if (s.prefs.background == "color" && theme?.backgroundType == "cover") "cover"
        else s.prefs.background
    val customAverage = rememberCoverAverage(custom)
    val average =
        when (type) {
            "cover" -> LocalCoverAverage.current.takeIf { s.playback.artwork != null }
            "custom" -> customAverage
            else -> null
        }
    val ink =
        average?.let { coverInk(it) }
            ?: LyricInk(
                androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                Color.Transparent,
            )
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize()
                .layout { measurable, constraints ->
                    val inset = bleed.roundToPx()
                    val width = constraints.maxWidth + inset * 2
                    val layer =
                        measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
                    layout(constraints.maxWidth, layer.height) { layer.place(-inset, 0) }
                }
                .testTag("lyric-art-layer")
        ) {
            if (average != null) Box(Modifier.fillMaxSize().background(Color(average)))
            if (theme?.backgroundColor != null && average == null)
                Box(Modifier.fillMaxSize().background(Color(theme.backgroundColor)))
            when (type) {
                "cover" ->
                    s.playback.artwork?.let {
                        BlurredArtwork(it, s.prefs.coverBlur, s.playback.track?.key.orEmpty())
                    }
                "custom" ->
                    custom?.let {
                        BlurredArtwork(it, s.prefs.coverBlur, "custom:${s.imageRevision}")
                    }
            }
        }
        // Keep the original image fully opaque. Contrast belongs to the glyphs, not a white veil.
        CompositionLocalProvider(LocalLyricInk provides ink, content = content)
    }
}
