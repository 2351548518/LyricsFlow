package com.example.lyricsflow.core

import java.util.Locale

/** An empty override follows the theme; explicit colours are always opaque RGB. */
object OverlayColors {
    fun normalize(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty()) return ""
        val hex = s.removePrefix("#")
        return if (Regex("[0-9a-fA-F]{6}").matches(hex)) "#${hex.uppercase(Locale.ROOT)}" else null
    }

    fun resolve(override: String, theme: Long? = null): Int {
        val normalized = normalize(override).orEmpty()
        return if (normalized.isNotEmpty()) normalized.drop(1).toInt(16) or 0xff000000.toInt()
        else theme?.toInt() ?: -1
    }
}
