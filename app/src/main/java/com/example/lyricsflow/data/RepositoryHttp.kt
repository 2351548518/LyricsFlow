package com.example.lyricsflow.data

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*

internal fun interface RepositoryTransport {
    suspend fun get(path: String, limit: Int): String
}

/** Public AMLL repository only; independent of GitHub login and its repo scope/token. */
internal class RepositoryHttp : RepositoryTransport {
    private var mirrorUntil = 0L

    private companion object {
        val workers =
            Executors.newFixedThreadPool(2) {
                Thread(it, "AMLL-download").apply { isDaemon = true }
            }
        val disconnects = Executors.newSingleThreadExecutor {
            Thread(it, "AMLL-cancel").apply { isDaemon = true }
        }
    }

    override suspend fun get(path: String, limit: Int): String {
        require(
            path == "metadata/raw-lyrics-index.jsonl" ||
                Regex("raw-lyrics/[A-Za-z0-9_-]{1,160}\\.ttml").matches(path)
        )
        val official = "https://raw.githubusercontent.com/amll-dev/amll-ttml-db/main/"
        val mirror = "https://amlldb.bikonoo.com/"
        val mirrorsFirst = System.currentTimeMillis() < mirrorUntil
        var last: Exception? = null
        for (base in if (mirrorsFirst) listOf(mirror, official) else listOf(official, mirror)) {
            try {
                return load(base, path, limit)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (base == official) mirrorUntil = System.currentTimeMillis() + 60 * 60 * 1000
                last = e
            }
        }
        throw last ?: ApiFailure("AMLL 仓库和镜像暂不可用")
    }

    private suspend fun load(base: String, path: String, limit: Int): String {
        try {
            return withTimeout(5500) {
                suspendCancellableCoroutine { continuation ->
                    val connection = AtomicReference<HttpURLConnection>()
                    val job = workers.submit {
                        var c: HttpURLConnection? = null
                        try {
                            c = URL(base + path).openConnection() as HttpURLConnection
                            connection.set(c)
                            if (!continuation.isActive) throw java.io.InterruptedIOException()
                            c.connectTimeout = 2500
                            c.readTimeout = 5000
                            c.instanceFollowRedirects = false
                            c.setRequestProperty("User-Agent", "LyricsFlow/1.0 Android")
                            val code = c.responseCode
                            if (code !in 200..299) throw ApiFailure("AMLL 仓库暂不可用（HTTP $code）")
                            val raw =
                                c.inputStream.use {
                                    String(it.readBytesLimited(limit), Charsets.UTF_8)
                                }
                            continuation.resume(raw)
                        } catch (e: Exception) {
                            continuation.resumeWithException(e)
                        } finally {
                            c?.disconnect()
                        }
                    }
                    continuation.invokeOnCancellation {
                        job.cancel(true)
                        connection.get()?.let { c -> disconnects.execute { c.disconnect() } }
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw ApiFailure("AMLL 仓库连接超时，请稍后重试或使用其他来源")
        } catch (e: java.io.IOException) {
            throw ApiFailure("AMLL 仓库连接失败（${e.javaClass.simpleName}），请检查网络或使用其他来源").apply {
                initCause(e)
            }
        }
    }
}
