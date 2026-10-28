package com.example.lyricsflow.ui

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import com.example.lyricsflow.core.ControlColors
import com.example.lyricsflow.core.CoverColors
import java.util.WeakHashMap
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

private data class CachedAverage(val generation: Int, val color: Int)

private val coverAverages = WeakHashMap<Bitmap, CachedAverage>()

/** Exact per-channel mean, scanned off the UI thread in roughly 16K-pixel batches. */
internal suspend fun averageCover(bitmap: Bitmap): Int? {
    if (bitmap.isRecycled) return null
    val generation = bitmap.generationId
    synchronized(coverAverages) {
        coverAverages[bitmap]
            ?.takeIf { it.generation == generation }
            ?.let {
                return it.color
            }
    }
    return withContext(Dispatchers.Default) {
        val readable =
            if (Build.VERSION.SDK_INT >= 26 && bitmap.config == Bitmap.Config.HARDWARE)
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            else bitmap
        if (readable == null) return@withContext null
        try {
            val rows = (16384 / readable.width).coerceAtLeast(1)
            val pixels = IntArray(readable.width * minOf(rows, readable.height))
            var red = 0L
            var green = 0L
            var blue = 0L
            var count = 0L
            var y = 0
            while (y < readable.height) {
                coroutineContext.ensureActive()
                val height = minOf(rows, readable.height - y)
                readable.getPixels(pixels, 0, readable.width, 0, y, readable.width, height)
                repeat(readable.width * height) { i ->
                    val pixel = pixels[i]
                    if (pixel ushr 24 != 0) {
                        red += pixel ushr 16 and 255
                        green += pixel ushr 8 and 255
                        blue += pixel and 255
                        count++
                    }
                }
                y += height
            }
            if (count == 0L) null
            else
                CoverColors.rgb(
                        (red / count).toInt(),
                        (green / count).toInt(),
                        (blue / count).toInt(),
                    )
                    .also {
                        synchronized(coverAverages) {
                            coverAverages[bitmap] = CachedAverage(generation, it)
                        }
                    }
        } finally {
            if (readable !== bitmap) readable.recycle()
        }
    }
}

@Composable
internal fun rememberCoverAverage(bitmap: Bitmap?): Int? {
    val generation = bitmap?.takeUnless { it.isRecycled }?.generationId
    // MediaController refreshes can return a new Bitmap instance for the same cover.
    // Keep the displayed colour until its replacement is ready, instead of flashing fallback.
    val average by
        produceState<Int?>(null, bitmap, generation) {
            if (bitmap != null && !bitmap.isRecycled) value = averageCover(bitmap)
            else {
                delay(500)
                value = null
            }
        }
    return average
}

internal val LocalCoverAverage = staticCompositionLocalOf<Int?> { null }

internal data class FlowPalette(
    val accent: Color,
    val background: Color,
    val ink: Color,
    val water: Color,
    val light: Color,
    val followsCover: Boolean = false,
)

internal fun flowPalette(index: Int, average: Int?, dark: Boolean): FlowPalette {
    val seed =
        if (index == 4) average ?: 0xff477f86.toInt()
        else
            listOf(0xff526f61.toInt(), 0xff9b705f.toInt(), 0xff78728f.toInt(), 0xff497f91.toInt())[
                index.coerceIn(0, 3)]
    val accent = CoverColors.mix(seed, -1, if (dark) .38f else .18f)
    val bg =
        if (index == 4)
            CoverColors.mix(seed, if (dark) 0xff000000.toInt() else -1, if (dark) .08f else .06f)
        else
            CoverColors.mix(
                seed,
                if (dark) 0xff10191f.toInt() else 0xfffcfcf8.toInt(),
                if (dark) .87f else .94f,
            )
    val ink =
        CoverColors.readable(
            if (index == 4) CoverColors.inverse(seed)
            else if (dark) 0xffe9f3ed.toInt() else 0xff203b40.toInt(),
            bg,
            7.0,
        )
    return FlowPalette(
        Color(accent),
        Color(bg),
        Color(ink),
        Color(CoverColors.mix(seed, 0xff6daeb7.toInt(), .5f)),
        Color(CoverColors.mix(seed, 0xffe0d7c3.toInt(), .65f)),
        index == 4,
    )
}

internal fun flowColorScheme(palette: FlowPalette): ColorScheme {
    val ui = ControlColors.from(palette.background.toArgb(), palette.ink.toArgb())
    val base =
        if (CoverColors.luminance(ui.background) < .18) darkColorScheme() else lightColorScheme()
    val action = Color(ui.action)
    val onAction = Color(ui.onAction)
    val error = Color(CoverColors.readable(0xffd43f54.toInt(), ui.background))
    return base.copy(
        primary = action,
        onPrimary = onAction,
        primaryContainer = action,
        onPrimaryContainer = onAction,
        secondary = action,
        onSecondary = onAction,
        secondaryContainer = Color(ui.high),
        onSecondaryContainer = Color(ui.ink),
        tertiary = action,
        onTertiary = onAction,
        tertiaryContainer = Color(ui.highest),
        onTertiaryContainer = Color(ui.ink),
        background = palette.background,
        onBackground = Color(ui.ink),
        surface = Color(ui.surface),
        onSurface = Color(ui.ink),
        surfaceVariant = Color(ui.highest),
        onSurfaceVariant = Color(ui.muted),
        surfaceContainerLowest = palette.background,
        surfaceContainerLow = Color(ui.low),
        surfaceContainer = Color(ui.surface),
        surfaceContainerHigh = Color(ui.high),
        surfaceContainerHighest = Color(ui.highest),
        surfaceDim = Color(ui.high),
        surfaceBright = Color(ui.low),
        surfaceTint = action,
        outline = Color(ui.outline),
        outlineVariant = Color(ui.divider),
        inverseSurface = action,
        inverseOnSurface = onAction,
        inversePrimary = onAction,
        primaryFixed = action,
        primaryFixedDim = action,
        onPrimaryFixed = onAction,
        onPrimaryFixedVariant = onAction,
        secondaryFixed = action,
        secondaryFixedDim = action,
        onSecondaryFixed = onAction,
        onSecondaryFixedVariant = onAction,
        tertiaryFixed = action,
        tertiaryFixedDim = action,
        onTertiaryFixed = onAction,
        onTertiaryFixedVariant = onAction,
        error = error,
        onError = Color(CoverColors.readable(ui.background, error.toArgb())),
        errorContainer = Color(ui.high),
        onErrorContainer = error,
    )
}

/** Quiet static currents: no perpetual animation or work on each playback tick. */
@Composable
internal fun FlowBackdrop(
    palette: FlowPalette,
    dark: Boolean,
    swatches: List<Int> = emptyList(),
    mesh: Boolean = false,
    animate: Boolean = false,
    reduced: Boolean = true,
) {
    if (palette.followsCover && mesh) {
        CoverMesh(palette, swatches, animate, reduced)
        return
    }
    Canvas(Modifier.fillMaxSize()) {
        if (palette.followsCover) {
            val base = palette.background.toArgb()
            val toward =
                if (CoverColors.luminance(palette.ink.toArgb()) > .5) 0xff000000.toInt() else -1
            drawRect(
                Brush.verticalGradient(
                    listOf(
                        palette.background,
                        Color(CoverColors.mix(base, toward, .08f)),
                        Color(CoverColors.mix(base, toward, .16f)),
                    )
                )
            )
            return@Canvas
        }
        drawRect(
            Brush.linearGradient(
                listOf(
                    palette.background,
                    palette.water.copy(alpha = if (dark) .12f else .14f),
                    palette.background,
                ),
                end = Offset(size.width, size.height),
            )
        )
        drawRect(
            Brush.radialGradient(
                listOf(palette.light.copy(alpha = .19f), Color.Transparent),
                center = Offset(size.width, size.height * .15f),
                radius = size.height * .65f,
            )
        )
        for (i in 0..2) {
            val y = size.height * (.50f + i * .13f)
            val path =
                Path().apply {
                    moveTo(-size.width * .2f, y)
                    cubicTo(
                        size.width * .25f,
                        y - size.height * .23f,
                        size.width * .65f,
                        y + size.height * .22f,
                        size.width * 1.2f,
                        y - size.height * .1f,
                    )
                    lineTo(size.width * 1.2f, size.height * 1.2f)
                    lineTo(-size.width * .2f, size.height * 1.2f)
                    close()
                }
            drawPath(path, palette.water.copy(alpha = if (dark) .035f else .045f))
        }
    }
}

internal data class LyricInk(val color: Color, val scrim: Color, val overImage: Boolean = false)

internal val LocalLyricInk = staticCompositionLocalOf {
    LyricInk(Color.Unspecified, Color.Transparent)
}

internal fun coverInk(average: Int): LyricInk {
    val inverse = CoverColors.inverse(average)
    val color = CoverColors.readable(inverse, average, 7.0)
    val lightText = CoverColors.luminance(color) > .5
    val scrim = if (lightText) 0xff08171c.toInt() else 0xfff8fbf7.toInt()
    return LyricInk(Color(color), Color(scrim), true)
}
