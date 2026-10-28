package com.example.lyricsflow.data

import com.example.lyricsflow.core.*
import java.net.URI
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

internal data class AmllEntry(val file: String, val metadata: Map<String, List<String>>) {
    val identities =
        metadata
            .filterKeys { it in listOf("ncmMusicId", "qqMusicId", "spotifyId", "appleMusicId") }
            .flatMap { (kind, ids) -> ids.map { "$kind:$it" } }
            .toSet()
    val titles
        get() = metadata["musicName"].orEmpty()

    val artists
        get() = metadata["artists"].orEmpty()

    val albums
        get() = metadata["album"].orEmpty()

    val normalized = (titles + artists + albums).joinToString(" ") { SongSearch.normalized(it) }

    fun track(current: Track? = null): Track {
        val title =
            titles.firstOrNull {
                current != null &&
                    SongSearch.normalized(SongSearch.withoutAliases(it)) ==
                        SongSearch.normalized(SongSearch.withoutAliases(current.title))
            } ?: titles.first()
        val credits = (listOf(artists.joinToString(" / ")) + artists).distinct()
        val artist =
            credits.firstOrNull {
                current != null &&
                    SongSearch.automatic(
                        current,
                        listOf(
                            Track(
                                title,
                                it,
                                duration = 0,
                                provider = Provider.AMLL,
                                sourceId = file,
                            )
                        ),
                    ) != null
            } ?: credits.first()
        val album =
            albums.firstOrNull {
                current != null && SongSearch.normalized(it) == SongSearch.normalized(current.album)
            } ?: albums.firstOrNull().orEmpty()
        return Track(title, artist, album, provider = Provider.AMLL, sourceId = file)
    }
}

internal class AmllClient(
    private val read: (String, Long) -> String?,
    private val write: (String, String) -> Unit,
    private val transport: RepositoryTransport = RepositoryHttp(),
) {
    companion object {
        const val INDEX = "metadata/raw-lyrics-index.jsonl"
        const val DAY = 24 * 60 * 60 * 1000L
        private val filePattern = Regex("[A-Za-z0-9_-]{1,160}\\.ttml")

        fun parseIndex(raw: String): List<AmllEntry> {
            require(raw.length <= 8_000_000) { "AMLL 索引过大" }
            val rows = raw.lineSequence().filter { it.isNotBlank() }.take(20001).toList()
            require(rows.size <= 20000) { "AMLL 索引记录过多" }
            val entries =
                rows
                    .mapNotNull { line ->
                        val o = JSONObject(line)
                        val file = o.getString("rawLyricFile")
                        if (!filePattern.matches(file)) return@mapNotNull null
                        val a = o.getJSONArray("metadata")
                        require(a.length() <= 100)
                        val metadata = mutableMapOf<String, List<String>>()
                        for (i in 0 until a.length()) {
                            val pair = a.getJSONArray(i)
                            val values = pair.getJSONArray(1)
                            require(values.length() <= 1000) { "AMLL 单字段值过多" }
                            metadata[pair.getString(0)] =
                                (0 until values.length()).map { values.getString(it).take(1000) }
                        }
                        AmllEntry(file, metadata).takeIf {
                            it.titles.isNotEmpty() && it.artists.isNotEmpty()
                        }
                    }
                    .distinctBy { it.file }
            require(entries.isNotEmpty()) { "AMLL 索引为空或格式已改变" }
            return entries
        }
    }

    private val lock = Mutex()
    private var loaded: List<AmllEntry> = emptyList()
    private var loadedAt = 0L
    private var offline = false

    private suspend fun index(): List<AmllEntry> =
        withContext(Dispatchers.IO) {
            lock.withLock {
                if (loaded.isNotEmpty() && System.currentTimeMillis() - loadedAt < DAY)
                    return@withLock loaded
                val cached = read(INDEX, DAY)
                if (cached != null) {
                    loaded = parseIndex(cached)
                    loadedAt = System.currentTimeMillis()
                    offline = false
                    return@withLock loaded
                }
                try {
                    val raw = transport.get(INDEX, 8_000_000)
                    val parsed = parseIndex(raw) // Validate before replacing a usable cache.
                    write(INDEX, raw)
                    loaded = parsed
                    offline = false
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    val old = read(INDEX, 14 * DAY)
                    if (old == null && loaded.isEmpty()) throw e
                    if (old != null) loaded = parseIndex(old)
                    offline = true
                }
                loadedAt = System.currentTimeMillis() - if (offline) DAY - 60000 else 0
                loaded
            }
        }

    suspend fun search(
        query: String,
        current: Track? = null,
        page: Int = 0,
        size: Int = 30,
    ): SearchPage =
        withContext(Dispatchers.Default) {
            val seen = mutableSetOf<String>()
            // The upstream index is oldest-to-newest and includes historical submissions.
            // Prefer the latest file sharing a platform recording ID, not an obsolete upload.
            val index =
                index()
                    .asReversed()
                    .filter { row ->
                        val keep = row.identities.isEmpty() || row.identities.none { it in seen }
                        seen.addAll(row.identities)
                        keep
                    }
                    .asReversed()
            val q = SongSearch.normalized(query)
            val tokens =
                query.split(Regex("\\s+")).map(SongSearch::normalized).filter { it.isNotBlank() }
            val contextual = current?.takeIf {
                q == SongSearch.normalized("${it.title} ${it.artist}") ||
                    q == SongSearch.normalized(it.title)
            }
            val exact =
                if (contextual != null)
                    index.filter { entry ->
                        entry.titles.any {
                            SongSearch.normalized(SongSearch.withoutAliases(it)) ==
                                SongSearch.normalized(SongSearch.withoutAliases(contextual.title))
                        }
                    }
                else emptyList()
            val contains = index.filter { entry ->
                tokens.isNotEmpty() && tokens.all { entry.normalized.contains(it) }
            }
            val direct = (exact + contains).distinctBy { it.file }
            val candidates =
                if (direct.isNotEmpty()) direct
                else {
                    // Bounded fuzzy pass: bigram shortlist first, edit-distance only for its best
                    // 80 rows.
                    val grams = q.windowed(2).toSet()
                    index
                        .map { row ->
                            row to
                                row.titles.maxOf { title ->
                                    val t = SongSearch.normalized(title)
                                    val overlap = t.windowed(2).toSet().intersect(grams).size
                                    overlap.toDouble() / maxOf(1, grams.size, t.length - 1)
                                }
                        }
                        .filter { it.second >= .2 }
                        .sortedByDescending { it.second }
                        .take(80)
                        .map { it.first }
                        .filter { row -> row.titles.any { SongSearch.similarity(query, it) >= .5 } }
                }
            val ranked =
                SongSearch.rank(candidates.map { it.track(contextual) }, query, contextual)
                    .take(300)
            SearchPage(
                ranked.drop(page * size).take(size),
                (page + 1) * size < ranked.size,
                ranked.size,
                "amll",
                if (offline) "AMLL 索引更新失败，使用本地索引；歌词将按需读取。"
                else "AMLL 社区 TTML：支持逐字、译文及罗马音；评论仍来自 QQ／网易云。",
            )
        }

    suspend fun resolve(raw: String): Track {
        val key = lookupKey(raw)
        val rows =
            index().filter {
                it.file == key ||
                    listOf("ncmMusicId", "qqMusicId", "spotifyId", "appleMusicId").any { name ->
                        key in it.metadata[name].orEmpty()
                    }
            }
        require(rows.isNotEmpty()) { "AMLL 索引中未找到该 ID 或文件" }
        val kinds =
            rows
                .flatMap { row ->
                    listOf("ncmMusicId", "qqMusicId", "spotifyId", "appleMusicId").filter {
                        key in row.metadata[it].orEmpty()
                    }
                }
                .distinct()
        require(rows.size == 1 || kinds.size == 1) { "该 ID 跨平台对应多首歌曲，请填写 AMLL 原始 TTML 文件名" }
        return rows.last().track()
    }

    private fun lookupKey(raw: String): String {
        val value = raw.trim()
        if (!value.startsWith("https://")) {
            require(value.matches(Regex("[A-Za-z0-9_-]{1,160}(?:\\.ttml)?"))) {
                "请输入平台歌曲 ID 或 AMLL 原始文件名"
            }
            return value
        }
        val uri = URI(value)
        require(uri.userInfo == null && uri.port in listOf(-1, 443)) { "歌曲链接无效" }
        val path = uri.path.orEmpty()
        return when (uri.host) {
            "raw.githubusercontent.com" -> {
                require(path.startsWith("/amll-dev/amll-ttml-db/main/raw-lyrics/"))
                path.substringAfterLast('/')
            }
            "github.com" -> {
                require(path.startsWith("/amll-dev/amll-ttml-db/blob/main/raw-lyrics/"))
                path.substringAfterLast('/')
            }
            "music.163.com",
            "m.music.163.com" ->
                Regex("(?:id=|song/)([0-9]+)").find(value)?.groupValues?.get(1)
                    ?: error("链接中缺少网易云 ID")
            "y.qq.com" -> Regex("[A-Za-z0-9]{14}").find(path)?.value ?: error("链接中缺少 QQ songmid")
            "open.spotify.com" ->
                Regex("/track/([A-Za-z0-9]{22})").find(path)?.groupValues?.get(1)
                    ?: error("链接中缺少 Spotify ID")
            else -> error("仅接受 AMLL 仓库及平台歌曲链接")
        }
    }

    suspend fun lyrics(track: Track, refresh: Boolean = false): Lyrics =
        withContext(Dispatchers.IO) {
            require(filePattern.matches(track.sourceId)) { "AMLL 文件名无效，请重新匹配" }
            val path = "raw-lyrics/${track.sourceId}"
            val cached = read(path, DAY)
            val raw =
                if (!refresh && cached != null) cached
                else {
                    try {
                        val data = transport.get(path, 2_000_000)
                        AmllTtml.parse(data) // Do not poison the cache with HTML or malformed XML.
                        write(path, data)
                        data
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        read(path, 14 * DAY) ?: throw e
                    }
                }
            Lyrics(AmllTtml.parse(raw), Provider.AMLL, providerRevision = 1)
        }
}
