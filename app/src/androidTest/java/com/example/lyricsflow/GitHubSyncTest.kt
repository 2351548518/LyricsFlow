package com.example.lyricsflow

import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import com.example.lyricsflow.core.*
import com.example.lyricsflow.data.*
import java.io.File
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** A deterministic server fake: never uses the network or real GitHub credentials. */
internal class MemoryGitHub : GitHubTransport {
    var exists = true
    var privateRepo = true
    var head = "initial"
    var remote: Map<String, ByteArray>? = null
    var race = false
    var createCount = 0
    var patchCount = 0
    var refreshCount = 0
    var pollCount = 0
    var loginDenied = false
    var shortLived = false
    var pendingThenSlow = false
    val postedPaths = mutableListOf<String>()
    private val blobs = mutableMapOf<String, ByteArray>()
    private var nextFiles = mapOf<String, ByteArray>()
    private var parent = ""

    private fun account() = JSONObject().put("login", "qa-user").put("id", 42)

    private fun repository() =
        JSONObject()
            .put("name", "lyricsflow-sync")
            .put("id", 73)
            .put("owner", account())
            .put("private", privateRepo)
            .put("default_branch", "main")
            .put("permissions", JSONObject().put("push", true))

    override suspend fun send(
        method: String,
        path: String,
        body: JSONObject?,
        token: String,
    ): GitHubReply {
        val o =
            when {
                path == "/login/device/code" -> {
                    assertEquals("repo", body!!.getString("scope"))
                    assertFalse(body.has("client_secret"))
                    JSONObject()
                        .put("device_code", "not-a-real-device-code")
                        .put("user_code", "TEST-CODE")
                        .put("verification_uri", "https://github.com/login/device")
                        .put("interval", 5)
                        .put("expires_in", 900)
                }
                path == "/login/oauth/access_token" -> {
                    assertFalse(body!!.has("client_secret"))
                    if (body.getString("grant_type") == "refresh_token") {
                        refreshCount++
                        JSONObject()
                            .put("access_token", "fake-refreshed")
                            .put("refresh_token", "fake-next-refresh")
                            .put("expires_in", 3600)
                    } else {
                        pollCount++
                        when {
                            loginDenied -> JSONObject().put("error", "access_denied")
                            pendingThenSlow && pollCount == 1 ->
                                JSONObject().put("error", "authorization_pending")
                            pendingThenSlow && pollCount == 2 ->
                                JSONObject().put("error", "slow_down").put("interval", 10)
                            else ->
                                JSONObject()
                                    .put("access_token", "fake-access")
                                    .put("scope", "repo")
                                    .also {
                                        if (shortLived)
                                            it.put("refresh_token", "fake-refresh")
                                                .put("expires_in", 1)
                                    }
                        }
                    }
                }
                path == "/user" -> account()
                path == "/user/repos" -> {
                    assertTrue(body!!.getBoolean("private"))
                    assertTrue(body.getBoolean("auto_init"))
                    exists = true
                    createCount++
                    repository()
                }
                path == "/repos/qa-user/lyricsflow-sync" -> {
                    if (!exists) throw GitHubFailure(404, "not found")
                    repository()
                }
                path.endsWith("/git/ref/heads/main") ->
                    JSONObject().put("object", JSONObject().put("sha", head))
                path.contains("/git/commits/") ->
                    JSONObject().put("tree", JSONObject().put("sha", "root-$head"))
                method == "GET" && path.endsWith("/git/trees/root-$head") ->
                    JSONObject()
                        .put(
                            "tree",
                            JSONArray().also {
                                it.put(
                                    JSONObject()
                                        .put("path", "README.md")
                                        .put("type", "blob")
                                        .put("sha", "untouched-readme")
                                )
                                if (remote != null)
                                    it.put(
                                        JSONObject()
                                            .put("path", "lyricsflow")
                                            .put("type", "tree")
                                            .put("sha", "folder-$head")
                                    )
                            },
                        )
                method == "GET" && path.endsWith("/git/trees/folder-$head") ->
                    JSONObject()
                        .put(
                            "tree",
                            JSONArray().also { list ->
                                remote!!.forEach { (name, bytes) ->
                                    val sha = digest(name + String(bytes, Charsets.UTF_8))
                                    blobs[sha] = bytes
                                    list.put(
                                        JSONObject()
                                            .put("path", name)
                                            .put("type", "blob")
                                            .put("mode", "100644")
                                            .put("sha", sha)
                                            .put("size", bytes.size)
                                    )
                                }
                            },
                        )
                method == "GET" && path.contains("/git/blobs/") ->
                    JSONObject()
                        .put("encoding", "base64")
                        .put(
                            "content",
                            Base64.encodeToString(
                                blobs.getValue(path.substringAfterLast('/')),
                                Base64.NO_WRAP,
                            ),
                        )
                method == "POST" && path.endsWith("/git/blobs") -> {
                    val bytes = Base64.decode(body!!.getString("content"), Base64.NO_WRAP)
                    val sha = digest(String(bytes, Charsets.UTF_8))
                    blobs[sha] = bytes
                    JSONObject().put("sha", sha)
                }
                method == "POST" && path.endsWith("/git/trees") -> {
                    assertEquals("root-$head", body!!.getString("base_tree"))
                    nextFiles = buildMap {
                        val a = body.getJSONArray("tree")
                        for (i in 0 until a.length()) {
                            val item = a.getJSONObject(i)
                            val p = item.getString("path")
                            postedPaths.add(p)
                            assertTrue(p.startsWith("lyricsflow/"))
                            put(p.substringAfter('/'), blobs.getValue(item.getString("sha")))
                        }
                    }
                    JSONObject().put("sha", "new-tree")
                }
                method == "POST" && path.endsWith("/git/commits") -> {
                    parent = body!!.getJSONArray("parents").getString(0)
                    JSONObject().put("sha", "new-commit")
                }
                method == "PATCH" -> {
                    assertEquals("/repos/qa-user/lyricsflow-sync/git/refs/heads/main", path)
                    assertFalse(body!!.getBoolean("force"))
                    patchCount++
                    if (race) head = "other-device"
                    if (parent != head) throw GitHubFailure(422, "non fast-forward")
                    remote = nextFiles
                    head = body.getString("sha")
                    JSONObject().put("object", JSONObject().put("sha", head))
                }
                else -> error("Unexpected $method $path")
            }
        return GitHubReply(200, o)
    }
}

class GitHubSyncTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val repo = GitHubRepo("lyricsflow-sync", "main", 73)

    private fun files() =
        linkedMapOf(
            "manifest.json" to
                """{"format":"lyricsflow.backup","schemaVersion":"1.0"}""".toByteArray(),
            "data.json" to "[]".toByteArray(),
            "settings.json" to """{"fontSize":36,"coverBlur":12}""".toByteArray(),
        )

    @Test
    fun createsPrivateRepositoryAndRejectsPublicExistingRepository() = runBlocking {
        val server = MemoryGitHub().apply { exists = false }
        val api = GitHubApi(server)
        assertEquals(repo, api.repository("fake", "qa-user", 42, repo.name, true))
        assertEquals(1, server.createCount)
        server.privateRepo = false
        try {
            api.repository("fake", "qa-user", 42, repo.name, true)
            fail("public repository accepted")
        } catch (_: IllegalArgumentException) {}
        assertEquals(1, server.createCount)
    }

    @Test
    fun atomicUploadRoundtripPreservesOtherPathsAndSkipsUnchangedSnapshot() = runBlocking {
        val server = MemoryGitHub()
        val api = GitHubApi(server)
        val empty = api.snapshot("fake", "qa-user", repo)
        assertEquals("new-commit", api.upload("fake", "qa-user", empty, files(), ""))
        assertEquals(
            SnapshotFiles.names.map { "lyricsflow/$it" }.toSet(),
            server.postedPaths.toSet(),
        )
        val downloaded = api.snapshot("fake", "qa-user", repo)
        files().forEach { (k, v) -> assertArrayEquals(v, downloaded.files[k]) }
        assertEquals(
            36,
            Transfer.validateBackup(SnapshotFiles.zip(downloaded.files)).second.getInt("fontSize"),
        )
        api.upload("fake", "qa-user", downloaded, files(), downloaded.head)
        assertEquals(1, server.patchCount)
    }

    @Test
    fun refusesUnseenSnapshotAndConcurrentBranchUpdate() = runBlocking {
        val server = MemoryGitHub().apply { remote = files() }
        val api = GitHubApi(server)
        val snapshot = api.snapshot("fake", "qa-user", repo)
        try {
            api.upload("fake", "qa-user", snapshot, files(), "old-head")
            fail("stale baseline accepted")
        } catch (_: IllegalArgumentException) {}
        assertEquals(0, server.patchCount)
        server.race = true
        val changed = files().apply { this["settings.json"] = "{\"fontSize\":40}".toByteArray() }
        try {
            api.upload("fake", "qa-user", snapshot, changed, snapshot.head)
            fail("race accepted")
        } catch (e: GitHubFailure) {
            assertEquals(422, e.status)
        }
        assertEquals("other-device", server.head)
        assertArrayEquals(files().getValue("settings.json"), server.remote!!["settings.json"])
    }

    @Test
    fun rejectsUnknownCloudFilesAndUnsupportedSchemaBeforeRestore() = runBlocking {
        val server =
            MemoryGitHub().apply {
                remote = files() + ("../credentials.xml" to "secret".toByteArray())
            }
        val api = GitHubApi(server)
        try {
            api.snapshot("fake", "qa-user", repo)
            fail("unknown path accepted")
        } catch (_: IllegalArgumentException) {}
        server.remote =
            files().apply {
                this["manifest.json"] =
                    "{\"format\":\"other-app\",\"schemaVersion\":\"1.0\"}".toByteArray()
            }
        try {
            api.snapshot("fake", "qa-user", repo)
            fail("wrong format accepted")
        } catch (_: IllegalArgumentException) {}
        assertEquals(0, server.patchCount)
    }

    private suspend fun idle(sync: GitHubSync) {
        withTimeout(15000) { while (sync.state.value.busy) delay(20) }
    }

    @Test
    fun restoreKeepsLocalOnlyRowsCreatesRecoveryAndDisablesPaidTranslation() = runBlocking {
        val graph = AppGraph(context.applicationContext as android.app.Application)
        val original = graph.state.value.prefs
        val slot = "github-restore-test"
        val id = "github-restore-fixture"
        val localOnly = "github-local-only-fixture"
        val localBody = trackJson(Track("Local original")).toString()
        graph.store.put("binding", id, localBody)
        graph.store.put("binding", localOnly, localBody)
        val cloudBody = trackJson(Track("Cloud replacement")).toString()
        val server =
            MemoryGitHub().apply {
                remote =
                    files().apply {
                        this["data.json"] =
                            JSONArray()
                                .put(
                                    JSONObject()
                                        .put("kind", "binding")
                                        .put("id", id)
                                        .put("body", cloudBody)
                                )
                                .toString()
                                .toByteArray()
                    }
            }
        val sync = GitHubSync(graph, GitHubApi(server), slot) {}
        try {
            withContext(Dispatchers.Main) {
                graph.preferences(original.copy(consent = true, autoTranslate = true))
                sync.configure("test-public-client-id", repo.name)
                sync.signIn()
            }
            idle(sync)
            withContext(Dispatchers.Main) { sync.connect() }
            idle(sync)
            withContext(Dispatchers.Main) { sync.download() }
            idle(sync)
            assertTrue(sync.state.value.downloadPreview.isNotBlank())
            assertEquals(localBody, graph.store.get("binding", id))
            withContext(Dispatchers.Main) { sync.restore() }
            idle(sync)
            assertFalse(sync.state.value.message, sync.state.value.failed)
            assertEquals(cloudBody, graph.store.get("binding", id))
            assertEquals(localBody, graph.store.get("binding", localOnly))
            assertEquals(36f, graph.state.value.prefs.fontSize, .01f)
            assertFalse(graph.state.value.prefs.consent)
            assertFalse(graph.state.value.prefs.autoTranslate)
            val recovery = Transfer.validateBackup(sync.recoveryBytes())
            assertEquals(localBody, recovery.first.first { it.second == id }.third)
            assertTrue(sync.state.value.hasRecovery)
        } finally {
            withContext(Dispatchers.Main) { graph.preferences(original) }
            graph.scope.cancel()
            graph.store.remove("binding", id)
            graph.store.remove("binding", localOnly)
            context.getSharedPreferences(slot, 0).edit().clear().commit()
            Secrets(context, slot).write("")
            File(context.noBackupFilesDir, "$slot-recovery.zip").delete()
        }
    }

    @Test
    fun cloudUpdateAfterDownloadPreviewLeavesLocalStateUntouched() = runBlocking {
        val graph = AppGraph(context.applicationContext as android.app.Application)
        val original = graph.state.value.prefs
        val slot = "github-stale-download-test"
        val server = MemoryGitHub().apply { remote = files() }
        val sync = GitHubSync(graph, GitHubApi(server), slot) {}
        try {
            withContext(Dispatchers.Main) {
                sync.configure("test-public-client-id", repo.name)
                sync.signIn()
            }
            idle(sync)
            withContext(Dispatchers.Main) { sync.connect() }
            idle(sync)
            withContext(Dispatchers.Main) { sync.download() }
            idle(sync)
            server.head = "changed-after-preview"
            withContext(Dispatchers.Main) { sync.restore() }
            idle(sync)
            assertTrue(sync.state.value.failed)
            assertTrue(sync.state.value.message.contains("重新下载"))
            assertEquals(original, graph.state.value.prefs)
            assertFalse(File(context.noBackupFilesDir, "$slot-recovery.zip").exists())
        } finally {
            graph.scope.cancel()
            context.getSharedPreferences(slot, 0).edit().clear().commit()
            Secrets(context, slot).write("")
        }
    }

    @Test
    fun deviceFlowWaitsSlowsDownRefreshesAndSeparatesEncryptedCredentialSlots() = runBlocking {
        val graph = AppGraph(context.applicationContext as android.app.Application)
        val server =
            MemoryGitHub().apply {
                pendingThenSlow = true
                shortLived = true
            }
        val waits = mutableListOf<Long>()
        val slot = "github-sync-test"
        val config = context.getSharedPreferences(slot, 0)
        config.edit().clear().commit()
        Secrets(context, slot).write("")
        val other = Secrets(context, "test-translation")
        other.write("not-a-real-api-key")
        val sync = GitHubSync(graph, GitHubApi(server), slot) { waits.add(it) }
        try {
            withContext(Dispatchers.Main) {
                sync.configure("test-public-client-id", repo.name)
                sync.signIn()
            }
            idle(sync)
            assertEquals(listOf(5000L, 5000L, 10000L), waits)
            assertEquals("qa-user", sync.state.value.login)
            val restarted = GitHubSync(graph, GitHubApi(server), slot)
            assertEquals("qa-user", restarted.state.value.login)
            assertTrue(restarted.state.value.message.contains("已登录 @qa-user"))
            assertFalse(restarted.state.value.connected)
            assertFalse(
                context
                    .getSharedPreferences("credentials", 0)
                    .getString(slot, "")!!
                    .contains("fake-access")
            )
            withContext(Dispatchers.Main) { sync.connect() }
            idle(sync)
            assertTrue(sync.state.value.message, sync.state.value.connected)
            assertEquals(1, server.refreshCount)
            val exported =
                SnapshotFiles.unzip(Transfer.backup(graph)).values.joinToString {
                    String(it, Charsets.UTF_8)
                }
            assertFalse(exported.contains("fake-refresh"))
            assertFalse(exported.contains("not-a-real-api-key"))
            assertFalse(exported.contains("test-public-client-id"))
            withContext(Dispatchers.Main) { sync.signOut() }
            idle(sync)
            assertEquals("", Secrets(context, slot).read())
            assertEquals("not-a-real-api-key", other.read())
        } finally {
            graph.scope.cancel()
            config.edit().clear().commit()
            Secrets(context, slot).write("")
            other.write("")
        }
    }

    @Test
    fun deniedDeviceAuthorizationStopsPolling() = runBlocking {
        val graph = AppGraph(context.applicationContext as android.app.Application)
        val server = MemoryGitHub().apply { loginDenied = true }
        val slot = "github-denied-test"
        val sync = GitHubSync(graph, GitHubApi(server), slot) {}
        try {
            withContext(Dispatchers.Main) {
                sync.configure("test-public-client-id", repo.name)
                sync.signIn()
            }
            idle(sync)
            assertTrue(sync.state.value.failed)
            assertEquals("", sync.state.value.login)
            assertEquals(1, server.pollCount)
            assertEquals("", sync.state.value.userCode)
        } finally {
            graph.scope.cancel()
            context.getSharedPreferences(slot, 0).edit().clear().commit()
            Secrets(context, slot).write("")
        }
    }
}
