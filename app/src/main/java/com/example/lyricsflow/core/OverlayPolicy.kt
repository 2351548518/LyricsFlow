package com.example.lyricsflow.core

object OverlayPolicy {
    const val PAUSE_GRACE_MS = 300000L
    fun visible(
        hasTrack: Boolean,
        interactive: Boolean,
        screenLocked: Boolean,
        playing: Boolean,
        pausedAt: Long,
        now: Long,
    ): Boolean =
        hasTrack &&
            interactive &&
            !screenLocked &&
            (playing || pausedAt == 0L || now - pausedAt < PAUSE_GRACE_MS)
}

/** Typed fields avoid concatenation collisions and rebuilding Preferences.toString every tick. */
internal data class OverlayRenderKey(
    val content: String,
    val line: Line?,
    val sungIndex: Int?,
    val preferences: Preferences,
    val style: String,
)
