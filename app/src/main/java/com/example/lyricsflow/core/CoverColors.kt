package com.example.lyricsflow.core

import kotlin.math.pow
import kotlin.math.roundToInt

/** Opaque sRGB arithmetic; no Android dependency, also used by export/preview tests. */
object CoverColors {
    fun rgb(r: Int, g: Int, b: Int): Int = (0xff shl 24) or (r shl 16) or (g shl 8) or b

    fun inverse(color: Int): Int =
        rgb(255 - (color ushr 16 and 255), 255 - (color ushr 8 and 255), 255 - (color and 255))

    fun mix(from: Int, to: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        fun channel(shift: Int) =
            (((from ushr shift and 255) * (1 - t)) + (to ushr shift and 255) * t).roundToInt()
        return rgb(channel(16), channel(8), channel(0))
    }

    fun luminance(color: Int): Double {
        fun channel(shift: Int): Double {
            val v = (color ushr shift and 255) / 255.0
            return if (v <= .04045) v / 12.92 else ((v + .055) / 1.055).pow(2.4)
        }
        return .2126 * channel(16) + .7152 * channel(8) + .0722 * channel(0)
    }

    fun contrast(a: Int, b: Int): Double {
        val x = luminance(a)
        val y = luminance(b)
        return (maxOf(x, y) + .05) / (minOf(x, y) + .05)
    }

    fun readable(preferred: Int, background: Int, minimum: Double = 4.5): Int {
        if (contrast(preferred, background) >= minimum) return preferred
        val end =
            if (contrast(-1, background) > contrast(0xff000000.toInt(), background)) -1
            else 0xff000000.toInt()
        for (step in 1..100) {
            val candidate = mix(preferred, end, step / 100f)
            if (contrast(candidate, background) >= minimum) return candidate
        }
        return end
    }
}
