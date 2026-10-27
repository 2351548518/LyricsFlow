package com.example.lyricsflow.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.example.lyricsflow.data.GitHubSync
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

@Composable
private fun SyncCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        Modifier.fillMaxWidth().padding(bottom = 14.dp),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
fun GitHubSettings(sync: GitHubSync, export: (String, ByteArray) -> Unit) {
    val s by sync.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var client by rememberSaveable(s.clientId) { mutableStateOf(s.clientId) }
    var repository by rememberSaveable(s.repository) { mutableStateOf(s.repository) }
    var localError by remember { mutableStateOf("") }
    var instructions by rememberSaveable { mutableStateOf(false) }
    var signingOut by remember { mutableStateOf(false) }
    fun open(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (_: Exception) {
            localError = "没有可用浏览器，请安装浏览器后重试"
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        PageTitle("GitHub 同步", "让你的主题与歌词，在设备之间流动。手动同步到个人私有仓库。")
        SyncCard(if (s.login.isBlank()) "连接 GitHub" else "已登录 @${s.login}") {
            if (s.login.isBlank()) {
                OutlinedTextField(
                    client,
                    { client = it },
                    Modifier.fillMaxWidth(),
                    label = { Text("OAuth App Client ID") },
                    singleLine = true,
                    enabled = !s.busy,
                )
                Text(
                    "这里只填写公开 Client ID。无需 Client Secret，也不会在 App 中索取 GitHub 密码。",
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowOutlinedButton(
                    onClick = { instructions = !instructions },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (instructions) "收起注册指引" else "还没有 Client ID？查看注册指引")
                }
                if (instructions) {
                    Text(
                        "1. 打开 GitHub → Settings → Developer settings → OAuth Apps → New OAuth App。\n2. 名称填写 LyricsFlow；Homepage URL 填你的项目或个人主页。\n3. Callback URL 可填写同一主页的 HTTPS 地址。本方案使用 Device Flow，不会调用这个回调。\n4. 勾选 Enable Device Flow；允许保留默认的到期 Token 设置。\n5. 注册后复制 Client ID 到上方，保存并登录。不要复制 Client Secret。",
                        fontSize = 13.sp,
                        lineHeight = 22.sp,
                    )
                    FlowOutlinedButton(
                        onClick = { open("https://github.com/settings/applications/new") },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("打开 GitHub 注册页面")
                    }
                }
                Text(
                    "授权将申请 repo 权限，可访问你有权限的公开和私有仓库，权限并非只限此同步仓库。LyricsFlow 仅操作你选择的个人私有仓库中的 lyricsflow 目录。",
                    fontSize = 13.sp,
                    lineHeight = 21.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowButton(
                    onClick = {
                        runCatching {
                            sync.configure(client, repository)
                            sync.signIn()
                            localError = ""
                        }
                            .onFailure { localError = it.message.orEmpty() }
                    },
                    enabled = !s.busy && client.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("保存并登录 GitHub")
                }
            } else {
                Text(
                    "凭证通过 Android Keystore 加密，仅保存在本机。",
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowOutlinedButton(
                    onClick = { signingOut = true },
                    enabled = !s.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("退出 GitHub 登录")
                }
                TextButton(
                    onClick = {
                        open("https://github.com/settings/connections/applications/${s.clientId}")
                    },
                    enabled = !s.busy,
                ) {
                    Text("查看或撤销 GitHub 授权")
                }
            }
            if (s.userCode.isNotBlank()) {
                Text("设备验证码", fontSize = 13.sp)
                SelectionContainer {
                    Text(
                        s.userCode,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 3.sp,
                    )
                }
                FlowButton(
                    onClick = {
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText("GitHub 设备验证码", s.userCode))
                        open("https://github.com/login/device")
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("复制验证码并打开 GitHub 授权")
                }
                Text("授权后返回 App 等待结果。验证码过期或取消后，重新登录即可。", fontSize = 13.sp, lineHeight = 20.sp)
                FlowOutlinedButton(
                    onClick = sync::cancelSignIn,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("取消本次登录")
                }
            }
        }
        Surface(
            color =
                if (s.failed || localError.isNotBlank()) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor =
                if (s.failed || localError.isNotBlank()) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (s.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(localError.ifBlank { s.message }, fontSize = 14.sp, lineHeight = 22.sp)
                if (s.lastSync > 0)
                    Text(
                        "上次成功：${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(s.lastSync))}",
                        fontSize = 12.sp,
                    )
            }
        }
        if (s.login.isNotBlank()) {
            SyncCard("个人私有仓库") {
                OutlinedTextField(
                    repository,
                    {
                        repository = it
                        sync.repository(it)
                    },
                    Modifier.fillMaxWidth(),
                    label = { Text("仓库名称") },
                    supportingText = { Text("${s.login}/$repository · 不存在时创建为私有") },
                    singleLine = true,
                    enabled = !s.busy,
                )
                FlowButton(
                    onClick = sync::connect,
                    enabled = !s.busy && repository.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (s.connected) "重新检查私有仓库" else "连接 / 创建私有仓库")
                }
                if (s.connected)
                    Text("✓ 已连接私有仓库", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            SyncCard("同步资料") {
                Text(
                    "包含主题文件、显示设置、逐曲来源、时间偏移、本地歌词及译文（包括已缓存的歌词数据）。评论缓存、播放历史、API Key、GitHub Token、翻译服务地址与授权状态不上传。",
                    fontSize = 13.sp,
                    lineHeight = 22.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Toggle("同时上传自定义背景", s.includeBackground, "默认关闭；仓库中的资料以普通文件保存，并非端到端加密") {
                    sync.includeBackground(it)
                }
                FlowButton(
                    onClick = sync::previewUpload,
                    enabled = s.connected && !s.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("预览并上传本机资料")
                }
                FlowOutlinedButton(
                    onClick = sync::download,
                    enabled = s.connected && !s.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("下载云端资料并预览")
                }
                Text(
                    "新设备先下载合并，再上传。相同 ID 采用云端版本，本地独有项保留；恢复前自动保存一份本机备份。当前为手动快照同步，不同步删除操作。",
                    fontSize = 13.sp,
                    lineHeight = 22.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = { open("https://github.com/${s.login}/${s.repository}") },
                    enabled = s.connected && !s.busy,
                ) {
                    Text("在浏览器查看仓库")
                }
            }
        }
        if (s.hasRecovery)
            SyncCard("恢复前的安全副本") {
                Text("保留最近一次云端恢复前的本机资料。导出后可通过“导入、导出与备份”恢复。", fontSize = 13.sp, lineHeight = 21.sp)
                FlowOutlinedButton(
                    onClick = {
                        scope.launch {
                            runCatching {
                                export(
                                    "LyricsFlow-before-GitHub.lfbackup.zip",
                                    sync.recoveryBytes(),
                                )
                            }
                                .onFailure { localError = "读取恢复前备份失败" }
                        }
                    },
                    enabled = !s.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("导出恢复前备份")
                }
            }
    }
    if (s.uploadPreview.isNotBlank())
        AlertDialog(
            onDismissRequest = sync::discardPreview,
            title = { Text("上传到私有仓库？") },
            text = {
                Text(
                    "${s.login}/${s.repository}\n\n${s.uploadPreview}\n\n仅更新 lyricsflow 目录，Git 历史保留旧版本。请确认这些资料可以保存在 GitHub。"
                )
            },
            confirmButton = { TextButton(onClick = sync::publish) { Text("确认上传") } },
            dismissButton = { TextButton(onClick = sync::discardPreview) { Text("取消") } },
        )
    if (s.downloadPreview.isNotBlank())
        AlertDialog(
            onDismissRequest = sync::discardPreview,
            title = { Text("合并云端资料？") },
            text = {
                Text(
                    "${s.downloadPreview}\n\n相同 ID 的歌词、绑定和主题会被云端版本覆盖，本地独有项保留。显示设置按云端恢复；自动翻译和付费授权会关闭。先保存恢复前备份，再应用。"
                )
            },
            confirmButton = { TextButton(onClick = sync::restore) { Text("备份本机并合并") } },
            dismissButton = { TextButton(onClick = sync::discardPreview) { Text("取消") } },
        )
    if (signingOut)
        AlertDialog(
            onDismissRequest = { signingOut = false },
            title = { Text("退出 GitHub？") },
            text = { Text("删除本机登录凭证；本地资料、恢复副本和远端仓库保留。若要撤销 GitHub 端的授权，请使用“查看或撤销 GitHub 授权”。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        sync.signOut()
                        signingOut = false
                    }
                ) {
                    Text("退出登录")
                }
            },
            dismissButton = { TextButton(onClick = { signingOut = false }) { Text("取消") } },
        )
}
