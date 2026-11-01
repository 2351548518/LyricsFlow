package com.example.lyricsflow

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.example.lyricsflow.data.*
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Optional emulator journey: supply a higher-version APK signed with the test app's key. */
class AppUpdateInstallTest {
    @Test fun signedApkCanBeDownloadedVerifiedAndHandedToSystemInstaller() = runBlocking {
        val fixture = InstrumentationRegistry.getArguments().getString("updateFixture").orEmpty()
        assumeTrue("Requires an explicitly supplied signed APK fixture", fixture.isNotBlank())
        val version = InstrumentationRegistry.getArguments().getString("updateVersion")!!
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bytes = File(fixture).readBytes()
        val json = JSONObject().put("draft", false).put("prerelease", false).put("tag_name", "v$version")
            .put("assets", JSONArray().put(JSONObject().put("name", "LyricsFlow-v$version.apk")
                .put("state", "uploaded").put("size", bytes.size)
                .put("digest", "sha256:" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) })
                .put("browser_download_url", "https://github.com/$UPDATE_REPOSITORY/releases/download/v$version/LyricsFlow-v$version.apk")))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val manager = AppUpdates(context, scope, ReleaseDownloads(
            GitHubTransport { _, _, _, _ -> GitHubReply(200, json) },
            open = { url -> object : HttpURLConnection(url) {
                override fun connect() {}
                override fun disconnect() {}
                override fun usingProxy() = false
                override fun getResponseCode() = 200
                override fun getContentLengthLong() = bytes.size.toLong()
                override fun getInputStream() = ByteArrayInputStream(bytes)
            } },
        ))
        try {
            withContext(Dispatchers.Main) { manager.check() }
            val state = withTimeout(10_000) { manager.state.first { it.stage in setOf(UpdateStage.READY, UpdateStage.ERROR) } }
            assertEquals(state.message, UpdateStage.READY, state.stage)
            val intent = manager.installerIntent()
            assertEquals("content", intent.data!!.scheme)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertArrayEquals(bytes, context.contentResolver.openInputStream(intent.data!!)!!.use { it.readBytes() })
            if (InstrumentationRegistry.getArguments().getString("openInstaller") == "true") {
                withContext(Dispatchers.Main) { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                // Gives the Android CLI journey time to inspect the real system confirmation UI.
                delay(30_000)
            }
        } finally { scope.cancel() }
    }
}
