package com.example.lyricsflow.data

import android.os.SystemClock
import androidx.core.content.edit
import com.example.lyricsflow.core.AppGraph
import java.io.File
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONException
import org.json.JSONObject

// Public OAuth application identifier; never put a Client Secret in the APK.
internal const val DEFAULT_GITHUB_CLIENT_ID = "Ov23liBtXrG7lSZomxKz"

data class GitHubState(
    val clientId: String = "",
    val login: String = "",
    val repository: String = "lyricsflow-sync",
    val connected: Boolean = false,
    val busy: Boolean = false,
    val userCode: String = "",
    val message: String = "尚未登录；本地功能可正常使用",
    val failed: Boolean = false,
    val includeBackground: Boolean = false,
    val uploadPreview: String = "",
    val downloadPreview: String = "",
    val hasRecovery: Boolean = false,
    val lastSync: Long = 0,
)

class GitHubSync
internal constructor(
    private val graph: AppGraph,
    private val api: GitHubApi = GitHubApi(),
    storageName: String = "github-sync",
    defaultClientId: String = DEFAULT_GITHUB_CLIENT_ID,
    private val pollWait: suspend (Long) -> Unit = { delay(it) },
) {
    private val prefs = graph.app.getSharedPreferences(storageName, 0)
    private val secrets = Secrets(graph.app, storageName)
    private val recovery = File(graph.app.noBackupFilesDir, "$storageName-recovery.zip")
    private val storedLogin =
        if (secrets.read().isNotBlank()) prefs.getString("login", "").orEmpty() else ""
    private val mutable =
        MutableStateFlow(
            GitHubState(
                clientId =
                    prefs.getString("clientId", defaultClientId).orEmpty().ifBlank {
                        defaultClientId
                    },
                login = storedLogin,
                message =
                    if (storedLogin.isBlank()) "尚未登录；本地功能可正常使用" else "已登录 @$storedLogin；请重新连接私有仓库",
                repository = prefs.getString("repository", "lyricsflow-sync").orEmpty(),
                hasRecovery = recovery.isFile,
                lastSync = prefs.getLong("lastSync", 0),
            )
        )
    val state = mutable.asStateFlow()
    private var operation: Job? = null
    private var pendingUpload: Pair<GitHubSnapshot, Map<String, ByteArray>>? = null
    private var pendingDownload: GitHubSnapshot? = null

    private fun change(block: (GitHubState) -> GitHubState) = mutable.update(block)

    // The baseline and account identity must survive a process death before success is shown.
    @android.annotation.SuppressLint("ApplySharedPref", "UseKtx")
    private suspend fun persist(block: android.content.SharedPreferences.Editor.() -> Unit) {
        withContext(Dispatchers.IO) {
            check(prefs.edit().apply(block).commit()) { "无法保存同步状态，请检查可用存储空间" }
        }
    }

    fun configure(clientId: String, repository: String) {
        if (state.value.busy || state.value.login.isNotBlank()) return
        require(Regex("[A-Za-z0-9_.-]{5,100}").matches(clientId.trim())) {
            "请填写 GitHub OAuth App 的公开 Client ID"
        }
        prefs.edit {
            putString("clientId", clientId.trim())
            putString("repository", repository.trim())
        }
        change {
            it.copy(
                clientId = clientId.trim(),
                repository = repository.trim(),
                failed = false,
                message = "已保存 Client ID，可以开始登录",
            )
        }
    }

    fun repository(name: String) {
        if (state.value.busy) return
        prefs.edit { putString("repository", name.trim()) }
        pendingUpload = null
        pendingDownload = null
        change {
            it.copy(
                repository = name.trim(),
                connected = false,
                uploadPreview = "",
                downloadPreview = "",
            )
        }
    }

    fun includeBackground(value: Boolean) {
        if (!state.value.busy) change { it.copy(includeBackground = value) }
    }

    private fun run(message: String, block: suspend () -> Unit) {
        if (state.value.busy) return
        change { it.copy(busy = true, message = message, failed = false) }
        operation =
            graph.scope.launch {
                try {
                    block()
                } catch (_: TimeoutCancellationException) {
                    change {
                        it.copy(
                            message =
                                "GitHub 请求超时，请检查手机能否访问 github.com 和 api.github.com 后重试；若正在上传，请先重新检查云端版本",
                            failed = true,
                            userCode = "",
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (e is GitHubFailure && e.status == 401) {
                        secrets.write("")
                        change { it.copy(login = "", connected = false) }
                    }
                    val text =
                        when (e) {
                            is GitHubFailure -> e.message
                            is IOException -> "网络连接未完成，请检查网络后重试；本地资料未删除"
                            is JSONException -> "GitHub 返回了无法识别的数据，请重新登录或检查远端快照"
                            is IllegalArgumentException,
                            is IllegalStateException -> e.message
                            else -> "操作未完成，请稍后重试"
                        }.orEmpty()
                    change { it.copy(message = text, failed = true, userCode = "") }
                } finally {
                    change { it.copy(busy = false) }
                }
            }
    }

    fun signIn() =
        run("正在向 GitHub 申请设备验证码…") {
            val client = state.value.clientId
            require(client.isNotBlank()) { "先保存 OAuth App Client ID，并启用 Device Flow" }
            val code =
                api.oauth(
                    "/login/device/code",
                    JSONObject().put("client_id", client).put("scope", "repo"),
                )
            oauthError(code)
            require(code.getString("verification_uri") == "https://github.com/login/device") {
                "GitHub 返回的授权地址不受支持"
            }
            var interval = code.getLong("interval").coerceAtLeast(5)
            val deadline =
                SystemClock.elapsedRealtime() + code.getLong("expires_in").coerceIn(1, 1800) * 1000
            change {
                it.copy(
                    userCode = code.getString("user_code"),
                    message = "请在 GitHub 输入验证码并授权，然后回到此页等待登录完成",
                )
            }
            while (SystemClock.elapsedRealtime() < deadline) {
                pollWait(interval * 1000)
                if (SystemClock.elapsedRealtime() >= deadline) break
                val result =
                    api.oauth(
                        "/login/oauth/access_token",
                        JSONObject()
                            .put("client_id", client)
                            .put("device_code", code.getString("device_code"))
                            .put("grant_type", "urn:ietf:params:oauth:grant-type:device_code"),
                    )
                when (result.optString("error")) {
                    "authorization_pending" -> continue
                    "slow_down" -> {
                        interval = maxOf(interval + 5, result.optLong("interval", interval + 5))
                        continue
                    }
                    "" -> {
                        val token = result.getString("access_token")
                        require(result.optString("scope").split(',', ' ').contains("repo")) {
                            "没有授予 repo 权限，无法创建和同步私有仓库"
                        }
                        val account = api.user(token)
                        currentCoroutineContext().ensureActive()
                        val id = account.getLong("id")
                        if (prefs.getLong("userId", -1) != id)
                            persist {
                                remove("baseline")
                                remove("repoId")
                                remove("branch")
                                remove("lastSync")
                            }
                        saveToken(result)
                        val login = account.getString("login")
                        persist {
                            putLong("userId", id)
                            putString("login", login)
                        }
                        change {
                            it.copy(
                                login = login,
                                userCode = "",
                                connected = false,
                                lastSync = prefs.getLong("lastSync", 0),
                                message = "已登录 @$login；请选择专用私有仓库",
                            )
                        }
                        return@run
                    }
                    else -> oauthError(result)
                }
            }
            error("设备验证码已过期，请重新登录")
        }

    fun cancelSignIn() {
        if (!state.value.busy || state.value.login.isNotBlank()) return
        operation?.cancel()
        // Leave the cancelled job assigned until its finally block has finished.
        change { it.copy(userCode = "", message = "已取消登录") }
    }

    private fun oauthError(o: JSONObject) {
        if (o.optString("error").isBlank()) return
        error(
            when (o.getString("error")) {
                "device_flow_disabled" -> "请在 OAuth App 设置中启用 Enable Device Flow"
                "incorrect_client_credentials" -> "Client ID 无效，请检查 OAuth App 注册信息"
                "access_denied" -> "你已拒绝本次授权，可重新登录"
                "expired_token",
                "token_expired" -> "验证码已过期，请重新登录"
                "bad_refresh_token" -> "GitHub 刷新凭证已过期或撤销，请退出后重新登录"
                else -> "GitHub 授权失败，请检查 OAuth App 设置并重新登录"
            }
        )
    }

    private suspend fun saveToken(o: JSONObject) {
        require(o.getString("access_token").isNotBlank())
        val data = JSONObject().put("access_token", o.getString("access_token"))
        if (o.has("refresh_token")) data.put("refresh_token", o.getString("refresh_token"))
        if (o.has("expires_in"))
            data.put("expiresAt", System.currentTimeMillis() + o.getLong("expires_in") * 1000)
        withContext(Dispatchers.IO) { secrets.write(data.toString()) }
    }

    private suspend fun token(): String {
        val raw = secrets.read()
        require(raw.isNotBlank() && state.value.login.isNotBlank()) { "请先登录 GitHub" }
        val data = JSONObject(raw)
        if (data.optLong("expiresAt", Long.MAX_VALUE) > System.currentTimeMillis() + 60000)
            return data.getString("access_token")
        require(data.optString("refresh_token").isNotBlank()) { "GitHub 登录已过期，请退出后重新登录" }
        val result =
            api.oauth(
                "/login/oauth/access_token",
                JSONObject()
                    .put("client_id", state.value.clientId)
                    .put("grant_type", "refresh_token")
                    .put("refresh_token", data.getString("refresh_token")),
            )
        oauthError(result)
        require(
            api.user(result.getString("access_token")).getLong("id") == prefs.getLong("userId", -1)
        ) {
            "GitHub 身份发生变化，请退出后重新登录"
        }
        saveToken(result)
        return result.getString("access_token")
    }

    private suspend fun repo(token: String, create: Boolean = false): GitHubRepo {
        val r =
            api.repository(
                token,
                state.value.login,
                prefs.getLong("userId", -1),
                state.value.repository,
                create,
            )
        if (!create)
            require(
                r.id == prefs.getLong("repoId", -1) && r.branch == prefs.getString("branch", "")
            ) {
                "仓库或分支发生变化，请重新连接"
            }
        return r
    }

    fun connect() =
        run("正在检查或创建私有仓库…") {
            val token = token()
            val r = repo(token, create = true)
            val snapshot = api.snapshot(token, state.value.login, r)
            persist {
                if (
                    r.id != prefs.getLong("repoId", -1) || r.branch != prefs.getString("branch", "")
                ) {
                    remove("baseline")
                    remove("lastSync")
                }
                putLong("repoId", r.id)
                putString("branch", r.branch)
            }
            change {
                it.copy(
                    connected = true,
                    lastSync = prefs.getLong("lastSync", 0),
                    message =
                        if (snapshot.files.isEmpty()) "私有仓库已就绪，可以预览上传本机资料"
                        else "已连接私有仓库；首次使用此设备请先下载云端资料",
                )
            }
        }

    fun previewUpload() =
        run("正在校验云端版本并生成上传预览…") {
            require(state.value.connected) { "请先连接私有仓库" }
            val files =
                withContext(Dispatchers.IO) {
                    SnapshotFiles.unzip(Transfer.backup(graph, state.value.includeBackground))
                }
            val token = token()
            val remote = api.snapshot(token, state.value.login, repo(token))
            require(remote.files.isEmpty() || remote.head == prefs.getString("baseline", "")) {
                "云端有尚未合并的资料，请先下载确认后再上传"
            }
            val summary = withContext(Dispatchers.IO) { describe(files) }
            pendingUpload = remote to files
            change { it.copy(uploadPreview = summary, message = "已生成上传预览，尚未提交") }
        }

    fun publish() =
        run("正在上传完整快照…") {
            val (remote, files) = pendingUpload ?: error("请先预览上传内容")
            discardPreview()
            val token = token()
            require(repo(token) == remote.repo)
            val head =
                api.upload(
                    token,
                    state.value.login,
                    remote,
                    files,
                    prefs.getString("baseline", "").orEmpty(),
                )
            synced(head)
            change { it.copy(message = "本机资料已上传到私有仓库") }
        }

    fun download() =
        run("正在下载并校验完整快照…") {
            require(state.value.connected) { "请先连接私有仓库" }
            val token = token()
            val remote = api.snapshot(token, state.value.login, repo(token))
            require(remote.files.isNotEmpty()) { "云端还没有快照，请先上传本机资料" }
            val summary = withContext(Dispatchers.IO) { describe(remote.files) }
            pendingDownload = remote
            change {
                it.copy(downloadPreview = summary, message = "云端资料已校验，确认后才会合并到本机")
            }
        }

    fun restore() =
        run("正在保存恢复前备份并合并云端资料…") {
            val remote = pendingDownload ?: error("请先下载云端快照")
            discardPreview()
            val token = token()
            require(repo(token) == remote.repo)
            val latest = api.snapshot(token, state.value.login, remote.repo)
            require(latest.head == remote.head) { "云端在预览期间已更新，请重新下载" }
            val validated =
                withContext(Dispatchers.IO) {
                    val backup = Transfer.validateBackup(SnapshotFiles.zip(remote.files))
                    val bytes = Transfer.backup(graph)
                    // AtomicFile retains the previous recovery file if disk write fails.
                    val file = android.util.AtomicFile(recovery)
                    val stream = file.startWrite()
                    try {
                        stream.write(bytes)
                        file.finishWrite(stream)
                    } catch (e: Exception) {
                        file.failWrite(stream)
                        throw e
                    }
                    backup
                }
            change { it.copy(hasRecovery = true) }
            restoreBackup(graph, validated)
            synced(remote.head)
            change { it.copy(message = "云端资料已合并；同 ID 项采用云端版本。原资料已备份，可导出恢复") }
        }

    private suspend fun synced(head: String) {
        val now = System.currentTimeMillis()
        persist {
            putString("baseline", head)
            putLong("lastSync", now)
        }
        change { it.copy(lastSync = now) }
    }

    private fun describe(files: Map<String, ByteArray>): String {
        val rows = Transfer.validateBackup(SnapshotFiles.zip(files)).first
        val grouped = rows.groupingBy { it.first }.eachCount()
        return "主题 ${grouped["style"] ?: 0} 项 · 已存歌词 ${grouped["saved"] ?: 0} 首\n歌词数据 ${grouped["lyrics"] ?: 0} 项 · 绑定 ${grouped["binding"] ?: 0} 项\n显示设置、逐曲来源与时间偏移\n共 ${files.values.sumOf { it.size } / 1024 + 1} KB；${if (JSONObject(String(files.getValue("settings.json"), Charsets.UTF_8)).has("backgroundImage")) "包含自定义背景" else "不含自定义背景"}"
    }

    fun discardPreview() {
        pendingUpload = null
        pendingDownload = null
        change { it.copy(uploadPreview = "", downloadPreview = "") }
    }

    suspend fun recoveryBytes(): ByteArray =
        withContext(Dispatchers.IO) {
            recovery.inputStream().use { it.readBytesLimited(20_000_000) }
        }

    fun signOut() =
        run("正在退出 GitHub 登录…") {
            withContext(Dispatchers.IO) { secrets.write("") }
            persist {
                remove("login")
                remove("userId")
                remove("repoId")
                remove("branch")
                remove("baseline")
                remove("lastSync")
            }
            discardPreview()
            change {
                it.copy(
                    login = "",
                    connected = false,
                    userCode = "",
                    lastSync = 0,
                    failed = false,
                    message = "已退出本机 GitHub 登录；本地资料和远端仓库保留。可在 GitHub 撤销应用授权",
                )
            }
        }
}
