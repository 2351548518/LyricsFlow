package com.example.lyricsflow.data

import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

internal class GitHubFailure(val status: Int, message: String) : Exception(message)

internal data class GitHubReply(val status: Int, val json: JSONObject)

/** Fixed GitHub hosts only. No redirects, response bodies or credentials in diagnostics. */
internal fun interface GitHubTransport {
    suspend fun send(method: String, path: String, body: JSONObject?, token: String): GitHubReply
}

internal class GitHubHttp(
    private val timeoutMs: Long = 45_000,
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) : GitHubTransport {
    private companion object {
        val requests =
            Executors.newFixedThreadPool(2) { task ->
                Thread(task, "GitHub-request").apply { isDaemon = true }
            }
        val disconnects =
            Executors.newFixedThreadPool(2) { task ->
                Thread(task, "GitHub-disconnect").apply { isDaemon = true }
            }
    }

    override suspend fun send(method: String, path: String, body: JSONObject?, token: String) =
        withTimeout(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val connection = AtomicReference<HttpURLConnection>()
                val request = requests.submit {
                    try {
                        val reply =
                            exchange(method, path, body, token) { c ->
                                connection.set(c)
                                if (!continuation.isActive) throw java.io.InterruptedIOException()
                            }
                        continuation.resume(reply)
                    } catch (e: Exception) {
                        continuation.resumeWithException(e)
                    }
                }
                continuation.invokeOnCancellation {
                    request.cancel(true)
                    // Some vendor HTTP/DNS implementations ignore thread interruption. Return
                    // control immediately and disconnect off the UI thread; discard late replies.
                    connection.get()?.let { c -> disconnects.execute { c.disconnect() } }
                }
            }
        }

    private fun exchange(
        method: String,
        path: String,
        body: JSONObject?,
        token: String,
        connected: (HttpURLConnection) -> Unit,
    ): GitHubReply {
        require(path.startsWith("/") && !path.startsWith("//"))
        val oauth = path in setOf("/login/device/code", "/login/oauth/access_token")
        require(!oauth || token.isEmpty())
        val c = open(URL((if (oauth) "https://github.com" else "https://api.github.com") + path))
        try {
            connected(c)
            c.requestMethod = method
            c.instanceFollowRedirects = false
            c.connectTimeout = 10000
            c.readTimeout = 30000
            c.setRequestProperty("User-Agent", "LyricsFlow-Android/1.0")
            c.setRequestProperty(
                "Accept",
                if (oauth) "application/json" else "application/vnd.github+json",
            )
            if (!oauth) c.setRequestProperty("X-GitHub-Api-Version", "2026-03-10")
            if (token.isNotEmpty()) c.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                val bytes =
                    if (oauth)
                        body
                            .keys()
                            .asSequence()
                            .joinToString("&") {
                                enc(it) + "=" + enc(body.getString(it))
                            }
                            .toByteArray()
                    else body.toString().toByteArray()
                c.doOutput = true
                c.setRequestProperty(
                    "Content-Type",
                    if (oauth) "application/x-www-form-urlencoded" else "application/json",
                )
                c.setFixedLengthStreamingMode(bytes.size)
                c.outputStream.use { it.write(bytes) }
            }
            val code = c.responseCode
            if (oauth && code == 400) {
                // OAuth protocol errors may use HTTP 400 instead of a 200 JSON response.
                val raw = c.errorStream?.use { it.readBytesLimited(64_000) }
                val error = raw?.let {
                    runCatching { JSONObject(String(it, Charsets.UTF_8)) }.getOrNull()
                }
                if (error?.has("error") == true) return GitHubReply(code, error)
            }
            if (code !in 200..299)
                throw GitHubFailure(
                    code,
                    when (code) {
                        401 -> "GitHub 登录已失效，请重新登录"
                        403,
                        429 -> "GitHub 拒绝请求或正在限流，请检查仓库权限并稍后重试"
                        404 -> "找不到仓库或没有访问权限"
                        409,
                        422 -> "远端发生冲突或仓库规则阻止提交，请重新下载检查；未强制覆盖"
                        in 300..399 -> "GitHub 地址已变更，请检查仓库名称；不会转发凭证到重定向地址"
                        else -> "GitHub 请求失败（HTTP $code），请稍后重试"
                    },
                )
            val bytes = c.inputStream.use { it.readBytesLimited(30_000_000) }
            return GitHubReply(code, JSONObject(String(bytes, Charsets.UTF_8)))
        } finally {
            c.disconnect()
        }
    }
}

internal fun enc(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

internal data class GitHubRepo(val name: String, val branch: String, val id: Long)

internal data class GitHubSnapshot(
    val repo: GitHubRepo,
    val head: String,
    val tree: String,
    val files: Map<String, ByteArray>,
)

/** One commit publishes the entire snapshot. Updating the branch is always a fast-forward. */
internal class GitHubApi(private val http: GitHubTransport = GitHubHttp()) {
    suspend fun oauth(path: String, body: JSONObject) = http.send("POST", path, body, "").json

    suspend fun user(token: String) = http.send("GET", "/user", null, token).json

    suspend fun repository(
        token: String,
        login: String,
        userId: Long,
        name: String,
        create: Boolean,
    ): GitHubRepo {
        require(Regex("[A-Za-z0-9_.-]{1,100}").matches(name) && name !in listOf(".", "..")) {
            "仓库名只能包含字母、数字、点、下划线和短横线"
        }
        val path = "/repos/${enc(login)}/${enc(name)}"
        val o =
            try {
                http.send("GET", path, null, token).json
            } catch (e: GitHubFailure) {
                if (e.status != 404 || !create) throw e
                http
                    .send(
                        "POST",
                        "/user/repos",
                        JSONObject()
                            .put("name", name)
                            .put("private", true)
                            .put("auto_init", true)
                            .put("description", "LyricsFlow personal settings, themes and lyrics")
                            .put("has_issues", false)
                            .put("has_projects", false)
                            .put("has_wiki", false),
                        token,
                    )
                    .json
            }
        require(o.getBoolean("private")) { "只允许同步到私有仓库，请换一个仓库名；不会修改现有仓库的可见性" }
        require(o.getJSONObject("owner").getLong("id") == userId) { "只能使用当前 GitHub 账号拥有的个人仓库" }
        require(!o.optBoolean("archived") && !o.optBoolean("disabled")) { "仓库已归档或禁用" }
        require(o.optJSONObject("permissions")?.optBoolean("push") != false) { "缺少仓库写入权限" }
        return GitHubRepo(o.getString("name"), o.getString("default_branch"), o.getLong("id"))
    }

    private fun root(login: String, r: GitHubRepo) = "/repos/${enc(login)}/${enc(r.name)}"

    suspend fun snapshot(token: String, login: String, repo: GitHubRepo): GitHubSnapshot =
        withContext(Dispatchers.IO) {
            val root = root(login, repo)
            val head =
                http
                    .send("GET", "$root/git/ref/heads/${enc(repo.branch)}", null, token)
                    .json
                    .getJSONObject("object")
                    .getString("sha")
            val tree =
                http
                    .send("GET", "$root/git/commits/$head", null, token)
                    .json
                    .getJSONObject("tree")
                    .getString("sha")
            // Read only the owned subdirectory. Other repository files remain untouched.
            val entries = http.send("GET", "$root/git/trees/$tree", null, token).json
            require(!entries.optBoolean("truncated")) { "仓库目录过大，无法安全检查" }
            val list = entries.getJSONArray("tree")
            val folder =
                (0 until list.length())
                    .map { list.getJSONObject(it) }
                    .firstOrNull { it.getString("path") == "lyricsflow" }
            if (folder == null) return@withContext GitHubSnapshot(repo, head, tree, emptyMap())
            require(folder.getString("type") == "tree") { "lyricsflow 路径被其他文件占用" }
            val directory =
                http.send("GET", "$root/git/trees/${folder.getString("sha")}", null, token).json
            require(!directory.optBoolean("truncated"))
            val a = directory.getJSONArray("tree")
            require(a.length() == SnapshotFiles.names.size) { "云端目录不是兼容的 LyricsFlow 快照；不会覆盖" }
            val files = linkedMapOf<String, ByteArray>()
            var total = 0
            for (i in 0 until a.length()) {
                val entry = a.getJSONObject(i)
                val name = entry.getString("path")
                require(
                    name in SnapshotFiles.names &&
                        name !in files &&
                        entry.getString("type") == "blob" &&
                        entry.getString("mode") == "100644"
                ) {
                    "云端快照包含不支持的文件或路径"
                }
                val size = entry.getLong("size")
                require(size in 0..20_000_000 && total + size <= 20_000_000) { "云端快照超过 20 MB" }
                val blob =
                    http.send("GET", "$root/git/blobs/${entry.getString("sha")}", null, token).json
                require(blob.getString("encoding") == "base64")
                val bytes = Base64.decode(blob.getString("content"), Base64.DEFAULT)
                require(bytes.size.toLong() == size) { "云端文件大小不一致" }
                total += bytes.size
                files[name] = bytes
            }
            Transfer.validateBackup(SnapshotFiles.zip(files))
            GitHubSnapshot(repo, head, tree, files)
        }

    suspend fun upload(
        token: String,
        login: String,
        snapshot: GitHubSnapshot,
        files: Map<String, ByteArray>,
        baseline: String,
    ): String =
        withContext(Dispatchers.IO) {
            require(snapshot.files.isEmpty() || baseline == snapshot.head) {
                "云端已有新的资料，请先下载并确认合并，再上传；不会覆盖其他设备的修改"
            }
            Transfer.validateBackup(SnapshotFiles.zip(files))
            if (
                files.keys == snapshot.files.keys &&
                    files.all { (key, value) -> value.contentEquals(snapshot.files[key]) }
            )
                return@withContext snapshot.head
            val root = root(login, snapshot.repo)
            val tree = JSONArray()
            for ((name, bytes) in files) {
                val sha =
                    http
                        .send(
                            "POST",
                            "$root/git/blobs",
                            JSONObject()
                                .put("encoding", "base64")
                                .put("content", Base64.encodeToString(bytes, Base64.NO_WRAP)),
                            token,
                        )
                        .json
                        .getString("sha")
                tree.put(
                    JSONObject()
                        .put("path", "lyricsflow/$name")
                        .put("mode", "100644")
                        .put("type", "blob")
                        .put("sha", sha)
                )
            }
            val newTree =
                http
                    .send(
                        "POST",
                        "$root/git/trees",
                        JSONObject().put("base_tree", snapshot.tree).put("tree", tree),
                        token,
                    )
                    .json
                    .getString("sha")
            val commit =
                http
                    .send(
                        "POST",
                        "$root/git/commits",
                        JSONObject()
                            .put("message", "Sync LyricsFlow settings, themes and lyrics")
                            .put("tree", newTree)
                            .put("parents", JSONArray().put(snapshot.head)),
                        token,
                    )
                    .json
                    .getString("sha")
            // Re-check privacy and repository identity immediately before publishing.
            val current =
                repository(token, login, user(token).getLong("id"), snapshot.repo.name, false)
            require(current == snapshot.repo) { "仓库或默认分支已改变，请重新连接" }
            http.send(
                "PATCH",
                "$root/git/refs/heads/${enc(current.branch)}",
                JSONObject().put("sha", commit).put("force", false),
                token,
            )
            commit
        }
}

internal object SnapshotFiles {
    val names = setOf("manifest.json", "data.json", "settings.json")

    fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        Transfer.validateBackup(bytes)
        val files = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                files[e.name] = zip.readBytesLimited(20_000_000)
            }
        }
        return files
    }

    fun zip(files: Map<String, ByteArray>): ByteArray {
        require(files.keys == names && files.values.sumOf { it.size.toLong() } <= 20_000_000) {
            "快照文件缺失或超过 20 MB"
        }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in files) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
