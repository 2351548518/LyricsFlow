package com.example.lyricsflow

import com.example.lyricsflow.core.*
import org.junit.Assert.*
import org.junit.Test

class SongSearchTest {
    private fun song(
        title: String,
        artist: String = "rionos",
        id: String = title,
        album: String = "Original",
        duration: Long = 286000,
    ) = Track(title, artist, album, duration, Provider.QQ, id, "001CNiOJ3VyAHu")

    @Test
    fun expandedQueriesHandleFullWidthAndTranslatedAliases() {
        val t = song("ウィアートル（旅人）")
        val queries = SongSearch.queries("${t.title} ${t.artist}", t)
        assertTrue(queries.contains("ウィアートル rionos"))
        assertTrue(queries.contains("ウィアートル"))
        assertTrue(queries.size <= 3)
        assertEquals("ウィアートル", SongSearch.withoutAliases("ウィアートル (旅人)"))
        assertEquals("fullwidth", SongSearch.normalized("ＦＵＬＬ－ＷＩＤＴＨ"))
    }

    @Test
    fun fuzzyRankingKeepsCandidatesAndDeduplicatesByProviderId() {
        val a = song("Love Story", "Taylor Swift", "1")
        val b = song("Another Song", "Someone", "2")
        val ranked = SongSearch.rank(listOf(b, a, a.copy(album = "Duplicate")), "Love Stroy")
        assertEquals("1", ranked.first().sourceId)
        assertEquals(2, ranked.size)
        assertTrue(ranked.contains(b))
        assertTrue(SongSearch.similarity("Love Stroy", "Love Story") > .7)
    }

    @Test
    fun translatedAliasesMatchButWrongArtistDurationAndVersionsDoNotBind() {
        val current = song("ウィアートル (旅人)")
        val correct = song("ウィアートル", id = "1")
        assertEquals(correct, SongSearch.automatic(current, listOf(correct)))
        assertNull(SongSearch.automatic(current, listOf(correct.copy(artist = "A cover singer"))))
        assertNull(SongSearch.automatic(current, listOf(correct.copy(duration = 310000))))
        assertNull(SongSearch.automatic(current, listOf(correct.copy(title = "ウィアートル (Live)"))))
        assertNull(SongSearch.automatic(song("Song"), listOf(song("Song (中文版)"))))
    }

    @Test
    fun ambiguousEditionsNeedAlbumOrManualConfirmation() {
        val first = song("Song", id = "1", album = "First")
        val second = song("Song", id = "2", album = "Second", duration = 289000)
        assertNull(SongSearch.automatic(song("Song", album = "Unknown"), listOf(first, second)))
        assertEquals(
            second,
            SongSearch.automatic(song("Song", album = "Second"), listOf(first, second)),
        )
        assertNull(SongSearch.automatic(song("Song", artist = ""), listOf(first)))
    }

    @Test
    fun avidCreditsUseAliasesAndCommasWithoutAcceptingDifferentVocalists() {
        val current = song("Avid", "SawanoHiroyuki[nZk], mizuki", duration = 249000)
        val candidate = song("Avid", "SawanoHiroyuki[nZk] / 瑞葵(mizuki)", duration = 250293)
        assertNotNull(SongSearch.automatic(current, listOf(candidate)))
        assertNull(
            SongSearch.automatic(
                current,
                listOf(candidate.copy(artist = "SawanoHiroyuki[nZk] / Laco")),
            )
        )
        assertNull(
            SongSearch.automatic(current, listOf(candidate.copy(artist = "SawanoHiroyuki[nZk]")))
        )
        assertTrue(
            SongSearch.queries("${current.title} ${current.artist}", current)
                .contains("Avid SawanoHiroyuki[nZk]")
        )
    }

    @Test
    fun sameRecordingReleasesNeedNotFailOnAlbumAliases() {
        val current =
            song("Avid", "SawanoHiroyuki[nZk], mizuki", album = "Unknown", duration = 249000)
        val one =
            song(
                "Avid",
                "SawanoHiroyuki[nZk] / 瑞葵(mizuki)",
                id = "1",
                album = "Single",
                duration = 250293,
            )
        val two = one.copy(sourceId = "2", album = "V", duration = 249000)
        assertEquals(two, SongSearch.automatic(current, listOf(one, two)))
        val hol = song("ホロネス", "美波", album = "カワキヲアメク", duration = 317000)
        assertNotNull(
            SongSearch.automatic(
                hol,
                listOf(song("ホロネス (空虚)", "美波 (みなみ)", album = "カワキヲアメク (声嘶力竭)", duration = 317000)),
            )
        )
    }
}
