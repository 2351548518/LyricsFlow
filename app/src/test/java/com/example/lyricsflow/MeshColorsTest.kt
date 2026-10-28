package com.example.lyricsflow

import com.example.lyricsflow.core.*
import org.junit.Assert.*
import org.junit.Test

class MeshColorsTest {
    @Test
    fun spatialColoursKeepTheirCoverPositions() {
        val red = 0xffee2233.toInt()
        val blue = 0xff2255ee.toInt()
        val green = 0xff22cc55.toInt()
        val gold = 0xffddaa22.toInt()
        val pixels =
            IntArray(64 * 64) { i ->
                when {
                    i / 64 < 32 && i % 64 < 32 -> red
                    i / 64 < 32 -> blue
                    i % 64 < 32 -> green
                    else -> gold
                }
            }
        val colors = MeshColors.spatial(pixels, 64, 64, 0xff777777.toInt())
        fun distance(a: Int, b: Int) =
            (0..2).sumOf { c ->
                kotlin.math.abs((a ushr (c * 8) and 255) - (b ushr (c * 8) and 255))
            }
        listOf(red, blue, green, gold).forEachIndexed { i, expected ->
            assertEquals(expected, listOf(red, blue, green, gold).minBy { distance(it, colors[i]) })
        }
        assertEquals(colors, MeshColors.spatial(pixels, 64, 64, 0xff777777.toInt()))
    }

    @Test
    fun spatialSamplingHandlesTinyAndTransparentCovers() {
        val fallback = 0xff345678.toInt()
        assertEquals(List(4) { fallback }, MeshColors.spatial(IntArray(4), 2, 2, fallback))
        assertEquals(List(4) { fallback }, MeshColors.spatial(intArrayOf(fallback), 1, 1, 0))
    }

    @Test
    fun extractsMultipleCoverColoursDeterministically() {
        val colors =
            intArrayOf(
                0xff843629.toInt(),
                0xff374e88.toInt(),
                0xff8b704a.toInt(),
                0xff435956.toInt(),
            )
        val pixels = IntArray(1024) { colors[it % 4] }
        val result = MeshColors.extract(pixels, 0xff5e534d.toInt())
        assertEquals(colors.toSet(), result.toSet())
        assertEquals(result, MeshColors.extract(pixels, 0xff5e534d.toInt()))
    }

    @Test
    fun allConvexMeshBlendsHaveReadableText() {
        val raw =
            listOf(0xffbb2244.toInt(), 0xff1166ff.toInt(), 0xffffcc00.toInt(), 0xff33aa88.toInt())
        for (base in listOf(0xff18354a.toInt(), 0xffeec892.toInt(), 0xff777777.toInt())) {
            val ink = CoverColors.readable(CoverColors.inverse(base), base, 7.0)
            val colors = MeshColors.readable(raw, base, ink)
            for (a in colors) for (b in colors) for (step in 0..20) {
                assertTrue(
                    "Mesh contrast dropped",
                    CoverColors.contrast(CoverColors.mix(a, b, step / 20f), ink) >= 4.5,
                )
            }
        }
    }

    @Test
    fun transparentAndMonochromeCoversHaveStableFallbacks() {
        val blue = 0xff225577.toInt()
        assertEquals(List(4) { blue }, MeshColors.extract(intArrayOf(0x00ffffff), blue))
        assertEquals(setOf(blue), MeshColors.extract(IntArray(1024) { blue }, blue).toSet())
    }
}
