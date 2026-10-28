package com.example.lyricsflow

import com.example.lyricsflow.core.*
import com.example.lyricsflow.data.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LrcLibClientTest {
    @Test
    fun httpDeadlineDisconnectsAStalledConnection() = runBlocking {
        val disconnected = java.util.concurrent.CountDownLatch(1)
        val entered = java.util.concurrent.CountDownLatch(1)
        val connection =
            object :
                java.net.HttpURLConnection(java.net.URL("https://lrclib.net/api/search?q=test")) {
                override fun connect() {}

                override fun usingProxy() = false

                override fun disconnect() {
                    disconnected.countDown()
                }

                override fun getResponseCode(): Int {
                    entered.countDown()
                    // Emulate vendor I/O that ignores a thread interrupt, until disconnect releases
                    // it.
                    while (disconnected.count > 0) try {
                        disconnected.await()
                    } catch (_: InterruptedException) {}
                    throw java.io.IOException("Disconnected")
                }
            }
        val transport = LrcHttp(timeoutMs = 150, open = { connection })
        val start = android.os.SystemClock.elapsedRealtime()
        try {
            transport.get("/api/search?q=test")
            fail()
        } catch (e: LrcFailure) {
            assertEquals(0, e.status)
        }
        assertEquals(0L, entered.count)
        assertTrue(disconnected.await(1000, java.util.concurrent.TimeUnit.MILLISECONDS))
        assertTrue(android.os.SystemClock.elapsedRealtime() - start < 1500)
    }

    private val song = Track("Dream Lantern", "RADWIMPS", "Your Name.", 131000, Provider.LRCLIB)

    private fun record(
        id: Int,
        text: String,
        duration: Double = 131.0,
        title: String = song.title,
        artist: String = song.artist,
    ) =
        JSONObject()
            .put("id", id)
            .put("trackName", title)
            .put("artistName", artist)
            .put("albumName", song.album)
            .put("duration", duration)
            .put("syncedLyrics", text)
            .put("plainLyrics", JSONObject.NULL)

    private fun response(vararg rows: JSONObject) =
        JSONObject().put("items", JSONArray(rows.toList()))

    private class Cache {
        var clock = 1000000L
        val values = mutableMapOf<String, Pair<Long, String>>()

        fun client(transport: LrcTransport, pause: suspend (Long) -> Unit = {}) =
            LrcLibClient(
                { path, age -> values[path]?.takeIf { clock - it.first <= age }?.second },
                { path, raw -> values[path] = clock to raw },
                transport,
                { clock },
                pause,
            )
    }

    @Test
    fun retriesOne503AndReusesLyricsInSearchResponse() = runBlocking {
        val cache = Cache()
        var calls = 0
        val waits = mutableListOf<Long>()
        val row = record(1, "[00:01.00]あぁ このまま声が")
        val client =
            cache.client(
                LrcTransport {
                    calls++
                    if (calls == 1) throw LrcFailure(503, 1000)
                    response(row)
                },
                { waits += it },
            )
        client.search("Dream Lantern", song)
        assertEquals(2, calls)
        assertEquals(listOf(1000L), waits)
        val lyrics = client.lyrics(client.record("1"), song.duration)
        assertEquals(2, calls)
        assertEquals("あぁ このまま声が", lyrics.lines.single().text)
        assertEquals(LrcLibClient.REVISION, lyrics.providerRevision)
    }

    @Test
    fun repeated503UsesStaleCacheAndBackoffButFreshCacheStillWorks() = runBlocking {
        val cache = Cache()
        var calls = 0
        var unavailable = false
        val client =
            cache.client(
                LrcTransport {
                    calls++
                    if (unavailable) throw LrcFailure(503)
                    response(record(1, "[00:01.00]test lyrics"))
                }
            )
        client.search("one")
        client.search("fresh")
        cache.clock += LrcLibClient.FRESH + 1
        unavailable = true
        assertTrue(client.search("one").stale)
        assertEquals(4, calls)
        assertTrue(client.search("one").stale)
        assertEquals(4, calls)
        cache.values["/api/search?q=fresh"] =
            cache.clock to response(record(2, "lyrics")).toString()
        assertFalse(client.search("fresh").stale)
        assertEquals(4, calls)
        try {
            client.search("uncached")
            fail("Expected backoff")
        } catch (e: ApiFailure) {
            assertTrue(e.message!!.contains("秒后"))
        }
        assertEquals(4, calls)
    }

    @Test
    fun respectsLongRetryAfterAndDoesNotRetry404() = runBlocking {
        val cache = Cache()
        var calls = 0
        val client =
            cache.client(
                LrcTransport {
                    calls++
                    throw LrcFailure(503, 30000)
                },
                { fail("Must not sleep for long Retry-After") },
            )
        try {
            client.search("query")
            fail()
        } catch (_: ApiFailure) {}
        assertEquals(1, calls)
        cache.clock += 30001
        try {
            client.search("query")
            fail()
        } catch (_: ApiFailure) {}
        assertEquals(2, calls)
        val notFound =
            cache.client(
                LrcTransport {
                    calls++
                    throw LrcFailure(404)
                }
            )
        try {
            notFound.record("999")
            fail()
        } catch (e: LrcFailure) {
            assertEquals(404, e.status)
        }
        assertEquals(3, calls)
        assertTrue(cache.values.isEmpty())
        assertEquals(1000L, retryAfter("1"))
        assertEquals(1000L, retryAfter("Thu, 01 Jan 1970 00:00:02 GMT", 1000))
    }

    @Test
    fun duplicateConcurrentRequestsShareTheSuccessfulCache() = runBlocking {
        val cache = Cache()
        var calls = 0
        val client =
            cache.client(
                LrcTransport {
                    calls++
                    delay(30)
                    response()
                }
            )
        coroutineScope { repeat(6) { launch { client.search("same") } } }
        assertEquals(1, calls)
    }

    @Test
    fun cancelledRequestDoesNotRetryOrCacheAndReleasesLock() = runBlocking {
        val cache = Cache()
        var calls = 0
        val entered = CompletableDeferred<Unit>()
        val client =
            cache.client(
                LrcTransport {
                    calls++
                    if (calls == 1) {
                        entered.complete(Unit)
                        awaitCancellation()
                    }
                    response()
                }
            )
        val job = launch { client.search("query") }
        entered.await()
        job.cancelAndJoin()
        assertTrue(cache.values.isEmpty())
        client.search("query")
        assertEquals(2, calls)
    }

    @Test
    fun prefersNativeLyricsOnlyWithinMatchingRecording() {
        val client = Cache().client(LrcTransport { response() })
        val roman = record(1, "[00:01.00]Aa konomama bokutachi no koe ga")
        val native = record(2, "[00:01.00]あぁ このまま僕たちの声が", 129.0)
        assertEquals(
            listOf("2"),
            client.preferOriginal(song, listOf(roman, native)).map { it.sourceId },
        )
        val anotherNative = record(5, "[00:01.00]あぁ このまま僕たちの声が", 131.666667)
        assertEquals(
            "5",
            SongSearch.automatic(
                    song,
                    client.preferOriginal(song, listOf(roman, native, anotherNative)),
                )!!
                .sourceId,
        )
        val wrong =
            listOf(
                native.put("duration", 300.0),
                record(3, "あぁ このまま僕たちの声が", artist = "Cover"),
                record(4, "あぁ このまま僕たちの声が", title = "Dream Lantern (Live)"),
            )
        assertEquals(
            "1",
            SongSearch.automatic(song, client.preferOriginal(song, listOf(roman) + wrong))!!
                .sourceId,
        )
        val english = song.copy(title = "Dream Lantern (English ver.)")
        val latin = roman.put("trackName", english.title)
        assertEquals(
            "1",
            SongSearch.automatic(english, client.preferOriginal(english, listOf(latin, native)))!!
                .sourceId,
        )
    }

    @Test
    fun doesNotCallRomanizedTextOriginalWhenNativePlainIsAvailable() {
        val client = Cache().client(LrcTransport { response() })
        val row =
            record(1, "[00:01.00]Aa konomama bokutachi no koe ga")
                .put("plainLyrics", "あぁ このまま僕たちの声が\n僕たちの明日へ")
        val lyrics = client.lyrics(row, 131000)
        assertEquals("あぁ このまま僕たちの声が", lyrics.lines.first().text)
        assertTrue(
            "Unrelated native text must not inherit romaji timestamps",
            lyrics.lines.all { it.start == null },
        )
        val english = client.lyrics(record(2, "[00:01.00]Let the morning find us slowly"), 131000)
        assertEquals(1000L, english.lines.single().start)
        val empty =
            client.lyrics(
                JSONObject()
                    .put("syncedLyrics", JSONObject.NULL)
                    .put("plainLyrics", JSONObject.NULL),
                0,
            )
        assertTrue(empty.lines.isEmpty())
    }
}
