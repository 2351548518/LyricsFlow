package com.example.lyricsflow.data

import com.example.lyricsflow.core.*
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

internal class LrcFailure(val status: Int, val retryMs: Long = 0) :
    Exception(if (status == 0) "LRCLIB 连接超时或失败" else "LRCLIB 服务暂不可用（HTTP $status）")

internal fun interface LrcTransport {
    suspend fun get(path: String): JSONObject
}

/** One cancellable, bounded GET. Never forwards credentials, follows redirects or uses mirrors. */
internal class LrcHttp(
    private val timeoutMs: Long = 8500,
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) : LrcTransport {
    private companion object {
        val workers =
            Executors.newFixedThreadPool(2) {
                Thread(it, "LRCLIB-request").apply { isDaemon = true }
            }
        val disconnects =
            Executors.newFixedThreadPool(2) {
                Thread(it, "LRCLIB-disconnect").apply { isDaemon = true }
            }
    }

    override suspend fun get(path: String): JSONObject {
        require(path.startsWith("/api/") && !path.contains("\r") && !path.contains("\n"))
        try {
            return withTimeout(timeoutMs) {
                suspendCancellableCoroutine { continuation ->
                    val connection = AtomicReference<HttpURLConnection>()
                    val future = workers.submit {
                        var c: HttpURLConnection? = null
                        try {
                            c = open(URL("https://lrclib.net$path"))
                            connection.set(c)
                            if (!continuation.isActive) throw java.io.InterruptedIOException()
                            c.connectTimeout = 4000
                            c.readTimeout = 6000
                            c.instanceFollowRedirects = false
                            c.setRequestProperty("User-Agent", "LyricsFlow/1.0 Android")
                            c.setRequestProperty("Accept", "application/json")
                            val code = c.responseCode
                            if (code !in 200..299)
                                throw LrcFailure(code, retryAfter(c.getHeaderField("Retry-After")))
                            val raw =
                                c.inputStream
                                    .use { String(it.readBytesLimited(4_000_000), Charsets.UTF_8) }
                                    .trim()
                            val json =
                                if (raw.startsWith("[")) JSONObject().put("items", JSONArray(raw))
                                else JSONObject(raw)
                            continuation.resume(json)
                        } catch (e: Exception) {
                            continuation.resumeWithException(e)
                        } finally {
                            c?.disconnect()
                        }
                    }
                    continuation.invokeOnCancellation {
                        future.cancel(true)
                        connection.get()?.let { c -> disconnects.execute { c.disconnect() } }
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw LrcFailure(0)
        } catch (e: java.io.IOException) {
            throw LrcFailure(0)
        }
    }
}

internal fun retryAfter(raw: String?, now: Long = System.currentTimeMillis()): Long {
    raw?.trim()?.toLongOrNull()?.let {
        return it.coerceIn(0, 604800) * 1000
    }
    return raw?.let {
            runCatching {
                SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)
                    .parse(it)
                    ?.time
                    ?.minus(now)
            }
                .getOrNull()
        }
        ?.coerceIn(0, 604800000) ?: 0
}

internal data class LrcResult(val rows: List<JSONObject>, val stale: Boolean = false)

/** Shared serialized requests, cache-first, one transient retry and host-wide overload backoff. */
internal class LrcLibClient(
    private val read: (String, Long) -> String?,
    private val write: (String, String) -> Unit,
    private val transport: LrcTransport = LrcHttp(),
    private val now: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private val lock = Mutex()
    private var retryAt = 0L
    private val known = java.util.concurrent.ConcurrentHashMap<String, JSONObject>()

    companion object {
        const val REVISION = 2
        const val FRESH = 6 * 60 * 60 * 1000L
        const val STALE = 7 * 24 * 60 * 60 * 1000L
    }

    private suspend fun fetch(path: String, refresh: Boolean = false): Pair<JSONObject, Boolean> =
        withContext(Dispatchers.IO) {
            lock.withLock {
                if (!refresh)
                    read(path, FRESH)?.let {
                        return@withLock JSONObject(it) to false
                    }
                val stale = read(path, STALE)
                if (now() < retryAt) {
                    if (stale != null) return@withLock JSONObject(stale) to true
                    throw ApiFailure(
                        "LRCLIB 正在限流或繁忙，请在 ${((retryAt-now())/1000)+1} 秒后重试；可切换 QQ／网易云"
                    )
                }
                var last: LrcFailure? = null
                repeat(2) { attempt ->
                    try {
                        val result = transport.get(path)
                        write(path, result.toString())
                        retryAt = 0
                        return@withLock result to false
                    } catch (e: LrcFailure) {
                        last = e
                        val transient = e.status in listOf(0, 429, 502, 503, 504)
                        if (!transient) throw e
                        if (attempt == 0 && e.status != 429 && e.retryMs <= 2000)
                            pause(maxOf(700, e.retryMs))
                        else {
                            retryAt =
                                now() + maxOf(if (e.status == 429) 60000 else 20000, e.retryMs)
                            if (stale != null) return@withLock JSONObject(stale) to true
                            throw ApiFailure("${e.message}，已暂停重复请求；稍后重试或切换 QQ／网易云")
                        }
                    }
                }
                throw last ?: ApiFailure("LRCLIB 暂不可用")
            }
        }

    suspend fun search(query: String, current: Track? = null): LrcResult =
        withContext(Dispatchers.IO) {
            val path =
                if (current == null) "/api/search?q=${enc(query)}"
                else
                    "/api/search?track_name=${enc(SongSearch.withoutAliases(current.title))}&artist_name=${enc(current.artist.split(Regex("[,，/、;&]")).first().trim())}"
            val (json, stale) = fetch(path)
            val a = json.optJSONArray("items") ?: JSONArray()
            val rows = (0 until a.length().coerceAtMost(200)).map { a.getJSONObject(it) }
            rows.forEach { row ->
                val id = row.optString("id")
                if (id.matches(Regex("\\d{1,20}"))) {
                    known[id] = row
                    // Search responses already contain lyrics: avoid a second /get/<id> request.
                    if (!stale) write("/api/get/$id", row.toString())
                }
            }
            if (known.size > 600) known.keys.take(known.size - 400).forEach { known.remove(it) }
            LrcResult(rows, stale)
        }

    suspend fun record(id: String, refresh: Boolean = false): JSONObject {
        require(id.matches(Regex("\\d{1,20}"))) { "请输入 LRCLIB 数字记录 ID" }
        return fetch("/api/get/$id", refresh).first.also { known[id] = it }
    }

    fun track(r: JSONObject) =
        Track(
            r.optString("trackName"),
            r.optString("artistName"),
            r.optString("albumName"),
            (r.optDouble("duration", 0.0) * 1000).toLong(),
            Provider.LRCLIB,
            r.optString("id"),
        )

    fun hint(t: Track): String =
        known[t.sourceId]
            ?.let {
                when {
                    it.optBoolean("instrumental") -> "纯音乐"
                    hasKana(text(it)) || hasKana(text(it, "plainLyrics")) -> "含日文原文"
                    latinOnly(text(it)) -> "拉丁字母歌词（可能为罗马音）"
                    else -> ""
                }
            }
            .orEmpty()

    fun preferOriginal(current: Track, rows: List<JSONObject>): List<Track> {
        val tracks = rows.map(::track)
        // Language preference never bypasses title, credit, version or duration checks.
        val eligible = rows.filter { SongSearch.automatic(current, listOf(track(it))) != null }
        if (englishVersion(current.title)) return tracks
        val japanese = eligible.filter {
            hasKana(text(it)) && text(it, "syncedLyrics").isNotBlank()
        }
        if (japanese.isEmpty()) return tracks
        val native = japanese.map(::track)
        val album = native.filter {
            current.album.isNotBlank() &&
                SongSearch.normalized(it.album) == SongSearch.normalized(current.album)
        }
        val pool = album.ifEmpty { native }
        val timed = pool.filter { it.duration > 0 }
        if (current.duration <= 0 || timed.isEmpty()) return pool
        // Prefer the nearest native upload, preserving the five-second eligibility check above.
        // Community copies often disagree by several seconds; they must not force a romaji pick.
        val closest = timed.minOf { kotlin.math.abs(it.duration - current.duration) }
        return timed.filter { kotlin.math.abs(it.duration - current.duration) <= closest + 500 }
    }

    fun lyrics(r: JSONObject, duration: Long): Lyrics {
        val synced = text(r, "syncedLyrics")
        val plain = text(r, "plainLyrics")
        // Some community uploads put romaji in syncedLyrics and native lyrics in plainLyrics.
        // Do not fabricate line timestamps by zipping two unrelated texts.
        val original = if (latinOnly(synced) && hasKana(plain)) plain else synced.ifBlank { plain }
        return Lyrics(
            LyricParser.lrc(original.removePrefix("\uFEFF"), duration),
            Provider.LRCLIB,
            r.optBoolean("instrumental"),
            REVISION,
        )
    }

    private fun text(r: JSONObject, field: String) =
        r.optString(field).takeUnless { it == "null" }.orEmpty()

    private fun text(r: JSONObject) = text(r, "syncedLyrics").ifBlank { text(r, "plainLyrics") }

    private fun hasKana(raw: String) = raw.count { it in '\u3040'..'\u30ff' } >= 4

    private fun latinOnly(raw: String): Boolean {
        val letters = raw.filter { it.isLetter() }
        return letters.length >= 12 &&
            letters.count { it in 'a'..'z' || it in 'A'..'Z' }.toDouble() / letters.length > .9
    }

    private fun englishVersion(title: String) =
        Regex("english|英語|英语|英文", RegexOption.IGNORE_CASE).containsMatchIn(title)
}
