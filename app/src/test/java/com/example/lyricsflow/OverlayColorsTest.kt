package com.example.lyricsflow

import com.example.lyricsflow.core.OverlayColors
import org.junit.Assert.*
import org.junit.Test

class OverlayColorsTest {
    @Test
    fun acceptsOpaqueRgbAndNormalizesUserInput() {
        assertEquals("#12ABCF", OverlayColors.normalize(" 12abcf "))
        assertEquals("#000000", OverlayColors.normalize("#000000"))
        assertEquals(0xff000000.toInt(), OverlayColors.resolve("#000000", 0xffff0000))
        assertEquals(0xff123abc.toInt(), OverlayColors.resolve("#123ABC"))
    }

    @Test
    fun rejectsPartialAlphaAndMalformedColors() {
        listOf("#12", "#GG1122", "#80FFFFFF", "red", "1234567").forEach {
            assertNull(OverlayColors.normalize(it))
        }
    }

    @Test
    fun defaultFollowsThemeButExplicitColorTakesPriority() {
        assertEquals(-1, OverlayColors.resolve(""))
        assertEquals(0xffa8e6cf.toInt(), OverlayColors.resolve("", 0xffa8e6cf))
        assertEquals(-1, OverlayColors.resolve("#FFFFFF", 0xffa8e6cf))
    }
}
