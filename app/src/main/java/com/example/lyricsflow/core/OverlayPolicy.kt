package com.example.lyricsflow.core

object OverlayPolicy {
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
            (playing || pausedAt == 0L || now - pausedAt < 300000)
}
