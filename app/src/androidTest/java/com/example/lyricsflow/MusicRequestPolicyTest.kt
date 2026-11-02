package com.example.lyricsflow

import androidx.test.platform.app.InstrumentationRegistry
import com.example.lyricsflow.core.Track
import com.example.lyricsflow.core.digest
import com.example.lyricsflow.data.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MusicRequestPolicyTest {
    private open class Response(val status: Int = 200, val raw: String = "{}") :
        HttpURLConnection(URL("https://music-test.invalid/")) {
        val disconnected = CountDownLatch(1)
        val output = ByteArrayOutputStream()
        override fun connect() {}
        override fun usingProxy() = false
        override fun disconnect() { disconnected.countDown() }
        override fun getResponseCode() = status
        override fun getInputStream() = ByteArrayInputStream(raw.toByteArray(Charsets.UTF_8))
        override fun getOutputStream() = output
        override fun getHeaderField(name: String) = if (name == "Retry-After") "60" else null
        override fun getContentLengthLong() = raw.toByteArray(Charsets.UTF_8).size.toLong()
    }

    @Test
    fun cancellingStalledRequestDisconnectsAndDoesNotCacheLateResponse() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val connection = object : Response(raw = "{\"late\":true}") {
            override fun getResponseCode(): Int {
                entered.countDown()
                // Some vendor sockets ignore interruption. Caller cancellation must still return.
                while (true) try { release.await(); break } catch (_: InterruptedException) {}
                return 200
            }
        }
        val url = "https://music-test.invalid/cancel-${System.nanoTime()}"
        val store = Store(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            val api = MusicApi(store, MusicHttp { connection })
            val request = launch(Dispatchers.Default) { api.request(url) }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            withTimeout(1000) { request.cancelAndJoin() }
            assertTrue(connection.disconnected.await(3, TimeUnit.SECONDS))
            release.countDown()
            delay(100)
            assertNull(store.get("cache", digest(url)))
            assertTrue(request.isCancelled)
        } finally {
            release.countDown()
            store.remove("cache", digest(url))
            store.close()
        }
    }

    @Test
    fun hostRateLimitStillAllowsFreshCacheButNeverBypassesExplicitRefresh() = runBlocking {
        val root = "https://music-test.invalid/${System.nanoTime()}"
        val cached = "$root/cached"
        val limited = "$root/limited"
        val store = Store(InstrumentationRegistry.getInstrumentation().targetContext)
        var calls = 0
        val api = MusicApi(store, MusicHttp { calls++; Response(429) })
        try {
            store.put("cache", digest(cached), "{\"cached\":true}")
            try { api.request(limited); fail("Expected rate limit") } catch (_: ApiFailure) {}
            assertTrue(api.request(cached).getBoolean("cached"))
            try { api.request(cached, refresh = true); fail("Refresh must wait") } catch (_: ApiFailure) {}
            try { api.request(cached, bearer = "test-only"); fail("Authenticated request must not use public cache") } catch (_: ApiFailure) {}
            assertEquals(1, calls)
        } finally {
            store.remove("cache", digest(cached))
            store.close()
        }
    }

    @Test
    fun transportPreservesPostHeadersAndRejectsOversizedBodies() = runBlocking {
        val response = Response(raw = "{\"ok\":true}")
        val http = MusicHttp { response }
        val body = "{\"text\":\"你好\"}"
        assertEquals(200, http.send(URL("https://u.y.qq.com/test"), body, "test-only", 60000).status)
        assertEquals("POST", response.requestMethod)
        assertEquals("https://y.qq.com/", response.getRequestProperty("Referer"))
        assertEquals("Bearer test-only", response.getRequestProperty("Authorization"))
        assertEquals(body, response.output.toString("UTF-8"))
        assertFalse(response.instanceFollowRedirects)
        assertEquals(60000, response.readTimeout)
        val oversized = object : Response() {
            override fun getContentLengthLong() = 4_000_001L
            override fun getInputStream(): ByteArrayInputStream = error("Must reject before body allocation")
        }
        try {
            MusicHttp { oversized }.send(URL("https://music-test.invalid/"), null, null, 12000)
            fail("Oversized body accepted")
        } catch (_: IllegalArgumentException) {}
        assertTrue(oversized.disconnected.await(3, TimeUnit.SECONDS))
    }

    @Test
    fun recordHintsAreBoundedAndPreserveNativeAndRomanizationLabels() = runBlocking {
        val client = LrcLibClient({ _, _ -> null }, { _, _ -> }, LrcTransport { path ->
            val id = path.substringAfterLast('/')
            JSONObject().put("id", id).put("syncedLyrics", when (id) {
                "600" -> "[00:01.00]僕たちは光の向こうへ"
                "601" -> "[00:01.00]Aa konomama bokutachi no koe ga"
                else -> ""
            }).put("instrumental", id.toInt() < 600)
        })
        for (id in 1..601) client.record(id.toString())
        assertEquals("", client.hint(Track("old", sourceId = "1")))
        assertEquals("纯音乐", client.hint(Track("retained", sourceId = "2")))
        assertEquals("含日文同步原文", client.hint(Track("native", sourceId = "600")))
        assertEquals("拉丁字母歌词（可能为罗马音）", client.hint(Track("romaji", sourceId = "601")))
    }
}
