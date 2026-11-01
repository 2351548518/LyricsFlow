package com.example.lyricsflow.data

import android.content.Context
import android.content.Intent
import android.content.ClipData
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import com.example.lyricsflow.BuildConfig
import com.example.lyricsflow.core.ReleaseVersion
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

internal const val UPDATE_REPOSITORY = "2351548518/LyricsFlow"
internal const val MAX_UPDATE_BYTES = 128_000_000L

internal data class UpdateRelease(
    val version: String,
    val url: String,
    val bytes: Long,
    val sha256: String,
    val assetId: Long = 0,
) {
    companion object {
        fun parse(json: JSONObject): UpdateRelease {
            check(!json.getBoolean("draft") && !json.getBoolean("prerelease")) { "尚无可用的正式版本" }
            val tag = json.getString("tag_name")
            check(ReleaseVersion.parse(tag) != null) { "无法识别正式版本号" }
            val assets = json.getJSONArray("assets")
            val expectedName = "LyricsFlow-v${tag.removePrefix("v")}.apk"
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .singleOrNull { it.optString("name") == expectedName && it.optString("state") == "uploaded" }
                ?: error("新版本尚未提供完整的 Android 安装包，请稍后重试")
            val url = apk.getString("browser_download_url")
            check(url == "https://github.com/$UPDATE_REPOSITORY/releases/download/$tag/$expectedName") {
                "安装包地址不属于官方发布仓库"
            }
            val size = apk.getLong("size")
            check(size in 1..MAX_UPDATE_BYTES) { "安装包大小异常" }
            val digest = apk.optString("digest").removePrefix("sha256:").lowercase()
            check(Regex("[0-9a-f]{64}").matches(digest)) { "新版本缺少 SHA-256 校验信息，请稍后重试" }
            return UpdateRelease(tag.removePrefix("v"), url, size, digest, apk.optLong("id").coerceAtLeast(0))
        }
    }
}

/** Public metadata and assets only: never pass the user's OAuth token to this client. */
internal class ReleaseDownloads(
    private val http: GitHubTransport = GitHubHttp(),
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    suspend fun latest(): UpdateRelease = try {
        UpdateRelease.parse(http.send("GET", "/repos/$UPDATE_REPOSITORY/releases/latest", null, "").json)
    } catch (e: GitHubFailure) {
        if (e.status !in listOf(403, 429, 500, 502, 503, 504)) throw e
        websiteLatest()
    } catch (_: IOException) {
        websiteLatest()
    }

    // Shared mobile/VPN exit IPs often exhaust GitHub's anonymous API quota. The
    // official latest redirect and release checksum file do not use that quota.
    private suspend fun websiteLatest(): UpdateRelease = withTimeout(45_000) {
        withContext(Dispatchers.IO) {
            val latest = open(URL("https://github.com/$UPDATE_REPOSITORY/releases/latest"))
            val tag = try {
                configure(latest, "HEAD")
                check(latest.responseCode in listOf(301, 302, 307, 308)) { "暂时无法获取最新正式版，请稍后重试" }
                val location = URL(latest.url, latest.getHeaderField("Location") ?: error("版本地址无效"))
                val prefix = "https://github.com/$UPDATE_REPOSITORY/releases/tag/"
                val raw = location.toString()
                check(raw.startsWith(prefix)) { "版本地址不属于官方发布仓库" }
                raw.removePrefix(prefix).also { check(ReleaseVersion.parse(it) != null) { "无法识别正式版本号" } }
            } finally { latest.disconnect() }
            val name = "LyricsFlow-v${tag.removePrefix("v")}.apk"
            val root = "https://github.com/$UPDATE_REPOSITORY/releases/download/$tag/"
            val checksum = String(assetInfo(root + "SHA256SUMS.txt", false).second, Charsets.UTF_8)
                .lineSequence().map { it.trim().split(Regex("\\s+"), limit = 2) }
                .singleOrNull { it.size == 2 && it[1].removePrefix("*") == name }
                ?.first()?.lowercase()
            check(checksum != null && Regex("[0-9a-f]{64}").matches(checksum)) { "正式版缺少有效的校验文件，请稍后重试" }
            val size = assetInfo(root + name, true).first
            check(size in 1..MAX_UPDATE_BYTES) { "无法读取安装包大小，请稍后重试" }
            UpdateRelease(tag.removePrefix("v"), root + name, size, checksum)
        }
    }

    private fun configure(connection: HttpURLConnection, method: String) {
        connection.requestMethod = method
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("User-Agent", "LyricsFlow-Android/${BuildConfig.VERSION_NAME}")
        connection.setRequestProperty("Accept-Encoding", "identity")
    }

    private suspend fun assetInfo(address: String, head: Boolean): Pair<Long, ByteArray> {
        var url = URL(address)
        for (hop in 0..5) {
            currentCoroutineContext().ensureActive()
            checkAssetUrl(url)
            val connection = open(url)
            try {
                configure(connection, if (head) "HEAD" else "GET")
                val status = connection.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    check(hop < 5) { "下载重定向次数过多" }
                    url = URL(url, connection.getHeaderField("Location") ?: error("下载地址无效"))
                    continue
                }
                check(status == 200) { "无法获取正式版文件（HTTP $status），请稍后重试" }
                return connection.contentLengthLong to if (head) byteArrayOf() else connection.inputStream.use { it.readBytesLimited(32_000) }
            } finally { connection.disconnect() }
        }
        error("未取得正式版文件")
    }

    suspend fun download(release: UpdateRelease, destination: File, progress: (Long) -> Unit) =
        withTimeout(300_000) {
            withContext(Dispatchers.IO) {
                val job = currentCoroutineContext()
                // The asset API can redirect straight to the CDN, avoiding a separate
                // connection to github.com on networks where only the API is reachable.
                var url = URL(if (release.assetId > 0) "https://api.github.com/repos/$UPDATE_REPOSITORY/releases/assets/${release.assetId}" else release.url)
                var fallback = release.url.takeIf { release.assetId > 0 }
                var completed = false
                try {
                    for (hop in 0..5) {
                        job.ensureActive()
                        checkAssetUrl(url)
                        val connection = open(url)
                        try {
                            connection.instanceFollowRedirects = false
                            connection.connectTimeout = 10_000
                            connection.readTimeout = 15_000
                            connection.setRequestProperty("User-Agent", "LyricsFlow-Android/${BuildConfig.VERSION_NAME}")
                            connection.setRequestProperty("Accept-Encoding", "identity")
                            connection.setRequestProperty("Accept", "application/octet-stream")
                            val status = connection.responseCode
                            if (status !in 200..399 && fallback != null) {
                                url = URL(fallback)
                                fallback = null
                                continue
                            }
                            if (status in listOf(301, 302, 303, 307, 308)) {
                                check(hop < 5) { "下载重定向次数过多" }
                                val location = connection.getHeaderField("Location") ?: error("下载地址无效")
                                url = URL(url, location)
                                continue
                            }
                            check(status == 200) { "安装包下载失败（HTTP $status），请稍后重试" }
                            val length = connection.contentLengthLong
                            check(length < 0 || length == release.bytes) { "安装包大小与发布信息不一致" }
                            val hash = MessageDigest.getInstance("SHA-256")
                            var count = 0L
                            connection.inputStream.use { input ->
                                destination.outputStream().use { output ->
                                    val buffer = ByteArray(32 * 1024)
                                    var notified = -1L
                                    while (true) {
                                        job.ensureActive()
                                        val size = input.read(buffer)
                                        if (size < 0) break
                                        count += size
                                        check(count <= release.bytes) { "下载数据超出安装包大小" }
                                        output.write(buffer, 0, size)
                                        hash.update(buffer, 0, size)
                                        // At most one state update per percent, independent of network chunk size.
                                        val percent = count * 100 / release.bytes
                                        if (percent != notified) {
                                            notified = percent
                                            progress(count)
                                        }
                                    }
                                    output.fd.sync()
                                }
                            }
                            check(count == release.bytes && hash.digest().hex() == release.sha256) {
                                "安装包校验失败，请重新下载"
                            }
                            job.ensureActive()
                            completed = true
                            return@withContext
                        } catch (e: IOException) {
                            if (fallback == null) throw e
                            url = URL(fallback)
                            fallback = null
                        } finally {
                            connection.disconnect()
                        }
                    }
                    error("未取得安装包")
                } finally {
                    if (!completed) destination.delete()
                }
            }
        }
}

internal fun checkAssetUrl(url: URL) {
    check(url.protocol == "https" && url.userInfo == null && url.port in listOf(-1, 443) &&
        url.host in setOf("github.com", "api.github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")) {
        "下载地址不属于 GitHub 官方文件服务"
    }
}

private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }

internal enum class UpdateStage { IDLE, CHECKING, DOWNLOADING, READY, LATEST, ERROR }

internal data class UpdateState(
    val stage: UpdateStage = UpdateStage.IDLE,
    val version: String = "",
    val downloaded: Long = 0,
    val total: Long = 0,
    val message: String = "",
) {
    val busy get() = stage == UpdateStage.CHECKING || stage == UpdateStage.DOWNLOADING
}

internal class AppUpdates(
    private val context: Context,
    private val scope: CoroutineScope,
    private val downloads: ReleaseDownloads = ReleaseDownloads(),
) {
    private val mutable = MutableStateFlow(UpdateState())
    val state = mutable.asStateFlow()
    private var operation: Job? = null
    @Volatile private var generation = 0
    private var ready: Pair<File, UpdateRelease>? = null

    fun check() {
        if (operation?.isActive == true) return
        val request = ++generation
        mutable.value = UpdateState(UpdateStage.CHECKING)
        operation = scope.launch {
            var file: File? = null
            try {
                val release = downloads.latest()
                val installed = ReleaseVersion.parse(BuildConfig.VERSION_NAME) ?: error("无法识别本机版本号")
                if (ReleaseVersion.parse(release.version)!! <= installed) {
                    mutable.value = UpdateState(UpdateStage.LATEST, message = "当前已是最新版本")
                    return@launch
                }
                ready = null
                withContext(Dispatchers.IO) {
                    val directory = File(context.cacheDir, "updates").apply { mkdirs() }
                    directory.listFiles()?.forEach { it.delete() }
                    file = File.createTempFile("release-", ".apk", directory)
                }
                mutable.value = UpdateState(UpdateStage.DOWNLOADING, release.version, total = release.bytes)
                downloads.download(release, file!!) { bytes ->
                    if (generation == request)
                        mutable.value = UpdateState(UpdateStage.DOWNLOADING, release.version, bytes, release.bytes)
                }
                withContext(Dispatchers.IO) { verifyUpdateApk(context, file!!, release.version) }
                ready = file!! to release
                mutable.value = UpdateState(UpdateStage.READY, release.version, release.bytes, release.bytes)
            } catch (_: TimeoutCancellationException) {
                file?.delete()
                if (generation != request) return@launch
                mutable.value = UpdateState(UpdateStage.ERROR, message = "请求超时，请检查网络后重试")
            } catch (e: CancellationException) {
                file?.delete()
                throw e
            } catch (e: Exception) {
                file?.delete()
                if (generation != request) return@launch
                mutable.value = UpdateState(UpdateStage.ERROR, message = when (e) {
                    is GitHubFailure -> when (e.status) {
                        404 -> "尚无可下载的正式版本，请稍后重试"
                        403, 429 -> "GitHub 请求受到限制，请稍后重试"
                        else -> "检查更新失败（HTTP ${e.status}），请稍后重试"
                    }
                    is IOException -> "网络连接失败，请检查网络后重试"
                    else -> e.message ?: "更新失败，请稍后重试"
                })
            }
        }
    }

    fun cancel() {
        generation++
        operation?.cancel()
        if (mutable.value.busy) mutable.value = UpdateState()
    }

    /** Revalidate immediately before granting temporary read access to the installer. */
    suspend fun installerIntent(): Intent {
        val (file, release) = ready ?: error("安装包已失效，请重新检查更新")
        withContext(Dispatchers.IO) { verifyUpdateApk(context, file, release.version) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            clipData = ClipData.newRawUri("LyricsFlow update", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}

@Suppress("DEPRECATION")
internal fun verifyUpdateApk(context: Context, file: File, version: String) {
    val manager = context.packageManager
    val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    val archive = manager.getPackageArchiveInfo(file.absolutePath, flags) ?: error("无法读取安装包，请重新下载")
    val installed = manager.getPackageInfo(context.packageName, flags)
    check(archive.packageName == context.packageName) { "安装包的应用标识不一致" }
    check(archive.versionName == version && versionCode(archive) > versionCode(installed)) { "安装包版本不符合升级要求" }
    fun signatures(info: PackageInfo): Set<String> =
        (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)
            .orEmpty().map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hex() }.toSet()
    val current = signatures(installed)
    check(current.isNotEmpty() && current == signatures(archive)) {
        "安装包签名与当前版本不同，无法覆盖升级。请使用相同签名的正式版"
    }
    check((archive.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE) <= Build.VERSION.SDK_INT) {
        "新版本不支持当前 Android 版本"
    }
}

@Suppress("DEPRECATION")
private fun versionCode(info: PackageInfo): Long =
    if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
