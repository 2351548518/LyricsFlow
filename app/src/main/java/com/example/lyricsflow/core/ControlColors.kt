package com.example.lyricsflow.core

/** Opaque UI colours: state and readability never depend on a low-opacity overlay. */
data class ControlColors(
    val background: Int,
    val ink: Int,
    val muted: Int,
    val action: Int,
    val onAction: Int,
    val low: Int,
    val surface: Int,
    val high: Int,
    val highest: Int,
    val outline: Int,
    val divider: Int,
) {
    companion object {
        fun from(background: Int, preferredInk: Int): ControlColors {
            val ink = CoverColors.readable(preferredInk, background, 7.0)
            val away =
                if (CoverColors.luminance(ink) > CoverColors.luminance(background))
                    0xff000000.toInt()
                else -1
            fun tone(amount: Float) = CoverColors.mix(background, away, amount)
            val muted = CoverColors.readable(CoverColors.mix(background, ink, .78f), background)
            return ControlColors(
                background,
                ink,
                muted,
                ink,
                CoverColors.readable(background, ink, 7.0),
                tone(.06f),
                tone(.11f),
                tone(.17f),
                tone(.23f),
                CoverColors.readable(CoverColors.mix(background, ink, .65f), background, 3.0),
                CoverColors.mix(background, ink, .24f),
            )
        }
    }
}
