package com.example.lyricsflow.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.example.lyricsflow.core.ScreenState
import com.example.lyricsflow.core.ThemeSpec
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SongArt(s: ScreenState, modifier: Modifier = Modifier) {
    val art = s.playback.artwork
    if (art != null) Image(art.asImageBitmap(), "专辑封面", modifier, contentScale = ContentScale.Crop)
    else LandscapeCover(modifier)
}

@Composable
fun LyricBackground(s: ScreenState) {
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
    if (theme?.backgroundColor != null)
        Box(Modifier.fillMaxSize().background(Color(theme.backgroundColor)))
    when (
        if (s.prefs.background == "color" && theme?.backgroundType == "cover") "cover"
        else s.prefs.background
    ) {
        "cover" -> SongArt(s, Modifier.fillMaxSize().alpha(.14f))
        "custom" ->
            custom?.let {
                Image(
                    it.asImageBitmap(),
                    null,
                    Modifier.fillMaxSize().alpha(.2f),
                    contentScale = ContentScale.Crop,
                )
            }
    }
}
