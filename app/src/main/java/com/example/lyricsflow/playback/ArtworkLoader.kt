package com.example.lyricsflow.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Read only explicit artwork URIs, without player credentials or storage permissions. */
internal class ArtworkLoader(private val context: Context, private val cacheNamespace: String? = null,
    private val readOverride: ((String) -> ByteArray)? = null) {
    companion object {
        fun spotifyImageUrl(raw: String): String? {
            val uri = Uri.parse(raw)
            if (uri.scheme != "content" || uri.authority != "com.spotify.mobile.android.mediaapi" ||
                uri.fragment != null || uri.pathSegments.size != 1) return null
            val image = uri.pathSegments.single()
            if (!Regex("spotify:image:[0-9a-fA-F]{40}").matches(image)) return null
            return "https://i.scdn.co/image/${image.substringAfterLast(':')}"
        }
    }
    suspend fun load(uris: List<String>): Bitmap? = withContext(Dispatchers.IO) {
        val jobContext = currentCoroutineContext()
        val tried = mutableSetOf<String>()
        for (uri in uris.take(3)) {
            for (candidate in listOfNotNull(uri, spotifyImageUrl(uri))) {
                jobContext.ensureActive()
                if (!tried.add(candidate)) continue
                val cache = java.io.File(context.cacheDir, "covers/${com.example.lyricsflow.core.digest("$cacheNamespace|$candidate")}")
                val useCache = readOverride == null && cacheNamespace != null
                if (useCache) {
                    val cached = runCatching {
                        if (cache.length() in 1..8_000_000) decode(cache.readBytes()) else null
                    }.getOrNull()
                    if (cached != null && ArtworkQuality.usable(cached)) {
                        cache.setLastModified(System.currentTimeMillis())
                        return@withContext cached
                    }
                    cache.delete()
                }
                val bytes = try {
                    readOverride?.invoke(candidate) ?: read(candidate) { jobContext.ensureActive() }
                } catch (e: CancellationException) { throw e }
                  catch (_: Exception) { continue }
                jobContext.ensureActive()
                val image = runCatching { decode(bytes) }.getOrNull()
                if (image != null && ArtworkQuality.usable(image)) {
                    if (useCache) runCatching {
                        cache.parentFile!!.mkdirs()
                        val atomic = android.util.AtomicFile(cache)
                        val output = atomic.startWrite()
                        try { output.write(bytes); atomic.finishWrite(output) }
                        catch (e: Exception) { atomic.failWrite(output); throw e }
                        var total = 0L
                        cache.parentFile!!.listFiles().orEmpty().sortedByDescending { it.lastModified() }.forEachIndexed { index, file ->
                            total += file.length()
                            if (index >= 64 || total > 32L * 1024 * 1024) file.delete()
                        }
                    }
                    return@withContext image
                }
            }
        }
        null
    }

    private fun bounded(input: InputStream, checkActive: () -> Unit): ByteArray = input.use {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16_384)
        val deadline = android.os.SystemClock.elapsedRealtime() + 8000
        while (true) {
            checkActive()
            check(android.os.SystemClock.elapsedRealtime() <= deadline)
            val count = it.read(buffer)
            if (count < 0) break
            require(output.size() + count <= 8_000_000) { "封面文件过大" }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }

    private fun read(raw: String, checkActive: () -> Unit): ByteArray {
        checkActive()
        val uri = Uri.parse(raw)
        if (uri.scheme == "content")
            return bounded(checkNotNull(context.contentResolver.openInputStream(uri)), checkActive)
        require(uri.scheme == "https")
        var url = URL(raw)
        repeat(4) {
            checkActive()
            require(url.protocol == "https" && url.userInfo == null)
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 2500
                connection.readTimeout = 3500
                if (connection.responseCode in listOf(301, 302, 303, 307, 308)) {
                    url = URL(url, checkNotNull(connection.getHeaderField("Location")))
                } else {
                    require(connection.responseCode == 200)
                    require(connection.contentLengthLong <= 8_000_000)
                    return bounded(connection.inputStream, checkActive)
                }
            } finally { connection.disconnect() }
        }
        error("封面重定向过多")
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > 1536 || bounds.outHeight / sample > 1536) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 })
    }
}
