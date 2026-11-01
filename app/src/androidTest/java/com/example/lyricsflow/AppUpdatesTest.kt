package com.example.lyricsflow

import androidx.test.platform.app.InstrumentationRegistry
import com.example.lyricsflow.data.*
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppUpdatesTest {
    private fun metadata(version: String = "1.0.3", content: ByteArray = "valid download".toByteArray()): JSONObject =
        JSONObject().put("draft", false).put("prerelease", false).put("tag_name", "v$version")
            .put("assets", org.json.JSONArray().put(JSONObject()
                .put("name", "LyricsFlow-v$version.apk").put("state", "uploaded")
                .put("size", content.size)
                .put("digest", "sha256:" + MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it.toInt() and 255) })
                .put("browser_download_url", "https://github.com/$UPDATE_REPOSITORY/releases/download/v$version/LyricsFlow-v$version.apk")))

    private class Connection(url: URL, private val bytes: ByteArray, private val code: Int = 200, private val location: String? = null) : HttpURLConnection(url) {
        var disconnected = false
        override fun connect() {}
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getResponseCode() = code
        override fun getInputStream() = ByteArrayInputStream(bytes)
        override fun getContentLengthLong() = bytes.size.toLong()
        override fun getHeaderField(name: String?) = if (name == "Location") location else null
    }

    @Test fun publicEndpointNeedsNoLoginAndValidatesReleaseAssets() = runBlocking {
        val downloads = ReleaseDownloads(GitHubTransport { method, path, body, token ->
            assertEquals("GET", method)
            assertEquals("/repos/2351548518/LyricsFlow/releases/latest", path)
            assertNull(body)
            assertEquals("", token)
            GitHubReply(200, metadata())
        })
        assertEquals("1.0.3", downloads.latest().version)
        fun rejected(json: JSONObject) {
            assertTrue(runCatching { UpdateRelease.parse(json) }.isFailure)
        }
        rejected(metadata().put("prerelease", true))
        rejected(metadata().put("draft", true))
        rejected(metadata("1.0.3-beta"))
        rejected(metadata().apply { getJSONArray("assets").getJSONObject(0).put("size", MAX_UPDATE_BYTES + 1) })
        rejected(metadata().apply { getJSONArray("assets").getJSONObject(0).put("browser_download_url", "https://evil.example/app.apk") })
        rejected(metadata().apply { getJSONArray("assets").getJSONObject(0).remove("digest") })
    }

    @Test fun redirectedDownloadReportsProgressAndRejectsCorruption() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bytes = ByteArray(100_000) { (it % 251).toByte() }
        val release = UpdateRelease.parse(metadata(content = bytes))
        val file = File(context.cacheDir, "update-network-test.apk")
        val progress = mutableListOf<Long>()
        val connections = mutableListOf<Connection>()
        val downloader = ReleaseDownloads(open = { url ->
            Connection(url, if (url.host == "github.com") byteArrayOf() else bytes,
                if (url.host == "github.com") 302 else 200,
                "https://release-assets.githubusercontent.com/example").also { connections += it }
        })
        try {
            downloader.download(release, file) { progress += it }
            assertArrayEquals(bytes, file.readBytes())
            assertEquals(bytes.size.toLong(), progress.last())
            assertTrue(connections.all { it.disconnected })
            val damaged = ReleaseDownloads(open = { Connection(it, bytes.copyOf().apply { this[0] = 99 }) })
            assertTrue(runCatching { damaged.download(release, file) {} }.isFailure)
            assertFalse(file.exists())
            val outside = ReleaseDownloads(open = { Connection(it, byteArrayOf(), 302, "https://evil.example/update.apk") })
            assertTrue(runCatching { outside.download(release, file) {} }.isFailure)
            assertFalse(file.exists())
        } finally { file.delete() }
    }

    @Test fun cancellationDeletesPartialDownload() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "update-cancel-test.apk")
        val bytes = ByteArray(100_000)
        val release = UpdateRelease.parse(metadata(content = bytes))
        val job = launch {
            ReleaseDownloads(open = { Connection(it, bytes) }).download(release, file) { throw CancellationException() }
        }
        job.join()
        assertTrue(job.isCancelled)
        assertFalse(file.exists())
    }

    @Test fun apiRateLimitFallsBackToOfficialLatestAndChecksumFile() = runBlocking {
        val expected = UpdateRelease.parse(metadata())
        val checksum = "${expected.sha256}  LyricsFlow-v${expected.version}.apk\n".toByteArray()
        val calls = mutableListOf<URL>()
        val fallback = ReleaseDownloads(GitHubTransport { _, _, _, token ->
            assertTrue(token.isEmpty())
            throw GitHubFailure(403, "rate limited")
        }, open = { url ->
            calls += url
            when {
                url.path.endsWith("/latest") -> Connection(url, byteArrayOf(), 302, "https://github.com/$UPDATE_REPOSITORY/releases/tag/v1.0.3")
                url.path.endsWith("SHA256SUMS.txt") -> Connection(url, checksum)
                else -> Connection(url, "valid download".toByteArray())
            }
        })
        assertEquals(expected, fallback.latest())
        assertEquals(3, calls.size)
        assertTrue(calls.all { it.host == "github.com" })
    }

    @Test fun assetApiCanDownloadDirectlyAndFallsBackWithoutCredentials() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bytes = "valid download".toByteArray()
        val release = UpdateRelease.parse(metadata().apply { getJSONArray("assets").getJSONObject(0).put("id", 123) })
        val file = File(context.cacheDir, "update-api-test.apk")
        val urls = mutableListOf<URL>()
        try {
            ReleaseDownloads(open = { url ->
                urls += url
                if (url.host == "api.github.com") Connection(url, byteArrayOf(), 403) else Connection(url, bytes)
            }).download(release, file) {}
            assertEquals("https://api.github.com/repos/$UPDATE_REPOSITORY/releases/assets/123", urls.first().toString())
            assertEquals(release.url, urls.last().toString())
            assertArrayEquals(bytes, file.readBytes())
        } finally { file.delete() }
    }

    @Test fun optionalLivePublicReleaseAndAssetDownload() = runBlocking {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("updateNetwork") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "live-update-download.apk")
        try {
            val downloads = ReleaseDownloads()
            val release = downloads.latest()
            downloads.download(release, file) {}
            assertEquals(release.bytes, file.length())
            assertEquals(context.packageName, context.packageManager.getPackageArchiveInfo(file.path, 0)!!.packageName)
        } finally { file.delete() }
    }

    @Test fun latestTimeoutAndRetryHaveExplicitStates() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var attempts = 0
        val manager = AppUpdates(context, scope, ReleaseDownloads(GitHubTransport { _, _, _, _ ->
            attempts++
            if (attempts == 1) withTimeout(20) { delay(200); error("unreachable") }
            GitHubReply(200, metadata(BuildConfig.VERSION_NAME))
        }))
        try {
            withContext(Dispatchers.Main) { manager.check() }
            withTimeout(4000) { manager.state.first { it.stage == UpdateStage.ERROR } }
            assertTrue(manager.state.value.message.contains("超时"))
            withContext(Dispatchers.Main) { manager.check() }
            withTimeout(4000) { manager.state.first { it.stage == UpdateStage.LATEST } }
            assertEquals(2, attempts)
        } finally { scope.cancel() }
    }

    @Test fun corruptArchivesCannotReachInstallerAndProviderCannotShareOtherFiles() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "invalid-update.apk").apply { writeText("not an apk") }
        try {
            assertTrue(runCatching { verifyUpdateApk(context, file, "1.0.3") }.isFailure)
            assertTrue(runCatching {
                androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
            }.isFailure)
        } finally { file.delete() }
    }
}
