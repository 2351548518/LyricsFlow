package com.example.lyricsflow.playback

import android.media.MediaMetadata
import com.example.lyricsflow.core.Track

/** QQ's Bluetooth lyric mode replaces TITLE/ARTIST but keeps stable UCAR song metadata. */
object PlayerMetadata {
    fun track(packageName: String, metadata: MediaMetadata?): Track? {
        metadata ?: return null
        fun text(key: String) = metadata.getString(key).orEmpty().trim()
        val qqTitle =
            if (packageName == "com.tencent.qqmusic") text("ucar.media.metadata.UCAR_TITLE") else ""
        val title =
            qqTitle
                .ifBlank { text(MediaMetadata.METADATA_KEY_TITLE) }
                .ifBlank { text(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) }
        if (title.isBlank()) return null
        val artist =
            if (qqTitle.isNotBlank())
                text("ucar.media.metadata.UCAR_ARTIST")
                    .ifBlank { text(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) }
                    .ifBlank { text(MediaMetadata.METADATA_KEY_ARTIST) }
            else text(MediaMetadata.METADATA_KEY_ARTIST)
        return Track(
            title,
            artist,
            text(MediaMetadata.METADATA_KEY_ALBUM),
            metadata.getLong(MediaMetadata.METADATA_KEY_DURATION),
        )
    }
}
