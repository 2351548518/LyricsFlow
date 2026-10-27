package com.example.lyricsflow

import android.media.MediaMetadata
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lyricsflow.playback.PlayerMetadata
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerMetadataTest {
    @Test
    fun qqBluetoothLyricUpdatesKeepTheSameSong() {
        fun metadata(lyric: String) =
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, lyric)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Song name-Artist")
                .putString("ucar.media.metadata.UCAR_TITLE", "Song name")
                .putString("ucar.media.metadata.UCAR_ARTIST", "Artist")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, 286000)
                .build()
        val first = PlayerMetadata.track("com.tencent.qqmusic", metadata("First lyric"))!!
        val second = PlayerMetadata.track("com.tencent.qqmusic", metadata("Second lyric"))!!
        assertEquals("Song name", first.title)
        assertEquals("Artist", first.artist)
        assertEquals(286000L, first.duration)
        assertEquals(first.key, second.key)
    }

    @Test
    fun spotifyAndNormalQqKeepStandardFields() {
        val small =
            android.graphics.Bitmap.createBitmap(32, 32, android.graphics.Bitmap.Config.ARGB_8888)
        val large =
            android.graphics.Bitmap.createBitmap(640, 640, android.graphics.Bitmap.Config.ARGB_8888)
        val art =
            MediaMetadata.Builder()
                .putBitmap(MediaMetadata.METADATA_KEY_ART, small)
                .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, large)
                .build()
        assertEquals(640, PlayerMetadata.artwork(art)?.width)
        assertNull(PlayerMetadata.artwork(null))
        val memory = com.example.lyricsflow.playback.ArtworkMemory()
        assertSame(large, memory.select("song-a", large))
        assertSame(large, memory.select("song-a", null))
        assertSame(large, memory.select("song-a", small))
        assertNull(memory.select("song-b", null))
        assertSame(small, memory.select("song-b", small))
        memory.clear()
        assertNull(memory.select("song-b", null))
        val metadata =
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "Actual song")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Actual artist")
                .putString("ucar.media.metadata.UCAR_TITLE", "Vendor-only field")
                .build()
        assertEquals("Actual song", PlayerMetadata.track("com.spotify.music", metadata)?.title)
        val normal =
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "Actual song")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Actual artist")
                .putString("ucar.media.metadata.UCAR_TITLE", " ")
                .build()
        assertEquals("Actual song", PlayerMetadata.track("com.tencent.qqmusic", normal)?.title)
        assertEquals("Actual artist", PlayerMetadata.track("com.tencent.qqmusic", normal)?.artist)
        assertNull(PlayerMetadata.track("com.spotify.music", null))
    }
}
