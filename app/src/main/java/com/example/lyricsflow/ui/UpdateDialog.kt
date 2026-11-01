package com.example.lyricsflow.ui

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.example.lyricsflow.BuildConfig
import com.example.lyricsflow.data.*
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun UpdateHost(updates: AppUpdates, dismiss: () -> Unit) {
    val state by updates.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var autoInstall by rememberSaveable { mutableStateOf(true) }
    var error by rememberSaveable { mutableStateOf("") }
    var opening by remember { mutableStateOf(false) }
    val installer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // Cancellation is reversible: keep the verified APK available for another attempt.
        opening = false
    }
    fun install() {
        scope.launch {
            opening = true
            try {
                installer.launch(updates.installerIntent())
                error = ""
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                opening = false
                error = e.message ?: "无法打开系统安装界面，请重试"
            }
        }
    }
    fun allowed() = Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (allowed()) install()
        else error = "尚未允许安装应用。点击“继续安装”可重新授权。"
    }
    fun requestInstall() {
        autoInstall = false
        error = ""
        if (allowed()) install()
        else if (Build.VERSION.SDK_INT >= 26) runCatching {
            permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri()))
        }.onFailure { error = "无法打开安装授权页面，请在系统设置中允许 LyricsFlow 安装应用" }
    }
    LaunchedEffect(state.stage) {
        if (state.stage == UpdateStage.READY && autoInstall) requestInstall()
    }
    UpdateDialog(
        state,
        error,
        opening,
        check = {
            autoInstall = true
            error = ""
            updates.check()
        },
        install = { requestInstall() },
        dismiss = {
            updates.cancel()
            dismiss()
        },
    )
}

@Composable
internal fun UpdateDialog(
    state: UpdateState,
    error: String = "",
    opening: Boolean = false,
    check: () -> Unit,
    install: () -> Unit,
    dismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(when (state.stage) {
            UpdateStage.DOWNLOADING -> "正在下载 v${state.version}"
            UpdateStage.READY -> "安装 v${state.version}"
            else -> "检查更新"
        }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("当前版本 v${BuildConfig.VERSION_NAME}")
                when (state.stage) {
                    UpdateStage.CHECKING -> {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("正在检查 GitHub 上的最新正式版…")
                    }
                    UpdateStage.DOWNLOADING -> {
                        val progress = if (state.total > 0) (state.downloaded.toFloat() / state.total).coerceIn(0f, 1f) else 0f
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        Text("${(progress * 100).toInt()}% · ${megabytes(state.downloaded)} / ${megabytes(state.total)} MB")
                        Text("下载完成并校验后将打开系统安装界面。首次使用需允许安装应用。")
                    }
                    UpdateStage.READY -> Text("安装包已下载并通过校验。请在系统安装界面确认更新；取消后可继续安装。")
                    UpdateStage.LATEST -> Text(state.message)
                    UpdateStage.ERROR -> Text(state.message, color = MaterialTheme.colorScheme.error)
                    UpdateStage.IDLE -> Text("从官方 GitHub Releases 获取正式版本。")
                }
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            when (state.stage) {
                UpdateStage.READY -> FlowButton(onClick = install, enabled = !opening) { Text("继续安装") }
                UpdateStage.ERROR, UpdateStage.IDLE -> FlowButton(onClick = check) { Text("重新检查") }
                UpdateStage.LATEST -> FlowButton(onClick = dismiss) { Text("知道了") }
                else -> Unit
            }
        },
        dismissButton = {
            if (state.stage != UpdateStage.LATEST) FlowOutlinedButton(onClick = dismiss) {
                Text(if (state.busy) "取消" else "关闭")
            }
        },
    )
}

private fun megabytes(bytes: Long) = String.format(Locale.getDefault(), "%.1f", bytes / 1_000_000.0)
