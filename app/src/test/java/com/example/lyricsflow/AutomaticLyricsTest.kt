package com.example.lyricsflow

import com.example.lyricsflow.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AutomaticLyricsTest {
    @Test
    fun amllPrecedesLrclibEvenWhenThePreviousSourceWasLrclib() = runBlocking {
        val visited = mutableListOf<Provider>()
        val result =
            AutomaticLyrics.resolve(Provider.LRCLIB, true) { source ->
                visited += source
                if (source in listOf(Provider.QQ, Provider.NETEASE)) SourceLookup(reason = "未收录")
                else content(source)
            }
        assertEquals(listOf(Provider.QQ, Provider.NETEASE, Provider.AMLL), visited)
        assertEquals(Provider.AMLL, result.resolved!!.lyrics.source)
    }

    @Test
    fun stalledSourceTimesOutAndFallsBackWithoutCancellingResolution() = runBlocking {
        val visited = mutableListOf<Provider>()
        val result =
            AutomaticLyrics.resolve(Provider.QQ, true, sourceTimeoutMs = 30) { source ->
                visited += source
                if (source == Provider.QQ) kotlinx.coroutines.awaitCancellation()
                else content(source)
            }
        assertEquals(listOf(Provider.QQ, Provider.NETEASE), visited)
        assertEquals(Provider.NETEASE, result.resolved!!.lyrics.source)
        assertTrue(
            result.attempts.single().reason.contains("超时") ||
                result.attempts.single().reason.contains("超过")
        )
    }

    private fun content(source: Provider, timed: Boolean = true) =
        SourceLookup(
            ResolvedLyrics(
                Track("Song", provider = source),
                Lyrics(listOf(Line(if (timed) 0 else null, null, "Text")), source),
            )
        )

    @Test
    fun preferredOrderAndDisabledFallback() = runBlocking {
        assertEquals(
            listOf(Provider.QQ, Provider.NETEASE, Provider.AMLL, Provider.LRCLIB),
            AutomaticLyrics.order(Provider.LRCLIB, true),
        )
        val visited = mutableListOf<Provider>()
        val result =
            AutomaticLyrics.resolve(Provider.QQ, false) {
                visited += it
                SourceLookup(reason = "没有候选")
            }
        assertEquals(listOf(Provider.QQ), visited)
        assertNull(result.resolved)
    }

    @Test
    fun providerFailureDoesNotStopRemainingSources() = runBlocking {
        val visited = mutableListOf<Provider>()
        val result =
            AutomaticLyrics.resolve(Provider.QQ, true) {
                visited += it
                when (it) {
                    Provider.QQ -> SourceLookup(reason = "歌手别名无法确认", candidates = 30)
                    Provider.NETEASE -> throw IllegalStateException("HTTP 503")
                    Provider.AMLL -> SourceLookup(reason = "库中未收录")
                    else -> content(it)
                }
            }
        assertEquals(LYRIC_PROVIDERS, visited)
        assertEquals(Provider.LRCLIB, result.resolved?.lyrics?.source)
        assertEquals(3, result.attempts.size)
    }

    @Test
    fun synchronizedFallbackWinsButPlainTextIsNotLost() = runBlocking {
        val result =
            AutomaticLyrics.resolve(Provider.QQ, true) {
                if (it == Provider.QQ) content(it, false) else content(it)
            }
        assertEquals(Provider.NETEASE, result.resolved?.lyrics?.source)
        val plain =
            AutomaticLyrics.resolve(Provider.QQ, true) {
                if (it == Provider.QQ) content(it, false) else SourceLookup(reason = "无歌词")
            }
        assertEquals(Provider.QQ, plain.resolved?.lyrics?.source)
    }

    @Test
    fun failedLookupReportsEachSourceAndCancellationStopsImmediately() = runBlocking {
        val failure =
            AutomaticLyrics.resolve(Provider.QQ, true) {
                SourceLookup(reason = "没有对应版本", candidates = 4)
            }
        assertTrue(failure.failureMessage().contains("LRCLIB"))
        assertTrue(failure.failureMessage().contains("4 个候选"))
        val visited = mutableListOf<Provider>()
        try {
            AutomaticLyrics.resolve(Provider.QQ, true) {
                visited += it
                throw CancellationException("new song")
            }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertEquals(listOf(Provider.QQ), visited)
        }
    }
}
