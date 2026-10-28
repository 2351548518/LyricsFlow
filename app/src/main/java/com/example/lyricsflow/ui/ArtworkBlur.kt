package com.example.lyricsflow.ui

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Mask only the artwork, so the one shared page background shows through at every boundary. */
internal fun Modifier.artworkFeather(focus: Boolean = false): Modifier = graphicsLayer {
    compositingStrategy = CompositingStrategy.Offscreen
}
    .drawWithCache {
        val vertical = if (focus) size.height * .46f else minOf(112.dp.toPx(), size.height * .24f)
        val horizontal = if (focus) size.width * .44f else minOf(28.dp.toPx(), size.width * .09f)
        fun stops(fraction: Float): Array<Pair<Float, Color>> {
            val edge = fraction.coerceIn(.001f, .49f)
            val leading =
                (0..12).map { step ->
                    val t = step / 12f
                    t * edge to Color.White.copy(alpha = t * t * (3f - 2f * t))
                }
            return (leading + leading.asReversed().map { (p, c) -> 1f - p to c }).toTypedArray()
        }
        val yMask = Brush.verticalGradient(*stops(vertical / size.height.coerceAtLeast(1f)))
        val xMask = Brush.horizontalGradient(*stops(horizontal / size.width.coerceAtLeast(1f)))
        onDrawWithContent {
            drawContent()
            drawRect(yMask, blendMode = BlendMode.DstIn)
            drawRect(xMask, blendMode = BlendMode.DstIn)
        }
    }

/** Two cached blur levels blend smoothly: readable centre, softer periphery, no blur on lyrics. */
@Composable
internal fun FeatheredArtwork(bitmap: Bitmap, radius: Float, contentKey: String) {
    val amount = radius.takeIf { it.isFinite() }?.coerceIn(0f, 24f) ?: 8f
    if (amount == 0f) BlurredArtwork(bitmap, 0f, contentKey)
    else {
        BlurredArtwork(bitmap, (amount * 2.2f + 4f).coerceAtMost(32f), contentKey, maxRadius = 32f)
        Box(Modifier.fillMaxSize().artworkFeather(focus = true)) {
            BlurredArtwork(bitmap, amount, contentKey)
        }
    }
}

/** API 24–30 fallback: bounded, cancellable, separable box passes approximating Gaussian blur. */
internal suspend fun blurCover(source: Bitmap, radius: Float): Bitmap? =
    withContext(Dispatchers.Default) {
        if (source.isRecycled) return@withContext null
        if (radius <= 0f) return@withContext source
        val scale = minOf(1f, 384f / maxOf(source.width, source.height))
        val width = (source.width * scale).roundToInt().coerceAtLeast(1)
        val height = (source.height * scale).roundToInt().coerceAtLeast(1)
        val software =
            if (Build.VERSION.SDK_INT >= 26 && source.config == Bitmap.Config.HARDWARE)
                source.copy(Bitmap.Config.ARGB_8888, false) ?: return@withContext null
            else source
        val readable = Bitmap.createScaledBitmap(software, width, height, true)
        try {
            val pixels = IntArray(width * height)
            val buffer = IntArray(pixels.size)
            val workerContext = coroutineContext
            readable.getPixels(pixels, 0, width, 0, 0, width, height)
            val r = (radius * scale / 1.6f).roundToInt().coerceIn(1, 48)
            fun pass(input: IntArray, output: IntArray, horizontal: Boolean) {
                val length = if (horizontal) width else height
                val lines = if (horizontal) height else width
                val window = 2 * r + 1
                repeat(lines) { line ->
                    workerContext.ensureActive()
                    fun index(position: Int) =
                        if (horizontal) line * width + position.coerceIn(0, length - 1)
                        else position.coerceIn(0, length - 1) * width + line
                    var a = 0
                    var red = 0
                    var green = 0
                    var blue = 0
                    for (position in -r..r) {
                        val pixel = input[index(position)]
                        a += pixel ushr 24
                        red += pixel ushr 16 and 255
                        green += pixel ushr 8 and 255
                        blue += pixel and 255
                    }
                    repeat(length) { position ->
                        output[index(position)] =
                            ((a / window) shl 24) or
                                ((red / window) shl 16) or
                                ((green / window) shl 8) or
                                (blue / window)
                        val remove = input[index(position - r)]
                        val add = input[index(position + r + 1)]
                        a += (add ushr 24) - (remove ushr 24)
                        red += (add ushr 16 and 255) - (remove ushr 16 and 255)
                        green += (add ushr 8 and 255) - (remove ushr 8 and 255)
                        blue += (add and 255) - (remove and 255)
                    }
                }
            }
            repeat(3) {
                pass(pixels, buffer, true)
                pass(buffer, pixels, false)
            }
            Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        } finally {
            if (readable !== software) readable.recycle()
            if (software !== source) software.recycle()
        }
    }

@Composable
internal fun BlurredArtwork(
    bitmap: Bitmap,
    radius: Float,
    contentKey: String,
    maxRadius: Float = 24f,
) {
    val amount = radius.takeIf { it.isFinite() }?.coerceIn(0f, maxRadius) ?: 8f
    if (Build.VERSION.SDK_INT >= 31 || amount == 0f) {
        Image(
            bitmap.asImageBitmap(),
            null,
            Modifier.fillMaxSize().blur(amount.dp, edgeTreatment = BlurredEdgeTreatment.Rectangle),
            contentScale = ContentScale.Crop,
            filterQuality = FilterQuality.High,
        )
    } else
        key(contentKey) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val pixelsPerDp =
                    1f / maxOf(maxWidth.value / bitmap.width, maxHeight.value / bitmap.height)
                val generation = bitmap.generationId
                val softened by
                    produceState<Bitmap?>(null, bitmap, generation, amount, pixelsPerDp) {
                        delay(80)
                        value = blurCover(bitmap, amount * pixelsPerDp)
                    }
                Image(
                    (softened ?: bitmap).asImageBitmap(),
                    null,
                    Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.High,
                )
            }
        }
}
