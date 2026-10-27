package com.example.lyricsflow.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.scale
import com.example.lyricsflow.core.digest
import com.example.lyricsflow.data.GitHubApi
import com.example.lyricsflow.data.readBytesLimited
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Only public profile/image requests, with no Authorization header or remote redirects. */
internal object GitHubAvatars {
    private fun file(context: Context, login: String) =
        File(context.cacheDir, "github-avatar-${digest(login.lowercase())}.png")

    suspend fun cached(context: Context, login: String): Bitmap? =
        withContext(Dispatchers.IO) {
            val f = file(context, login)
            if (f.length() in 1..1_000_000) BitmapFactory.decodeFile(f.path) else null
        }

    suspend fun stale(context: Context, login: String) =
        withContext(Dispatchers.IO) {
            val f = file(context, login)
            !f.isFile || System.currentTimeMillis() - f.lastModified() > 24 * 60 * 60 * 1000L
        }

    suspend fun fetch(context: Context, login: String): Bitmap =
        withContext(Dispatchers.IO) {
            val profile = GitHubApi().publicUser(login)
            val url = URL(profile.getString("avatar_url"))
            require(
                url.protocol == "https" &&
                    url.host == "avatars.githubusercontent.com" &&
                    url.userInfo == null &&
                    url.port in listOf(-1, 443)
            )
            val c =
                URL("https://avatars.githubusercontent.com${url.path}?s=192&v=4").openConnection()
                    as HttpURLConnection
            val bitmap =
                try {
                    c.connectTimeout = 8000
                    c.readTimeout = 12000
                    c.instanceFollowRedirects = false
                    c.setRequestProperty("User-Agent", "LyricsFlow-Android/1.0")
                    require(c.responseCode == 200)
                    val bytes = c.inputStream.use { it.readBytesLimited(1_000_000) }
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    require(bounds.outWidth in 1..1024 && bounds.outHeight in 1..1024)
                    val decoded =
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("头像解码失败")
                    decoded.scale(192, 192, true).also {
                        if (it !== decoded) decoded.recycle()
                    }
                } finally {
                    c.disconnect()
                }
            val atomic = android.util.AtomicFile(file(context, login))
            val out = atomic.startWrite()
            try {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
                atomic.finishWrite(out)
            } catch (e: Exception) {
                atomic.failWrite(out)
                throw e
            }
            bitmap
        }
}

@Composable
internal fun GitHubAvatar(login: String) {
    val context = LocalContext.current
    val bitmap by
        produceState<Bitmap?>(null, login) {
            value = null
            if (login.isNotBlank()) {
                value = GitHubAvatars.cached(context, login)
                if (value == null || GitHubAvatars.stale(context, login)) {
                    try {
                        value = GitHubAvatars.fetch(context, login)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        /* Offline: retain cached avatar, otherwise the person icon. */
                    }
                }
            }
        }
    val image = bitmap.takeIf { login.isNotBlank() }
    if (image == null) Glyph("user", Modifier.size(26.dp))
    else
        Image(
            image.asImageBitmap(),
            "GitHub 头像",
            Modifier.fillMaxSize().clip(CircleShape),
            contentScale = ContentScale.Crop,
        )
}
