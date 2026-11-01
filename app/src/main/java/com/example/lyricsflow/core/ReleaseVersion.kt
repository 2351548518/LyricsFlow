package com.example.lyricsflow.core

/** Only stable numeric release tags are eligible for in-app updates. */
internal data class ReleaseVersion(val parts: List<Int>) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int {
        for (i in 0 until maxOf(parts.size, other.parts.size)) {
            val result = (parts.getOrElse(i) { 0 }).compareTo(other.parts.getOrElse(i) { 0 })
            if (result != 0) return result
        }
        return 0
    }

    companion object {
        fun parse(value: String): ReleaseVersion? {
            val raw = value.removePrefix("v")
            if (!Regex("[0-9]+(?:\\.[0-9]+){1,3}").matches(raw)) return null
            val parts = raw.split('.').map { it.toIntOrNull() ?: return null }
            return ReleaseVersion(parts)
        }
    }
}
