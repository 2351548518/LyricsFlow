package com.example.lyricsflow.playback

import android.graphics.Bitmap
import java.util.WeakHashMap

/** Reject empty / virtually uniform neutral placeholders, not actual monochrome photography. */
internal object ArtworkQuality {
    private data class Sample(val generation: Int, val usable: Boolean)
    private val samples = WeakHashMap<Bitmap, Sample>()

    @Synchronized fun usable(bitmap: Bitmap): Boolean {
        if (bitmap.isRecycled) return false
        val generation = bitmap.generationId
        samples[bitmap]?.takeIf { it.generation == generation }?.let { return it.usable }
        val result = runCatching {
            val scaled = Bitmap.createScaledBitmap(bitmap, 16, 16, true)
            val readable = if (android.os.Build.VERSION.SDK_INT >= 26 && scaled.config == Bitmap.Config.HARDWARE)
                scaled.copy(Bitmap.Config.ARGB_8888, false) else scaled
            try {
                val pixels = IntArray(256)
                readable.getPixels(pixels, 0, 16, 0, 0, 16, 16)
                var visible = 0
                var low = 255
                var high = 0
                var chroma = 0
                for (pixel in pixels) {
                    if (pixel ushr 24 < 32) continue
                    visible++
                    val r = pixel ushr 16 and 255
                    val g = pixel ushr 8 and 255
                    val b = pixel and 255
                    val light = (r + g + b) / 3
                    low = minOf(low, light); high = maxOf(high, light)
                    chroma = maxOf(chroma, maxOf(r, g, b) - minOf(r, g, b))
                }
                visible > 8 && !(low in 24..232 && high - low <= 12 && chroma <= 10)
            } finally {
                if (readable !== scaled) readable.recycle()
                if (scaled !== bitmap) scaled.recycle()
            }
        }.getOrDefault(false)
        samples[bitmap] = Sample(generation, result)
        return result
    }
}
