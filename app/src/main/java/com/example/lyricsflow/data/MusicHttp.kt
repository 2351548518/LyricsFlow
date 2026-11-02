package com.example.lyricsflow.data

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*

internal data class MusicResponse(val status: Int, val retryAfter: String?, val bytes: ByteArray)

/** Bounded network workers; cancellation releases the caller and disconnects the old request. */
internal class MusicHttp(
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    private companion object {
        val workers = Executors.newFixedThreadPool(3) {
            Thread(it, "Music-request").apply { isDaemon = true }
        } as ThreadPoolExecutor
        val disconnects = Executors.newFixedThreadPool(2) {
            Thread(it, "Music-cancel").apply { isDaemon = true }
        }
    }

    suspend fun send(url: URL, body: String?, bearer: String?, readTimeoutMs: Int): MusicResponse {
        val timeout = readTimeoutMs.coerceIn(1000, 60000)
        try {
            return withTimeout(timeout + 10_000L) {
                suspendCancellableCoroutine { continuation ->
                    val connection = AtomicReference<HttpURLConnection>()
                    val future = workers.submit {
                        var c: HttpURLConnection? = null
                        try {
                            fun checkActive() {
                                if (!continuation.isActive) throw CancellationException("Request cancelled")
                            }
                            checkActive()
                            c = open(url)
                            connection.set(c)
                            checkActive()
                            c.connectTimeout = 5000
                            c.readTimeout = timeout
                            c.instanceFollowRedirects = false
                            c.setRequestProperty("User-Agent", "LyricsFlow/1.0 Android")
                            c.setRequestProperty("Accept", "application/json")
                            if (url.host == "qq.com" || url.host.endsWith(".qq.com"))
                                c.setRequestProperty("Referer", "https://y.qq.com/")
                            if (url.host == "music.163.com")
                                c.setRequestProperty("Referer", "https://music.163.com/")
                            if (bearer != null) c.setRequestProperty("Authorization", "Bearer $bearer")
                            if (body != null) {
                                c.requestMethod = "POST"
                                c.doOutput = true
                                c.setRequestProperty("Content-Type", "application/json")
                                c.outputStream.use { output ->
                                    checkActive()
                                    output.write(body.toByteArray(Charsets.UTF_8))
                                }
                            }
                            checkActive()
                            val status = c.responseCode
                            val retry = c.getHeaderField("Retry-After")
                            val bytes = if (status in 200..299) {
                                require(c.contentLengthLong <= 4_000_000) { "响应过大" }
                                c.inputStream.use { input ->
                                    val output = ByteArrayOutputStream()
                                    val buffer = ByteArray(16_384)
                                    while (true) {
                                        checkActive()
                                        val size = input.read(buffer)
                                        if (size < 0) break
                                        require(output.size() + size <= 4_000_000) { "响应过大" }
                                        output.write(buffer, 0, size)
                                    }
                                    output.toByteArray()
                                }
                            } else byteArrayOf()
                            checkActive()
                            continuation.resume(MusicResponse(status, retry, bytes))
                        } catch (e: Exception) {
                            continuation.resumeWithException(e)
                        } finally {
                            c?.disconnect()
                        }
                    }
                    continuation.invokeOnCancellation {
                        future.cancel(true)
                        workers.remove(future as Runnable)
                        connection.get()?.let { c -> disconnects.execute { c.disconnect() } }
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw ApiFailure("${url.host} 连接超时，请稍后重试")
        }
    }
}
