package com.example.lyricsflow

import com.example.lyricsflow.core.CoverColors
import org.junit.Assert.*
import org.junit.Test

class CoverColorsTest {
    @Test
    fun inverseKeepsEachChannelAndPaleAccentIsBounded() {
        assertEquals(0xffedcba9.toInt(), CoverColors.inverse(0xff123456.toInt()))
        assertEquals(0xff000000.toInt(), CoverColors.inverse(-1))
        assertEquals(-1, CoverColors.mix(0xff123456.toInt(), -1, 2f))
    }

    @Test
    fun unreadableMiddleGrayAndExtremeColorsReachTextContrast() {
        val colors =
            listOf(
                0xff808080.toInt(),
                0xff101010.toInt(),
                -1,
                0xffffff00.toInt(),
                0xff123c84.toInt(),
            )
        for (background in colors) {
            val ink = CoverColors.readable(CoverColors.inverse(background), background)
            assertTrue(
                "${background.toUInt().toString(16)}",
                CoverColors.contrast(ink, background) >= 4.5,
            )
        }
        assertEquals(21.0, CoverColors.contrast(-1, 0xff000000.toInt()), .001)
    }
}
