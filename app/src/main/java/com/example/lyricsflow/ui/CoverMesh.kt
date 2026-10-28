package com.example.lyricsflow.ui

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.layer.*
import androidx.compose.ui.unit.IntSize
import androidx.core.graphics.scale
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.lyricsflow.core.MeshColors
import java.util.WeakHashMap
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private data class MeshSample(val generation: Int, val colors: List<Int>)

private val meshSamples = WeakHashMap<Bitmap, MeshSample>()

@Composable
internal fun rememberCoverSwatches(bitmap: Bitmap?, average: Int?): List<Int> {
    val generation = bitmap?.takeUnless { it.isRecycled }?.generationId
    val colors by
        produceState<List<Int>>(emptyList(), bitmap, generation) {
            if (bitmap == null || bitmap.isRecycled) {
                delay(500)
                value = emptyList()
                return@produceState
            }
            val cached =
                synchronized(meshSamples) {
                    meshSamples[bitmap]?.takeIf { it.generation == generation }
                }
            if (cached != null) {
                value = cached.colors
                return@produceState
            }
            val sampled =
                withContext(Dispatchers.Default) {
                    val readable =
                        if (Build.VERSION.SDK_INT >= 26 && bitmap.config == Bitmap.Config.HARDWARE)
                            bitmap.copy(Bitmap.Config.ARGB_8888, false)
                        else bitmap
                    if (readable == null) return@withContext emptyList()
                    val small = readable.scale(48, 48, true)
                    try {
                        val pixels = IntArray(48 * 48)
                        small.getPixels(pixels, 0, 48, 0, 0, 48, 48)
                        MeshColors.spatial(pixels, 48, 48, average ?: 0xff477f86.toInt())
                    } finally {
                        if (small !== readable) small.recycle()
                        if (readable !== bitmap) readable.recycle()
                    }
                }
            synchronized(meshSamples) {
                meshSamples[bitmap] = MeshSample(bitmap.generationId, sampled)
            }
            value = sampled
        }
    return colors
}

internal val LocalMeshSwatches = staticCompositionLocalOf<List<Int>> { emptyList() }

/** Multi-colour mesh field, independently implemented after studying Pear-Wall's GPU pipeline. */
@Composable
internal fun CoverMesh(
    palette: FlowPalette,
    swatches: List<Int>,
    animate: Boolean,
    reduced: Boolean,
    modifier: Modifier = Modifier,
) {
    val target =
        remember(swatches, palette) {
            MeshColors.readable(swatches, palette.background.toArgb(), palette.ink.toArgb())
        }
    var from by remember { mutableStateOf(target) }
    var to by remember { mutableStateOf(target) }
    val transition = remember { Animatable(1f) }
    LaunchedEffect(target, reduced) {
        val current =
            from.indices.map { i ->
                com.example.lyricsflow.core.CoverColors.mix(from[i], to[i], transition.value)
            }
        from =
            current.takeIf { colors ->
                colors.all {
                    com.example.lyricsflow.core.CoverColors.contrast(it, palette.ink.toArgb()) >=
                        4.5
                }
            } ?: target
        to = target
        transition.snapTo(if (reduced) 1f else 0f)
        if (!reduced) transition.animateTo(1f, tween(1400))
    }
    val phase = remember { mutableFloatStateOf(0f) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(animate, reduced, lifecycle) {
        if (animate && !reduced)
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                var previous = 0L
                var lastDraw = 0L
                while (true) withFrameNanos { now ->
                    if (previous != 0L && now - lastDraw >= 32_000_000L) {
                        phase.floatValue += ((now - previous) / 1_000_000_000f).coerceAtMost(.1f)
                        lastDraw = now
                        previous = now
                    } else if (previous == 0L) {
                        previous = now
                        lastDraw = now
                    }
                }
            }
    }
    val shader = remember { if (Build.VERSION.SDK_INT >= 33) MeshShader.create() else null }
    val layer = rememberGraphicsLayer()
    Canvas(modifier.fillMaxSize()) {
        val progress = transition.value
        val colors =
            from.indices.map { i ->
                Color(com.example.lyricsflow.core.CoverColors.mix(from[i], to[i], progress))
            }
        val t = phase.floatValue * .15f
        val points =
            arrayOf(
                Offset(.18f + sin(t * .83f) * .065f, .20f + cos(t * .67f) * .055f),
                Offset(.82f + cos(t * .73f) * .060f, .20f + sin(t * .91f) * .065f),
                Offset(.18f + cos(t * .61f) * .055f, .80f + sin(t * .77f) * .060f),
                Offset(.82f + sin(t * .71f) * .065f, .80f + cos(t * .89f) * .055f),
            )
        // A soft background needs few pixels. Force a small offscreen texture, then upscale it.
        // This leaves text at native resolution and avoids shading the full display every frame.
        val raster =
            IntSize(
                (size.width * .25f).toInt().coerceAtLeast(1),
                (size.height * .25f).toInt().coerceAtLeast(1),
            )
        layer.compositingStrategy = androidx.compose.ui.graphics.layer.CompositingStrategy.Offscreen
        layer.pivotOffset = Offset.Zero
        layer.scaleX = size.width / raster.width
        layer.scaleY = size.height / raster.height
        layer.record(size = raster) {
            if (Build.VERSION.SDK_INT >= 33 && shader != null) {
                MeshShader.draw(this, shader, colors, points, t)
            } else {
                drawRect(colors[0])
                for (i in 1..3) drawRect(
                    Brush.radialGradient(
                        listOf(colors[i], colors[i].copy(alpha = 0f)),
                        center = Offset(points[i].x * size.width, points[i].y * size.height),
                        radius = size.maxDimension * 1.15f,
                    )
                )
            }
        }
        drawLayer(layer)
    }
}

@androidx.annotation.RequiresApi(33)
private object MeshShader {
    private const val SOURCE =
        """
        uniform float2 resolution;
        uniform float phase;
        uniform float2 p0; uniform float2 p1; uniform float2 p2; uniform float2 p3;
        layout(color) uniform half4 c0; layout(color) uniform half4 c1;
        layout(color) uniform half4 c2; layout(color) uniform half4 c3;
        float weight(float2 uv, float2 p) { float2 d=uv-p; return exp(-3.2*dot(d,d)); }
        half4 main(float2 coord) {
            float2 uv=coord/resolution;
            uv.x+=0.045*sin(uv.y*3.0+phase);
            uv.y+=0.035*cos(uv.x*2.5-phase*0.7);
            float a=weight(uv,p0), b=weight(uv,p1), c=weight(uv,p2), d=weight(uv,p3);
            half3 rgb=(c0.rgb*a+c1.rgb*b+c2.rgb*c+c3.rgb*d)/(a+b+c+d);
            float noise=fract(sin(dot(coord,float2(12.9898,78.233)))*43758.5453)-0.5;
            return half4(clamp(rgb+half(noise/1024.0),0.0,1.0),1.0);
        }
    """

    fun create() = runCatching { android.graphics.RuntimeShader(SOURCE) }.getOrNull()

    fun draw(
        scope: androidx.compose.ui.graphics.drawscope.DrawScope,
        shader: android.graphics.RuntimeShader,
        colors: List<Color>,
        points: Array<Offset>,
        phase: Float,
    ) {
        shader.setFloatUniform("resolution", scope.size.width, scope.size.height)
        shader.setFloatUniform("phase", phase)
        points.forEachIndexed { i, p -> shader.setFloatUniform("p$i", p.x, p.y) }
        colors.forEachIndexed { i, c -> shader.setColorUniform("c$i", c.toArgb()) }
        with(scope) { drawRect(ShaderBrush(shader)) }
    }
}
