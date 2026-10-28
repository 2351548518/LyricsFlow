package com.example.lyricsflow

import com.example.lyricsflow.core.*
import com.example.lyricsflow.data.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AmllClientTest {
    private val file = "1730652389036-69615623-d1511ea9.ttml"
    private val xml =
        """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata"><body><div begin="10s"><p begin="10s" end="12s"><span begin="10s" end="11s">Hello</span> <span begin="11s" end="12s">world</span><span ttm:role="x-translation" xml:lang="zh-CN">你好，世界</span></p></div></body></tt>"""

    private fun entry(name: String = "Sunny day", id: String = file) =
        JSONObject()
            .put("rawLyricFile", id)
            .put(
                "metadata",
                JSONArray(
                    listOf(
                        listOf("musicName", listOf(name, "晴天")),
                        listOf("artists", listOf("Singer", "歌手")),
                        listOf("album", listOf("Album")),
                        listOf("ncmMusicId", listOf("186016")),
                        listOf("qqMusicId", listOf("97773")),
                        listOf("spotifyId", listOf("0F02KChKwbcQ3tk4q1YxLH")),
                    )
                ),
            )

    private fun client(
        raw: String = entry().toString(),
        transport: RepositoryTransport? = null,
    ): AmllClient {
        val values = mutableMapOf<String, String>()
        return AmllClient(
            { key, _ -> values[key] },
            { key, value -> values[key] = value },
            transport
                ?: RepositoryTransport { path, _ -> if (path == AmllClient.INDEX) raw else xml },
        )
    }

    @Test
    fun metadataAliasesMatchCompleteCreditsAndSearchIsFuzzy() = runBlocking {
        val old = "1700000000000-1-abcd.ttml"
        val api = client(entry(id = old).toString() + "\n" + entry())
        val current = Track("晴天", "歌手", "Album", 180000)
        val results = api.search("晴天 歌手", current)
        assertEquals(file, SongSearch.automatic(current, results.tracks)!!.sourceId)
        assertTrue(api.search("Sunny dy").tracks.isNotEmpty())
        assertEquals(Provider.AMLL, api.search("Sunny day").tracks.first().provider)
        assertEquals(file, api.resolve("186016").sourceId)
        assertEquals(old, api.resolve(old).sourceId)
    }

    @Test
    fun resolvesPlatformIdsAndRejectsUntrustedLinks() = runBlocking {
        val api = client()
        for (key in
            listOf(
                file,
                "186016",
                "97773",
                "0F02KChKwbcQ3tk4q1YxLH",
                "https://music.163.com/song?id=186016",
                "https://open.spotify.com/track/0F02KChKwbcQ3tk4q1YxLH",
            )) assertEquals(file, api.resolve(key).sourceId)
        try {
            api.resolve("https://evil.example/song/186016")
            fail()
        } catch (_: IllegalStateException) {}
        try {
            api.resolve("../../secret.ttml")
            fail()
        } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun cachesIndexAndLyricsWithoutMixingTranslationIntoOriginal() = runBlocking {
        var calls = 0
        val api =
            client(
                transport =
                    RepositoryTransport { path, _ ->
                        calls++
                        if (path == AmllClient.INDEX) entry().toString() else xml
                    }
            )
        val track = api.resolve("186016")
        val lyrics = api.lyrics(track)
        assertEquals(2, calls)
        assertEquals("Hello world", lyrics.lines.single().text)
        assertEquals("你好，世界", lyrics.lines.single().translation)
        assertEquals(10000L, lyrics.lines.single().words.first().start)
        api.search("Sunny")
        api.lyrics(track)
        assertEquals(2, calls)
    }

    @Test
    fun offlineUsesValidatedCacheAndInvalidXmlCannotPoisonIt() = runBlocking {
        val values = mutableMapOf(AmllClient.INDEX to entry().toString(), "raw-lyrics/$file" to xml)
        val api =
            AmllClient(
                { key, age -> values[key]?.takeIf { age > AmllClient.DAY } },
                { key, value -> values[key] = value },
                RepositoryTransport { _, _ -> throw ApiFailure("offline") },
            )
        val page = api.search("Sunny")
        assertTrue(page.note.contains("本地索引"))
        assertEquals("Hello world", api.lyrics(page.tracks.first(), true).lines.single().text)
        val bad =
            AmllClient(
                { key, _ -> values[key] },
                { key, value -> values[key] = value },
                RepositoryTransport { _, _ -> "<html>bad</html>" },
            )
        assertEquals("Hello world", bad.lyrics(page.tracks.first(), true).lines.single().text)
        assertEquals(xml, values["raw-lyrics/$file"])
    }

    @Test
    fun cancellationDoesNotTurnIntoStaleSuccessAndIndexPathsAreValidated(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val api =
            client(
                transport =
                    RepositoryTransport { _, _ ->
                        entered.complete(Unit)
                        awaitCancellation()
                    }
            )
        val work = launch { api.search("query") }
        entered.await()
        work.cancelAndJoin()
        assertTrue(work.isCancelled)
        val parsed = AmllClient.parseIndex(entry().toString() + "\n" + entry(id = "../../bad.ttml"))
        assertEquals(1, parsed.size)
        val anthology =
            entry()
                .put(
                    "metadata",
                    JSONArray(
                        listOf(
                            listOf("musicName", listOf("Song")),
                            listOf("artists", listOf("Artist")),
                            listOf("album", (1..238).map { "Album $it" }),
                        )
                    ),
                )
        assertEquals(238, AmllClient.parseIndex(anthology.toString()).single().albums.size)
        assertThrows(IllegalArgumentException::class.java) { AmllClient.parseIndex("") }
    }
}
