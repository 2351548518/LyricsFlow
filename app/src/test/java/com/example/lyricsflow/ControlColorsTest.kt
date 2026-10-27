package com.example.lyricsflow

import com.example.lyricsflow.core.ControlColors
import com.example.lyricsflow.core.CoverColors
import org.junit.Assert.*
import org.junit.Test

class ControlColorsTest {
    @Test
    fun controlsStayReadableAcrossCoverHuesAndMidGray() {
        val channels = listOf(0, 64, 128, 192, 255)
        for (r in channels) for (g in channels) for (b in channels) {
            val background = CoverColors.rgb(r, g, b)
            val c = ControlColors.from(background, CoverColors.inverse(background))
            for (surface in listOf(c.background, c.low, c.surface, c.high, c.highest)) {
                assertTrue(
                    "text ${background.toUInt().toString(16)}",
                    CoverColors.contrast(c.ink, surface) >= 4.5,
                )
                assertTrue("secondary text", CoverColors.contrast(c.muted, surface) >= 4.5)
                assertTrue("outline", CoverColors.contrast(c.outline, surface) >= 3.0)
            }
            assertTrue("selected fill", CoverColors.contrast(c.action, c.surface) >= 3.0)
            assertTrue("selected label", CoverColors.contrast(c.onAction, c.action) >= 7.0)
        }
    }

    @Test
    fun lightGrayInkOnBlackStillUsesDarkerPanelTones() {
        val c = ControlColors.from(0xff030303.toInt(), 0xffaaaaaa.toInt())
        assertTrue(CoverColors.luminance(c.surface) <= CoverColors.luminance(c.background))
        assertTrue(CoverColors.contrast(c.ink, c.surface) >= 7)
    }
}
