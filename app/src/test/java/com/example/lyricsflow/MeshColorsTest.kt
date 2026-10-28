package com.example.lyricsflow

import com.example.lyricsflow.core.*
import org.junit.Assert.*
import org.junit.Test

class MeshColorsTest {
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
