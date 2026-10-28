package com.example.lyricsflow.data

import com.example.lyricsflow.core.*
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class ApiFailure(message: String) : Exception(message)

class MusicApi(private val store: Store) : LyricsLookup {
    var neteaseGateway = ""
    private val amll =
        AmllClient(
            read = { path, age -> store.get("cache", digest("amll:$path"), age) },
            write = { path, value -> store.put("cache", digest("amll:$path"), value) },
        )
    private val lrc =
        LrcLibClient(
            read = { path, age -> store.get("cache", digest("https://lrclib.net$path"), age) },
            write = { path, value -> store.put("cache", digest("https://lrclib.net$path"), value) },
        )

    fun lyricHint(track: Track): String =
        if (track.provider == Provider.LRCLIB) lrc.hint(track) else ""

    private val cooldowns = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /**
     * HTTPS only; bounded bytes and timeout; no cookies, remote scripts or credentials in
     * diagnostics.
     */
    suspend fun request(
        url: String,
        body: JSONObject? = null,
        bearer: String? = null,
        refresh: Boolean = false,
        readTimeoutMs: Int = 12000,
        cacheMaxAgeMs: Long = 6 * 60 * 60 * 1000L,
    ): JSONObject =
        withContext(Dispatchers.IO) {
            require(URL(url).protocol == "https") { "服务地址必须使用 HTTPS" }
            val host = URL(url).host
            val remaining = (cooldowns[host] ?: 0) - System.currentTimeMillis()
            if (remaining > 0) throw ApiFailure("服务要求限流等待，请在 ${(remaining/1000)+1} 秒后重试")
            val cacheId = digest(url + (body?.toString().orEmpty()))
            if (bearer == null && !refresh)
                store.get("cache", cacheId, cacheMaxAgeMs)?.let {
                    return@withContext JSONObject(it)
                }
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 5000
            c.readTimeout = readTimeoutMs.coerceIn(1000, 60000)
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "LyricsFlow/1.0 Android")
            c.setRequestProperty("Accept", "application/json")
            if (URL(url).host.endsWith("qq.com"))
                c.setRequestProperty("Referer", "https://y.qq.com/")
            if (URL(url).host == "music.163.com")
                c.setRequestProperty("Referer", "https://music.163.com/")
            if (bearer != null) c.setRequestProperty("Authorization", "Bearer $bearer")
            try {
                if (body != null) {
                    c.requestMethod = "POST"
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/json")
                    c.outputStream.use { it.write(body.toString().toByteArray()) }
                }
                val code = c.responseCode
                if (code == 429) {
                    val retry = c.getHeaderField("Retry-After")
                    val seconds = retry?.toLongOrNull()?.coerceIn(1, 604800) ?: 60
                    cooldowns[host] = System.currentTimeMillis() + seconds * 1000
                    throw ApiFailure("请求过于频繁，请在 $seconds 秒后重试")
                }
                if (code !in 200..299) throw ApiFailure("服务暂不可用（HTTP $code）")
                val bytes = c.inputStream.use { it.readBytesLimited(4_000_000) }
                val raw = String(bytes, Charsets.UTF_8).trim()
                val json =
                    if (raw.startsWith("[")) JSONObject().put("items", jsonArray(raw))
                    else jsonObject(raw)
                if (bearer == null) store.put("cache", cacheId, json.toString())
                json
            } catch (e: ApiFailure) {
                throw e
            } catch (e: Exception) {
                throw ApiFailure("${URL(url).host} 连接或响应失败（${e.javaClass.simpleName}），请稍后重试")
            } finally {
                c.disconnect()
            }
        }

    private suspend fun qq(
        slot: String,
        module: String,
        method: String,
        param: JSONObject,
        refresh: Boolean = false,
        common: JSONObject? = null,
    ): JSONObject {
        val b =
            JSONObject()
                .put(
                    "comm",
                    common ?: JSONObject().put("ct", 24).put("cv", 0).put("format", "json"),
                )
                .put(
                    slot,
                    JSONObject().put("module", module).put("method", method).put("param", param),
                )
        val root = request("https://u.y.qq.com/cgi-bin/musicu.fcg", b, refresh = refresh)
        if (root.optInt("code") != 0 || root.optJSONObject(slot)?.optInt("code") != 0)
            throw ApiFailure("QQ 音乐接口暂不可用；可更换来源或手动绑定歌曲")
        return root.getJSONObject(slot).getJSONObject("data")
    }

    suspend fun search(provider: Provider, query: String): List<Track> =
        searchPage(provider, query).tracks

    override suspend fun searchExpanded(
        provider: Provider,
        query: String,
        current: Track?,
    ): List<Track> {
        if (provider == Provider.AMLL) return amll.search(query, current, size = 50).tracks
        if (provider == Provider.LRCLIB && current != null) {
            return lrc.preferOriginal(current, lrc.searchOriginal(current).rows)
        }
        val all = mutableListOf<Track>()
        var failure: Exception? = null
        for (variant in SongSearch.queries(query, current)) {
            try {
                all += search(provider, variant)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                failure = e
            }
            if (current != null && SongSearch.automatic(current, all) != null) break
        }
        if (all.isEmpty() && failure != null) throw failure
        return SongSearch.rank(all, query, current)
    }

    suspend fun searchPage(
        provider: Provider,
        query: String,
        page: Int = 0,
        size: Int = 30,
        channel: String = "",
        current: Track? = null,
    ): SearchPage {
        require(query.isNotBlank()) { "请输入歌名或歌手" }
        require(query.length <= 500 && page in 0..99 && size in 1..50) { "搜索关键词或分页超出范围" }
        return when (provider) {
            Provider.AMLL -> amll.search(query, current, page, size)
            Provider.NETEASE -> {
                val url =
                    if (neteaseGateway.isBlank())
                        "https://music.163.com/api/search/get/web?s=${enc(query)}&type=1&limit=$size&offset=${page*size}"
                    else
                        "${neteaseGateway.trimEnd('/')}/search?keywords=${enc(query)}&type=1&limit=$size&offset=${page*size}"
                val r = request(url, cacheMaxAgeMs = 5 * 60 * 1000L)
                if (r.optInt("code") != 200) throw ApiFailure("网易云搜索返回 ${r.optInt("code")}")
                val a = r.optJSONObject("result")?.optJSONArray("songs") ?: JSONArray()
                val tracks =
                    (0 until a.length().coerceAtMost(size)).map { n ->
                        val s = a.getJSONObject(n)
                        val artists =
                            s.optJSONArray("artists") ?: s.optJSONArray("ar") ?: JSONArray()
                        Track(
                            s.optString("name"),
                            (0 until artists.length()).joinToString(" / ") {
                                artists.getJSONObject(it).optString("name")
                            },
                            (s.optJSONObject("album") ?: s.optJSONObject("al"))
                                ?.optString("name")
                                .orEmpty(),
                            s.optLong("duration", s.optLong("dt")),
                            provider,
                            s.optString("id"),
                        )
                    }
                val total = r.optJSONObject("result")?.optInt("songCount", -1)?.takeIf { it >= 0 }
                SearchPage(
                    tracks,
                    if (total != null) (page + 1) * size < total else tracks.size == size,
                    total,
                    "netease",
                )
            }
            Provider.QQ -> {
                // Full search is different from Smartbox suggestions, which usually contain four
                // songs.
                if (channel.isBlank() || channel == "qq-full") {
                    try {
                        val r =
                            request(
                                "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?w=${enc(query)}&format=json&n=$size&p=${page+1}&cr=1&t=0&g_tk=5381",
                                cacheMaxAgeMs = 5 * 60 * 1000L,
                            )
                        if (r.optInt("code", -1) != 0) throw ApiFailure("QQ 完整搜索暂不可用")
                        val song =
                            r.optJSONObject("data")?.optJSONObject("song")
                                ?: throw ApiFailure("QQ 搜索响应缺少歌曲列表")
                        val a = song.optJSONArray("list") ?: JSONArray()
                        val rows =
                            (0 until a.length().coerceAtMost(size)).map {
                                qqTrack(a.getJSONObject(it))
                            }
                        val total = song.optInt("totalnum", -1).takeIf { it >= 0 }
                        if (rows.isNotEmpty() || page > 0 || channel == "qq-full")
                            return SearchPage(
                                rows,
                                rows.isNotEmpty() &&
                                    if (total != null) (page + 1) * size < total
                                    else rows.size == size,
                                total,
                                "qq-full",
                            )
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        if (page > 0 || channel == "qq-full") throw e
                    }
                }
                if (page > 0 && channel != "qq-desktop") return SearchPage(emptyList())
                val p =
                    JSONObject()
                        .put("query", query)
                        .put("search_type", 0)
                        .put("num_per_page", size)
                        .put("page_num", page + 1)
                        .put("highlight", false)
                        .put("grp", 1)
                val d =
                    try {
                        qq(
                            "search",
                            "music.search.SearchCgiService",
                            "DoSearchForQQMusicDesktop",
                            p,
                        )
                    } catch (e: ApiFailure) {
                        JSONObject()
                    }
                val a =
                    d.optJSONObject("body")?.optJSONObject("song")?.optJSONArray("list")
                        ?: JSONArray()
                if (a.length() > 0)
                    SearchPage(
                        (0 until a.length().coerceAtMost(size)).map { n ->
                            qqTrack(a.getJSONObject(n))
                        },
                        a.length() >= size,
                        channel = "qq-desktop",
                    )
                else {
                    if (page > 0) return SearchPage(emptyList(), channel = "qq-desktop")
                    // The desktop endpoint can report success with no songs. Smartbox is a separate
                    // public lookup.
                    val r =
                        request(
                            "https://c.y.qq.com/splcloud/fcgi-bin/smartbox_new.fcg?key=${enc(query)}&format=json"
                        )
                    if (r.optInt("code") != 0) throw ApiFailure("QQ 搜索暂不可用，请通过 songmid 绑定")
                    val items =
                        r.optJSONObject("data")?.optJSONObject("song")?.optJSONArray("itemlist")
                            ?: JSONArray()
                    val tracks = mutableListOf<Track>()
                    for (n in 0 until items.length().coerceAtMost(20)) {
                        val item = items.getJSONObject(n)
                        try {
                            tracks += resolveQQ(item.getString("mid"))
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                        }
                    }
                    SearchPage(
                        tracks,
                        channel = "qq-suggestions",
                        note = "完整搜索未返回结果，当前为搜索建议；可尝试简化关键词或切换来源。",
                    )
                }
            }
            Provider.LRCLIB -> {
                val context = current?.takeIf {
                    SongSearch.normalized(query) ==
                        SongSearch.normalized("${it.title} ${it.artist}")
                }
                val result = if (context != null) lrc.searchOriginal(context) else lrc.search(query)
                val tracks =
                    if (context != null) lrc.orderedSearch(context, result.rows)
                    else result.rows.map(lrc::track)
                SearchPage(
                    tracks.drop(page * size).take(size),
                    (page + 1) * size < result.rows.size,
                    null, // LRCLIB returns an array, not the catalogue's total hit count.
                    "lrclib",
                    if (result.stale) "LRCLIB 暂不可用，显示最近保存的搜索结果。" else "",
                )
            }
            else -> SearchPage(emptyList())
        }
    }

    private fun qqTrack(s: JSONObject): Track {
        val singers = s.optJSONArray("singer") ?: JSONArray()
        return Track(
            s.optString("title", s.optString("name", s.optString("songname"))),
            (0 until singers.length()).joinToString(" / ") {
                singers.getJSONObject(it).optString("name")
            },
            s.optJSONObject("album")?.optString("name") ?: s.optString("albumname"),
            s.optLong("interval") * 1000,
            Provider.QQ,
            s.optString("id", s.optString("songid")),
            s.optString("mid", s.optString("songmid")),
        )
    }

    suspend fun resolveQQ(mid: String): Track {
        require(Regex("[A-Za-z0-9]{10,20}").matches(mid)) { "请输入 QQ 音乐 songmid（不是数字 songid）" }
        val r =
            qq(
                "detail",
                "music.pf_song_detail_svr",
                "get_song_detail_yqq",
                JSONObject().put("song_mid", mid).put("song_id", 0).put("song_type", 0),
            )
        return qqTrack(r.getJSONObject("track_info"))
    }

    suspend fun resolveNet(id: String): Track {
        require(Regex("[0-9]{1,20}").matches(id)) { "请输入网易云歌曲数字 ID" }
        val r = request("https://music.163.com/api/song/detail/?id=$id&ids=%5B$id%5D")
        val s = r.getJSONArray("songs").getJSONObject(0)
        val a = s.optJSONArray("artists") ?: JSONArray()
        return Track(
            s.getString("name"),
            (0 until a.length()).joinToString(" / ") { a.getJSONObject(it).optString("name") },
            s.optJSONObject("album")?.optString("name").orEmpty(),
            s.optLong("duration"),
            Provider.NETEASE,
            id,
        )
    }

    suspend fun resolveLrc(id: String): Track {
        return lrc.track(lrc.record(id))
    }

    suspend fun resolveAmll(value: String): Track = amll.resolve(value)

    override suspend fun lyrics(t: Track, refresh: Boolean): Lyrics {
        return when (t.provider) {
            Provider.AMLL -> amll.lyrics(t, refresh)
            Provider.NETEASE -> {
                val url =
                    if (neteaseGateway.isBlank())
                        "https://music.163.com/api/song/lyric?id=${t.sourceId}&lv=-1&kv=-1&tv=-1"
                    else "${neteaseGateway.trimEnd('/')}/lyric/new?id=${t.sourceId}"
                val r = request(url, refresh = refresh)
                if (r.optInt("code") != 200) throw ApiFailure("网易云歌词暂不可用")
                val yrc = r.optJSONObject("yrc")?.optString("lyric").orEmpty()
                val lrc = r.optJSONObject("lrc")?.optString("lyric").orEmpty()
                val rows =
                    if (yrc.isNotBlank()) LyricParser.yrc(yrc, t.duration)
                    else LyricParser.lrc(lrc, t.duration)
                Lyrics(
                    LyricParser.merge(
                        rows,
                        r.optJSONObject("tlyric")?.optString("lyric").orEmpty(),
                        r.optJSONObject("romalrc")?.optString("lyric").orEmpty(),
                        t.duration,
                    ),
                    t.provider,
                    r.optBoolean("nolyric"),
                )
            }
            Provider.QQ -> {
                require(t.mid.isNotBlank()) { "QQ 歌词需要 songmid，请重新匹配" }
                try {
                    val data =
                        qq(
                            "lyric",
                            "music.musichallSong.PlayLyricInfo",
                            "GetPlayLyricInfo",
                            JSONObject()
                                .put("songMid", t.mid)
                                .put("crypt", 0)
                                .put("qrc", 0)
                                .put("trans", 1)
                                .put("roma", 0)
                                .put("lrc_t", 0)
                                .put("qrc_t", 0)
                                .put("trans_t", 0)
                                .put("roma_t", 0)
                                .put("type", 1),
                            refresh,
                            JSONObject().put("ct", 24).put("cv", 4747474).put("format", "json"),
                        )
                    val lyrics = QqLyricCodec.lyrics(data, t.duration)
                    if (lyrics.lines.isNotEmpty()) return lyrics
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    // Preserve the legacy line-lyric path when the newer service is unavailable.
                }
                val r =
                    request(
                        "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=${enc(t.mid)}&format=json&nobase64=1&g_tk=5381",
                        refresh = refresh,
                    )
                if (r.optInt("code") != 0) throw ApiFailure("QQ 歌词暂不可用")
                QqLyricCodec.lyrics(r, t.duration, revision = 1)
            }
            Provider.LRCLIB -> {
                lrc.lyrics(lrc.record(t.sourceId, refresh), t.duration)
            }
            else -> throw ApiFailure("请选择网络来源或导入歌词文件")
        }
    }

    suspend fun comments(
        t: Track,
        hot: Boolean,
        page: Int,
        cursor: String = "",
        refresh: Boolean = false,
    ): CommentPage {
        return when (t.provider) {
            Provider.QQ -> {
                require(t.sourceId.matches(Regex("\\d+"))) { "QQ 评论需要数字 songid；songmid 无法直接用于评论" }
                val d =
                    qq(
                        "comments",
                        "music.globalComment.CommentRead",
                        if (hot) "GetHotCommentList" else "GetNewCommentList",
                        JSONObject()
                            .put("BizType", 1)
                            .put("BizId", t.sourceId)
                            .put("LastCommentSeqNo", cursor)
                            .put("PageSize", 20)
                            .put("PageNum", page)
                            .put("HotType", 1)
                            .put("WithAirborne", 0)
                            .put("PicEnable", 1),
                        refresh,
                    )
                val list = d.optJSONObject("CommentList") ?: throw ApiFailure("QQ 评论响应结构已变化")
                val a = list.optJSONArray("Comments") ?: JSONArray()
                val rows =
                    (0 until a.length())
                        .map { n ->
                            val c = a.getJSONObject(n)
                            Comment(
                                c.optString("CmId"),
                                c.optString("Nick", "听友"),
                                c.optString("Content"),
                                c.optInt("PraiseNum"),
                                c.optLong("PubTime") * 1000,
                                c.optString("SeqNo"),
                            )
                        }
                        .filter { it.id.isNotBlank() && it.text.isNotBlank() }
                CommentPage(rows, list.optInt("HasMore") == 1, rows.lastOrNull()?.cursor.orEmpty())
            }
            Provider.NETEASE -> {
                val id = t.sourceId
                val url =
                    if (neteaseGateway.isBlank())
                        "https://music.163.com/api/v1/resource/${if(hot)"hotcomments" else "comments"}/R_SO_4_$id?limit=20&offset=${page*20}"
                    else
                        "${neteaseGateway.trimEnd('/')}/comment/${if(hot)"hot" else "music"}?id=$id&type=0&limit=20&offset=${page*20}"
                val r = request(url, refresh = refresh)
                if (r.optInt("code") != 200) throw ApiFailure("网易云评论暂不可用（${r.optInt("code")}）")
                val a = r.optJSONArray(if (hot) "hotComments" else "comments") ?: JSONArray()
                val rows =
                    (0 until a.length()).map { n ->
                        val c = a.getJSONObject(n)
                        Comment(
                            c.optString("commentId"),
                            c.optJSONObject("user")?.optString("nickname") ?: "听友",
                            c.optString("content"),
                            c.optInt("likedCount"),
                            c.optLong("time"),
                        )
                    }
                CommentPage(
                    rows,
                    r.optBoolean(
                        "hasMore",
                        r.optBoolean("more", (page + 1) * 20 < r.optInt("total")),
                    ),
                )
            }
            else -> throw ApiFailure("该来源不提供评论，请切换 QQ 音乐或网易云音乐")
        }
    }
}

fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val n = read(buffer)
        if (n < 0) break
        require(out.size() + n <= limit) { "文件或响应超过限制" }
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}
