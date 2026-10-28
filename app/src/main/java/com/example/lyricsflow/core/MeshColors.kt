package com.example.lyricsflow.core

import kotlin.math.pow
import kotlin.math.roundToInt

/** Small deterministic colour clustering. The exact RGB mean remains the theme's base colour. */
object MeshColors {
    fun extract(pixels: IntArray, fallback: Int): List<Int> {
        val samples = pixels.filter { it ushr 24 >= 32 }.take(4096)
        if (samples.isEmpty()) return List(4) { fallback }
        fun distance(a: Int, b: Int): Double =
            (0..2).sumOf { channel ->
                val d = ((a ushr (channel * 8) and 255) - (b ushr (channel * 8) and 255)).toDouble()
                d * d
            }
        val centres = mutableListOf(samples.minBy { distance(it, fallback) })
        repeat(3) { centres += samples.maxBy { p -> centres.minOf { distance(p, it) } } }
        repeat(6) {
            val sums = Array(4) { LongArray(4) }
            samples.forEach { p ->
                val n = centres.indices.minBy { distance(p, centres[it]) }
                sums[n][0] += p ushr 16 and 255
                sums[n][1] += p ushr 8 and 255
                sums[n][2] += p and 255
                sums[n][3]++
            }
            centres.indices.forEach { n ->
                val s = sums[n]
                if (s[3] > 0)
                    centres[n] =
                        CoverColors.rgb(
                            (s[0] / s[3]).toInt(),
                            (s[1] / s[3]).toInt(),
                            (s[2] / s[3]).toInt(),
                        )
            }
        }
        return centres
    }

    /** Channel bounds make every convex mesh blend readable, not just its four control points. */
    fun readable(swatches: List<Int>, base: Int, ink: Int): List<Int> {
        val lightInk = CoverColors.luminance(ink) > CoverColors.luminance(base)
        val lum = CoverColors.luminance(ink)
        val bound =
            if (lightInk) ((lum + .05) / 4.6 - .05).coerceIn(0.0, 1.0)
            else ((lum + .05) * 4.6 - .05).coerceIn(0.0, 1.0)
        fun srgb(linear: Double) =
            if (linear <= .0031308) linear * 12.92 else 1.055 * linear.pow(1 / 2.4) - .055
        val channelBound =
            (srgb(bound) * 255)
                .let { if (lightInk) kotlin.math.floor(it) else kotlin.math.ceil(it) }
                .toInt()
                .coerceIn(0, 255)
        return (swatches.ifEmpty { listOf(base) })
            .let { colors -> List(4) { colors[it % colors.size] } }
            .map { raw ->
                val rgb = intArrayOf(raw ushr 16 and 255, raw ushr 8 and 255, raw and 255)
                if (lightInk) {
                    val peak = rgb.max().coerceAtLeast(1)
                    if (peak > channelBound)
                        for (i in rgb.indices) rgb[i] =
                            (rgb[i] * channelBound.toFloat() / peak).toInt()
                } else {
                    val low = rgb.min()
                    if (low < channelBound) {
                        val amount = (channelBound - low).toFloat() / (255 - low).coerceAtLeast(1)
                        for (i in rgb.indices) rgb[i] =
                            (rgb[i] + (255 - rgb[i]) * amount).roundToInt()
                    }
                }
                CoverColors.rgb(rgb[0], rgb[1], rgb[2])
            }
    }
}
