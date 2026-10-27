package com.example.lyricsflow.playback

import android.media.MediaMetadata
import com.example.lyricsflow.core.Track

/** Preserve the highest-quality cover across transient metadata gaps for the same recording. */
internal class ArtworkMemory {
    private var key: String? = null
    private var image: android.graphics.Bitmap? = null

    fun select(trackKey: String, candidate: android.graphics.Bitmap?): android.graphics.Bitmap? {
        val previous = image?.takeUnless { it.isRecycled }
        val next = candidate?.takeUnless { it.isRecycled }
        image =
            if (key != trackKey) next
            else if (
                next != null &&
                    (previous == null ||
                        next.width.toLong() * next.height >=
                            previous.width.toLong() * previous.height)
            )
                next
            else previous
        key = trackKey
        return image
    }

    fun clear() {
        key = null
        image = null
    }
}

/** QQ's Bluetooth lyric mode replaces TITLE/ARTIST but keeps stable UCAR song metadata. */
object PlayerMetadata {
    fun artwork(metadata: MediaMetadata?): android.graphics.Bitmap? =
        listOf(
                MediaMetadata.METADATA_KEY_ART,
                MediaMetadata.METADATA_KEY_ALBUM_ART,
                MediaMetadata.METADATA_KEY_DISPLAY_ICON,
            )
            .mapNotNull { metadata?.getBitmap(it)?.takeUnless { bitmap -> bitmap.isRecycled } }
            .maxByOrNull { it.width.toLong() * it.height }

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
