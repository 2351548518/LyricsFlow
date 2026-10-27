@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.lyricsflow.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.lyricsflow.core.*
import com.example.lyricsflow.data.*
import com.example.lyricsflow.overlay.LyricsOverlayService
import kotlinx.coroutines.*
import org.json.JSONObject

@Composable
fun FlowApp(graph: AppGraph) {
    val s by graph.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dark =
        when (s.prefs.dark) {
            "dark" -> true
            "light" -> false
            else -> isSystemInDarkTheme()
        }
    val accents = listOf(Color(0xff596b58), Color(0xffa0715c), Color(0xff7b7892), Color(0xff507981))
    val accent = if (dark) Color(0xffb8c5a6) else accents[s.prefs.palette.coerceIn(0, 3)]
    val bg =
        if (dark) Color(0xff222a25)
        else
            listOf(Color(0xffeeeae4), Color(0xfff0e8df), Color(0xffebe9ee), Color(0xffe7eceb))[
                s.prefs.palette.coerceIn(0, 3)]
    val scheme =
        if (dark)
            darkColorScheme(
                primary = accent,
                secondary = accent,
                secondaryContainer = accent.copy(alpha = .15f),
                background = bg,
                surface = bg,
                onSurface = Color(0xffe2e6dc),
                onBackground = Color(0xffe2e6dc),
                onPrimary = bg,
                surfaceVariant = Color(0xff303c33),
            )
        else
            lightColorScheme(
                primary = accent,
                secondary = accent,
                secondaryContainer = accent.copy(alpha = .15f),
                background = bg,
                surface = bg,
                onSurface = Color(0xff303b33),
                onBackground = Color(0xff303b33),
                onPrimary = Color.White,
                surfaceVariant = Color(0xffe4e2d9),
            )
    var page by rememberSaveable { mutableStateOf("home") }
    var sheet by rememberSaveable { mutableStateOf("") }
    var pendingImport by remember { mutableStateOf("lyrics") }
    var pendingBytes by remember { mutableStateOf(ByteArray(0)) }
    var pendingStyle by remember { mutableStateOf<StyleSpec?>(null) }
    var pendingBackup by remember {
        mutableStateOf<Pair<List<Triple<String, String, String>>, JSONObject>?>(null)
    }
    val style =
        remember(s.styleRaw) {
            s.styleRaw
                .takeIf { it.isNotBlank() }
                ?.let { runCatching { StyleCodec.parse(it) }.getOrNull() }
        }
    var commentsTrackData by rememberSaveable { mutableStateOf("") }
    val commentsTrack =
        remember(commentsTrackData) {
            commentsTrackData.takeIf { it.isNotBlank() }?.let { readTrack(JSONObject(it)) }
        }
    val snack = remember { SnackbarHostState() }
    fun report(e: Throwable) {
        graph.message(e.message ?: "操作失败，请重试")
    }
    val export =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream")
        ) { uri ->
            if (uri != null)
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openOutputStream(uri, "wt")!!.use {
                                it.write(pendingBytes)
                            }
                        }
                        graph.message("文件已导出")
                    } catch (e: Exception) {
                        report(e)
                    }
                }
        }
    fun output(name: String, bytes: ByteArray) {
        pendingBytes = bytes
        export.launch(name)
    }
    val pick =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null)
                scope.launch {
                    try {
                        val bytes =
                            withContext(Dispatchers.IO) {
                                context.contentResolver.openInputStream(uri)!!.use {
                                    it.readBytesLimited(
                                        when (pendingImport) {
                                            "backup" -> 20_000_000
                                            "image" -> 10_000_000
                                            else -> 2_000_000
                                        }
                                    )
                                }
                            }
                        when (pendingImport) {
                            "image" -> {
                                withContext(Dispatchers.IO) {
                                    BackgroundFiles.importImage(context, bytes)
                                }
                                graph.preferences(s.prefs.copy(background = "custom"))
                                graph.backgroundChanged()
                                graph.message("背景图片已保存为应用私有副本")
                            }
                            "style" ->
                                pendingStyle = StyleCodec.parse(String(bytes, Charsets.UTF_8))
                            "backup" ->
                                pendingBackup =
                                    withContext(Dispatchers.IO) { Transfer.validateBackup(bytes) }
                            else -> {
                                val (track, l) =
                                    Transfer.importLyrics(
                                        String(bytes, Charsets.UTF_8).removePrefix("\uFEFF"),
                                        s.playback.track?.duration ?: 0,
                                    )
                                graph.installLyrics(track, l)
                            }
                        }
                    } catch (e: Exception) {
                        report(e)
                    }
                }
        }
    fun import(kind: String) {
        pendingImport = kind
        pick.launch(arrayOf("*/*"))
    }
    val notifications =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) graph.message("通知未授权，可在应用中关闭或解锁悬浮窗")
        }
    fun overlay() {
        if (s.overlay) {
            context.stopService(Intent(context, LyricsOverlayService::class.java))
            return
        }
        if (!Settings.canDrawOverlays(context)) {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}"),
                )
            )
            graph.message("授权后返回，再次点击开启悬浮窗")
            return
        }
        if (Build.VERSION.SDK_INT >= 33)
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        runCatching {
            androidx.core.content.ContextCompat.startForegroundService(
                context,
                Intent(context, LyricsOverlayService::class.java),
            )
        }
            .onFailure { report(it) }
    }
    LaunchedEffect(s.toast) {
        if (s.toast.isNotBlank()) {
            snack.showSnackbar(s.toast)
            graph.consumeToast(s.toast)
        }
    }
    fun back() {
        if (sheet.isNotEmpty()) sheet = ""
        else
            page =
                if (
                    page in
                        setOf(
                            "appearance",
                            "permissions",
                            "sources",
                            "translation",
                            "data",
                            "library",
                            "help",
                        )
                )
                    "my"
                else "home"
    }
    BackHandler(enabled = sheet.isNotEmpty() || page != "home") { back() }
    MaterialTheme(colorScheme = scheme) {
        Surface(Modifier.fillMaxSize(), color = bg) {
            Scaffold(
                containerColor = Color.Transparent,
                snackbarHost = { SnackbarHost(snack) },
                bottomBar = { if (page == "home" || page == "my") Dock(page) { page = it } },
                modifier = Modifier.safeDrawingPadding(),
            ) { pad ->
                Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 24.dp)) {
                    Header(
                        page,
                        when {
                            s.demo -> "预览模式"
                            s.browsing -> "手动浏览"
                            s.playback.playing -> "正在聆听"
                            else -> "让歌词，流动起来"
                        },
                        { back() },
                    ) {
                        sheet = "about"
                    }
                    when (page) {
                        "home" ->
                            Home(
                                s,
                                graph,
                                style,
                                { sheet = "search" },
                                { sheet = "source" },
                                {
                                    commentsTrackData =
                                        s.playback.track?.let { trackJson(it).toString() }.orEmpty()
                                    page = "comments"
                                },
                                { overlay() },
                                { sheet = "delay" },
                            )
                        "comments" ->
                            CommentsScreen(graph, commentsTrack, s.binding, { page = "home" })
                        "my" ->
                            MyScreen(
                                s,
                                { page = it },
                                {
                                    graph.preview()
                                    page = "home"
                                },
                                {
                                    graph.live()
                                    page = "home"
                                },
                            )
                        "library" ->
                            Library(graph) { t, l ->
                                graph.browseLocal(t, l)
                                page = "home"
                            }
                        "appearance" ->
                            Appearance(
                                s,
                                graph,
                                style,
                                { import("style") },
                                {
                                    output(
                                        "LyricsFlow-style.json",
                                        (style?.raw ?: StyleCodec.preset(s.prefs.free))
                                            .toByteArray(),
                                    )
                                },
                                { new ->
                                    graph.style(new.raw)
                                },
                                { import("image") },
                            )
                        "permissions" -> PermissionSettings(s, graph, { overlay() })
                        "translation" -> TranslationSettings(s, graph)
                        "sources" -> SourceSettings(s, graph) { sheet = "search" }
                        "data" ->
                            DataSettings(
                                graph,
                                { import("lyrics") },
                                { import("backup") },
                                {
                                    scope.launch {
                                        try {
                                            val b =
                                                withContext(Dispatchers.IO) {
                                                    Transfer.backup(graph)
                                                }
                                            output("LyricsFlow.lfbackup.zip", b)
                                        } catch (e: Exception) {
                                            report(e)
                                        }
                                    }
                                },
                                { asLrc ->
                                    val t = s.playback.track
                                    val l = s.lyrics
                                    if (t == null || l == null) graph.message("请先加载歌词")
                                    else
                                        output(
                                            if (asLrc) "LyricsFlow.lrc"
                                            else "LyricsFlow.lflyrics.json",
                                            (if (asLrc) Transfer.lrc(l)
                                                else Transfer.lyricPackage(t, l, s.delay))
                                                .toByteArray(),
                                        )
                                },
                            )
                        else -> HelpScreen(page)
                    }
                }
            }
        }
        when (sheet) {
            "search" ->
                SearchSheet(graph, s.playback.track, { sheet = "" }) { t ->
                    if (s.playback.track != null && !s.demo && !s.browsing) graph.bind(t)
                    else graph.browse(t)
                    sheet = ""
                }
            "source" ->
                AlertDialog(
                    onDismissRequest = { sheet = "" },
                    title = { Text("选择歌词来源") },
                    text = {
                        Column {
                            listOf(Provider.QQ, Provider.NETEASE, Provider.LRCLIB).forEach { p ->
                                TextButton(
                                    onClick = {
                                        graph.selectSource(p)
                                        sheet = ""
                                    }
                                ) {
                                    Text(p.label)
                                }
                            }
                            Text("QQ 搜索受限时可用 songmid 绑定。评论来源独立于歌词。", fontSize = 13.sp)
                        }
                    },
                    confirmButton = { TextButton(onClick = { sheet = "search" }) { Text("歌曲匹配") } },
                )
            "delay" ->
                AlertDialog(
                    onDismissRequest = { sheet = "" },
                    title = { Text("校准歌词时间") },
                    text = {
                        Column {
                            Text("${s.delay} ms")
                            Text("正值让歌词稍后出现；只保存到这首歌与当前来源。", fontSize = 13.sp)
                            Slider(
                                value = s.delay.toFloat(),
                                onValueChange = { graph.delay((it / 100).toLong() * 100) },
                                valueRange = -10000f..10000f,
                            )
                            Row {
                                TextButton(onClick = { graph.delay(s.delay - 100) }) {
                                    Text("−100 ms")
                                }
                                TextButton(onClick = { graph.delay(s.delay + 100) }) {
                                    Text("+100 ms")
                                }
                                TextButton(onClick = { graph.delay(0) }) { Text("归零") }
                            }
                        }
                    },
                    confirmButton = { TextButton(onClick = { sheet = "" }) { Text("完成") } },
                )
            "about" ->
                AlertDialog(
                    onDismissRequest = { sheet = "" },
                    title = { Text("LyricsFlow") },
                    text = {
                        Text(
                            "歌词随音乐流动，共鸣在字里行间。\n\n原生 Android · 1.0 开发版\nQQ / 网易云接口为非官方公开接口，可能变更。仅用于个人聆听辅助；不提供音乐下载、评论发布或平台账号登录。"
                        )
                    },
                    confirmButton = { TextButton(onClick = { sheet = "" }) { Text("知道了") } },
                )
        }
        pendingStyle?.let { p ->
            AlertDialog(
                onDismissRequest = { pendingStyle = null },
                title = { Text("预览主题 · ${p.name}") },
                text = {
                    val spec = remember(p) { ThemeSpec.from(p.raw) }
                    Column(
                        Modifier.background(spec.backgroundColor?.let { Color(it) } ?: bg)
                            .padding(16.dp)
                    ) {
                        Text(
                            "${p.mode} · ${p.size.toInt()} sp",
                            color = p.color?.let { Color(it) } ?: accent,
                        )
                        Text(
                            "Leave a little room\nfor the light",
                            fontSize = p.size.coerceAtMost(40f).sp,
                            color = p.color?.let { Color(it) } ?: accent,
                        )
                        Text(
                            "为光，留一点空隙",
                            color = spec.translation.color?.let { Color(it) } ?: accent,
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "声明式主题 · 原生排版与时间轴动画。悬浮窗采用单行组布局，独立应用目标覆盖。",
                            fontSize = 12.sp,
                            color = p.color?.let { Color(it) } ?: accent,
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            graph.style(p.raw)
                            pendingStyle = null
                        }
                    ) {
                        Text("应用主题")
                    }
                },
                dismissButton = { TextButton(onClick = { pendingStyle = null }) { Text("取消") } },
            )
        }
        pendingBackup?.let { backup ->
            AlertDialog(
                onDismissRequest = { pendingBackup = null },
                title = { Text("恢复本地备份？") },
                text = {
                    Text("已校验 ${backup.first.size} 项数据。合并本地歌词、绑定和主题；同 ID 覆盖。API Key、系统权限和悬浮窗位置不恢复。")
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        graph.store.restore(backup.first)
                                    }
                                    val o = backup.second
                                    val p = s.prefs
                                    o.optJSONObject("trackPreferences")?.let {
                                        graph.restoreTrackPreferences(it)
                                    }
                                    if (o.has("backgroundImage"))
                                        withContext(Dispatchers.IO) {
                                            BackgroundFiles.importImage(
                                                context,
                                                android.util.Base64.decode(
                                                    o.getString("backgroundImage"),
                                                    android.util.Base64.DEFAULT,
                                                ),
                                            )
                                        }
                                    graph.preferences(
                                        p.copy(
                                            translation =
                                                o.optBoolean("translation", p.translation),
                                            romanization =
                                                o.optBoolean("romanization", p.romanization),
                                            free = o.optBoolean("free", p.free),
                                            fontSize =
                                                o.optDouble("fontSize", 30.0)
                                                    .toFloat()
                                                    .coerceIn(10f, 96f),
                                            dark =
                                                o.optString("dark", "system").takeIf {
                                                    it in listOf("light", "dark", "system")
                                                } ?: "system",
                                            palette = o.optInt("palette", 0).coerceIn(0, 3),
                                            reduced = o.optBoolean("reduced", false),
                                            overlaySize =
                                                o.optDouble("overlaySize", 22.0)
                                                    .toFloat()
                                                    .coerceIn(12f, 48f),
                                            overlayAlpha =
                                                o.optDouble("overlayAlpha", .85)
                                                    .toFloat()
                                                    .coerceIn(.2f, 1f),
                                            overlayTranslation =
                                                o.optBoolean("overlayTranslation", true),
                                            autoTranslate = false,
                                            consent = false,
                                            background =
                                                o.optString("background", "color").takeIf {
                                                    it in listOf("color", "cover", "custom")
                                                } ?: "color",
                                        )
                                    )
                                    graph.backgroundChanged()
                                    graph.reloadStyles()
                                    graph.message("备份已恢复")
                                    pendingBackup = null
                                } catch (e: Exception) {
                                    report(e)
                                }
                            }
                        }
                    ) {
                        Text("确认恢复")
                    }
                },
                dismissButton = { TextButton(onClick = { pendingBackup = null }) { Text("取消") } },
            )
        }
    }
}

@Composable
private fun Header(page: String, status: String, back: () -> Unit, info: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (page != "home" && page != "my")
            IconButton(
                onClick = back,
                modifier = Modifier.semantics { contentDescription = "返回上一级" },
            ) {
                Glyph("back")
            }
        Glyph("logo", Modifier.size(24.dp))
        Spacer(Modifier.width(8.dp))
        Text("Lyrics", fontSize = 21.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.7).sp)
        Text("Flow", fontSize = 22.sp, fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic)
        Spacer(Modifier.weight(1f))
        if (page == "home")
            Text(
                status,
                Modifier.padding(end = 4.dp),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.primary,
            )
        IconButton(
            onClick = info,
            modifier = Modifier.size(40.dp).semantics { contentDescription = "关于 LyricsFlow" },
        ) {
            Glyph("info", Modifier.size(19.dp))
        }
    }
}

@Composable
private fun Dock(page: String, onSelect: (String) -> Unit) {
    Box(
        Modifier.fillMaxWidth().padding(bottom = 12.dp, top = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surface.copy(alpha = .96f),
            shadowElevation = 6.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = .07f)),
        ) {
            Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("home" to "首页", "my" to "我的").forEach { (id, label) ->
                    Row(
                        Modifier.clip(CircleShape)
                            .background(
                                if (page == id) MaterialTheme.colorScheme.primary.copy(alpha = .12f)
                                else Color.Transparent
                            )
                            .clickable { onSelect(id) }
                            .padding(horizontal = 22.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Glyph(if (id == "home") "home" else "user", Modifier.size(19.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            label,
                            fontSize = 14.sp,
                            fontWeight = if (page == id) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun Glyph(kind: String, modifier: Modifier = Modifier) {
    val color = LocalContentColor.current
    Canvas(modifier.size(22.dp)) {
        val k = size.width / 24
        fun line(a: Float, b: Float, c: Float, d: Float) =
            drawLine(color, Offset(a * k, b * k), Offset(c * k, d * k), 1.6f * k, StrokeCap.Round)
        fun path(vararg xy: Float) {
            val p = Path()
            p.moveTo(xy[0] * k, xy[1] * k)
            for (i in 2 until xy.size step 2) p.lineTo(xy[i] * k, xy[i + 1] * k)
            drawPath(
                p,
                color,
                style = Stroke(1.6f * k, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
        when (kind) {
            "logo" -> {
                listOf(4f to 12f, 9f to 6f, 14f to 3f, 19f to 9f).forEach { (x, y) ->
                    drawRoundRect(
                        color,
                        Offset(x * k, y * k),
                        Size(2.6f * k, (22 - y) * k),
                        CornerRadius(k),
                    )
                }
            }
            "home" ->
                path(
                    3f,
                    11f,
                    12f,
                    3f,
                    21f,
                    11f,
                    21f,
                    21f,
                    15f,
                    21f,
                    15f,
                    14f,
                    9f,
                    14f,
                    9f,
                    21f,
                    3f,
                    21f,
                    3f,
                    11f,
                )
            "user" -> {
                drawCircle(color, 4 * k, Offset(12 * k, 7 * k), style = Stroke(1.6f * k))
                drawArc(
                    color,
                    180f,
                    180f,
                    false,
                    Offset(4 * k, 13 * k),
                    Size(16 * k, 16 * k),
                    style = Stroke(1.6f * k),
                )
            }
            "back" -> {
                line(20f, 12f, 4f, 12f)
                path(10f, 6f, 4f, 12f, 10f, 18f)
            }
            "comment" -> {
                drawRoundRect(
                    color,
                    Offset(3 * k, 3 * k),
                    Size(18 * k, 15 * k),
                    CornerRadius(5 * k),
                    style = Stroke(1.6f * k),
                )
                path(4f, 15f, 3f, 22f, 9f, 18f)
                line(8f, 8f, 16f, 8f)
                line(8f, 12f, 13f, 12f)
            }
            "window" -> {
                drawRoundRect(
                    color,
                    Offset(3 * k, 4 * k),
                    Size(18 * k, 16 * k),
                    CornerRadius(3 * k),
                    style = Stroke(1.6f * k),
                )
                line(3f, 9f, 21f, 9f)
                drawCircle(color, k, Offset(7 * k, 6.5f * k))
            }
            "refresh" -> {
                drawArc(
                    color,
                    40f,
                    290f,
                    false,
                    Offset(4 * k, 4 * k),
                    Size(16 * k, 16 * k),
                    style = Stroke(1.6f * k),
                )
                path(21f, 4f, 21f, 10f, 15f, 10f)
            }
            "play" -> {
                val p =
                    Path().apply {
                        moveTo(8 * k, 4 * k)
                        lineTo(21 * k, 12 * k)
                        lineTo(8 * k, 20 * k)
                        close()
                    }
                drawPath(p, color)
            }
            "pause" -> {
                drawRect(color, Offset(7 * k, 4 * k), Size(3 * k, 16 * k))
                drawRect(color, Offset(14 * k, 4 * k), Size(3 * k, 16 * k))
            }
            "chevron" -> path(9f, 6f, 15f, 12f, 9f, 18f)
            "heart" -> {
                path(
                    12f,
                    21f,
                    3f,
                    12f,
                    3f,
                    6f,
                    7f,
                    3f,
                    12f,
                    7f,
                    17f,
                    3f,
                    21f,
                    6f,
                    21f,
                    12f,
                    12f,
                    21f,
                )
            }
            "check" -> path(4f, 12f, 9f, 17f, 20f, 6f)
            else -> {
                drawCircle(color, 9 * k, Offset(12 * k, 12 * k), style = Stroke(1.6f * k))
                line(12f, 11f, 12f, 17f)
                drawCircle(color, k, Offset(12 * k, 7 * k))
            }
        }
    }
}

@Composable
private fun Home(
    s: ScreenState,
    graph: AppGraph,
    style: StyleSpec?,
    search: () -> Unit,
    source: () -> Unit,
    comments: () -> Unit,
    overlay: () -> Unit,
    delay: () -> Unit,
) {
    val t = s.playback.track
    if (t == null) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            LandscapeCover(Modifier.size(220.dp).clip(RoundedCornerShape(28.dp)))
            Spacer(Modifier.height(32.dp))
            Text("留一点空隙，给音乐。", fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(14.dp))
            Text(
                "连接本机音乐，歌词会在这里流动。\n支持 Spotify、QQ 音乐、网易云等音乐 App。",
                textAlign = TextAlign.Center,
                fontSize = 14.sp,
                lineHeight = 24.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f),
            )
            Spacer(Modifier.height(24.dp))
            val context = LocalContext.current
            Button(
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
            ) {
                Text("授权读取播放状态")
            }
            TextButton(onClick = search) { Text("搜索歌曲与真实评论") }
            TextButton(onClick = { graph.preview() }) { Text("先看看预览 →") }
            Spacer(Modifier.height(20.dp))
        }
        return
    }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(s.playback.playing, s.prefs.reduced) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                now = SystemClock.elapsedRealtime()
                delay(if (s.playback.playing) 80 else 500)
            }
        }
    }
    val position = s.playback.at(now)
    val active = if (s.playback.positionKnown) s.lyrics?.active(position - s.delay) ?: -1 else -1
    LaunchedEffect(position) {
        if (s.demo && s.playback.playing && t.duration > 0 && position >= t.duration)
            graph.demoPlay()
    }
    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    t.title,
                    fontFamily = FontFamily.Serif,
                    fontSize = 31.sp,
                    lineHeight = 36.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    t.artist.ifBlank { "未知歌手" },
                    Modifier.padding(top = 9.dp),
                    fontSize = 11.sp,
                    letterSpacing = 1.5.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f),
                )
            }
            IconButton(
                onClick = { graph.saveCurrent() },
                enabled = s.lyrics != null,
                modifier = Modifier.semantics { contentDescription = "保存当前歌词" },
            ) {
                Glyph(if (s.saved) "check" else "heart")
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(top = 14.dp, bottom = 8.dp)) {
            val wide = maxWidth >= 700.dp
            val coverHeight = (maxHeight - 64.dp).coerceAtLeast(60.dp).coerceAtMost(360.dp)
            Row(
                Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(60.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(16.dp))) {
                    LyricBackground(s)
                    if (s.lyrics != null && s.lyrics.lines.isNotEmpty())
                        LyricStage(s, style, active, position - s.delay)
                    else
                        Column(
                            Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            if (s.busy)
                                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                            Text(
                                if (s.busy) "正在寻找这首歌的文字…"
                                else s.message.ifBlank { "暂无歌词，请搜索匹配或导入本地文件" },
                                Modifier.padding(24.dp),
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .5f),
                            )
                            OutlinedButton(onClick = search) { Text("歌曲匹配") }
                        }
                }
                if (wide)
                    Column(
                        Modifier.weight(.75f).fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        SongArt(
                            s,
                            Modifier.height(coverHeight)
                                .aspectRatio(.88f)
                                .clip(RoundedCornerShape(24.dp)),
                        )
                        Text(
                            t.album.ifBlank { "A moment, just for you" },
                            Modifier.padding(top = 12.dp),
                            fontFamily = FontFamily.Serif,
                            fontStyle = FontStyle.Italic,
                            fontSize = 17.sp,
                        )
                        Text(
                            "LYRICS, AT YOUR PACE",
                            Modifier.padding(top = 6.dp),
                            fontSize = 10.sp,
                            letterSpacing = 2.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = .4f),
                        )
                    }
            }
        }
        if (s.demo || s.browsing)
            Text(
                if (s.demo) "预览 · 自有示例 · 不播放音频" else "手动浏览 · 未播放音频",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.primary,
            )
        else
            Text(
                "播放来自 ${playbackAppName(s.playback.packageName)}",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.primary,
            )
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val compact = maxWidth < 380.dp
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                TextButton(onClick = source, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Box(
                        Modifier.size(5.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(s.source.label, fontSize = 12.sp)
                    Text("⌄", Modifier.padding(start = 4.dp))
                }
                IconButton(
                    onClick = { graph.refresh() },
                    enabled = !s.busy && !s.demo,
                    modifier = Modifier.size(44.dp).semantics { contentDescription = "刷新歌词" },
                ) {
                    Glyph("refresh", Modifier.size(18.dp))
                }
                IconButton(
                    onClick = {
                        graph.preferences(s.prefs.copy(translation = !s.prefs.translation))
                    },
                    modifier = Modifier.size(44.dp).semantics { contentDescription = "显示或隐藏译文" },
                ) {
                    Text(
                        "译",
                        fontSize = 16.sp,
                        color =
                            if (s.prefs.translation) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = .3f),
                    )
                }
                IconButton(
                    onClick = overlay,
                    modifier =
                        Modifier.size(44.dp).semantics {
                            contentDescription = if (s.overlay) "关闭悬浮窗" else "开启悬浮窗"
                        },
                ) {
                    Glyph("window", Modifier.size(19.dp))
                }
                Spacer(Modifier.weight(1f))
                FilledTonalButton(
                    onClick = comments,
                    modifier = Modifier.semantics { contentDescription = "歌曲评论 · 共鸣" },
                    contentPadding = PaddingValues(horizontal = if (compact) 10.dp else 14.dp),
                    colors =
                        ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                ) {
                    Glyph("comment", Modifier.size(17.dp))
                    if (!compact) {
                        Spacer(Modifier.width(6.dp))
                        Text("共鸣", fontSize = 12.sp, maxLines = 1)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = delay, contentPadding = PaddingValues(4.dp)) {
                Text(if (s.delay == 0L) "时间校准" else "${s.delay} ms", fontSize = 11.sp)
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = search, contentPadding = PaddingValues(4.dp)) {
                Text("歌曲匹配", fontSize = 11.sp)
            }
            if (s.demo || s.browsing)
                TextButton(onClick = { graph.live() }, contentPadding = PaddingValues(4.dp)) {
                    Text("返回实时", fontSize = 11.sp)
                }
        }
        if (s.demo) {
            Slider(
                value = position.toFloat().coerceIn(0f, t.duration.toFloat()),
                onValueChange = { graph.demoSeek(it.toLong()) },
                valueRange = 0f..t.duration.toFloat(),
                modifier = Modifier.height(22.dp).semantics { contentDescription = "预览时间轴" },
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(time(position), fontSize = 11.sp)
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = { graph.demoPlay() },
                    modifier =
                        Modifier.semantics {
                            contentDescription = if (s.playback.playing) "暂停预览" else "播放预览"
                        },
                ) {
                    Glyph(if (s.playback.playing) "pause" else "play", Modifier.size(19.dp))
                }
                Spacer(Modifier.weight(1f))
                Text(time(t.duration), fontSize = 11.sp)
            }
        } else {
            LinearProgressIndicator(
                progress = {
                    if (t.duration > 0) (position.toFloat() / t.duration).coerceIn(0f, 1f) else 0f
                },
                modifier = Modifier.fillMaxWidth().height(2.dp),
            )
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp)) {
                Text(
                    if (s.browsing) "浏览歌词" else if (s.playback.playing) "正在播放" else "已暂停",
                    fontSize = 10.sp,
                )
                Spacer(Modifier.weight(1f))
                Text("${time(position)} / ${time(t.duration)}", fontSize = 10.sp)
            }
        }
    }
}

private fun time(ms: Long) =
    "%02d:%02d".format(ms.coerceAtLeast(0) / 60000, ms.coerceAtLeast(0) / 1000 % 60)

@Composable
private fun LyricStage(s: ScreenState, style: StyleSpec?, active: Int, position: Long) {
    val lines = s.lyrics!!.lines
    val list = rememberLazyListState()
    var manualUntil by remember { mutableLongStateOf(0) }
    val scope = rememberCoroutineScope()
    val dragged by list.interactionSource.collectIsDraggedAsState()
    val theme = remember(style?.raw) { style?.raw?.let { ThemeSpec.from(it) } }
    val color = style?.color?.let { Color(it) } ?: MaterialTheme.colorScheme.onSurface
    val align =
        when (style?.alignment) {
            "center" -> TextAlign.Center
            "end" -> TextAlign.End
            else -> TextAlign.Start
        }
    fun rowText(row: Line, current: Boolean): AnnotatedString = buildAnnotatedString {
        if (current && s.playback.positionKnown && row.words.isNotEmpty()) {
            row.words.forEach { w ->
                withStyle(
                    SpanStyle(color = if (position >= w.start) color else color.copy(alpha = .3f))
                ) {
                    append(w.text)
                }
            }
        } else append(row.text)
    }
    @Composable
    fun group(
        index: Int,
        modifier: Modifier = Modifier,
        partitionHeight: Float? = null,
        vertical: Boolean = false,
    ) {
        val row = lines[index]
        val current =
            index == active ||
                row.start == null ||
                !s.playback.positionKnown ||
                active >= 0 && row.start == lines[active].start
        val motion =
            if (current && s.playback.positionKnown && !s.prefs.reduced)
                theme?.motion(
                    (position - (row.start ?: position)).coerceAtLeast(0),
                    (row.end ?: position) - (row.start ?: 0),
                ) ?: Motion()
            else Motion()
        val font =
            when (theme?.font) {
                "serif" -> FontFamily.Serif
                "monospace" -> FontFamily.Monospace
                else -> FontFamily.SansSerif
            }
        val textSize =
            if (partitionHeight != null)
                minOf(s.prefs.fontSize, ((partitionHeight - 36f) / 2.5f).coerceAtLeast(10f))
            else s.prefs.fontSize
        Column(
            modifier
                .fillMaxWidth()
                .graphicsLayer {
                    alpha = (if (current) 1f else theme?.inactive ?: .32f) * motion.opacity
                    scaleX = motion.scale
                    scaleY = motion.scale
                    rotationZ = motion.rotation
                    translationX = motion.x * size.width
                    translationY = motion.y * size.height
                }
                .padding(
                    horizontal = (theme?.padding ?: 0f).dp,
                    vertical = (if (partitionHeight != null) 4f else (theme?.gap ?: 28f) / 2).dp,
                )
        ) {
            if (vertical && partitionHeight != null) {
                val charsPerColumn =
                    ((partitionHeight - 36f) / (textSize * 1.12f)).toInt().coerceIn(2, 20)
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    graphemes(row.text).chunked(charsPerColumn).take(10).forEach { chars ->
                        Text(
                            chars.joinToString("\n"),
                            fontSize = textSize.sp,
                            lineHeight = (textSize * 1.12f).sp,
                            fontWeight = FontWeight.Bold,
                            color = color,
                        )
                    }
                }
            } else
                Text(
                    rowText(row, current),
                    fontSize = textSize.sp,
                    lineHeight = (textSize * 1.22f).sp,
                    fontFamily = font,
                    fontWeight = FontWeight(theme?.original?.weight ?: if (current) 700 else 600),
                    color = color,
                    textAlign = align,
                    maxLines = if (partitionHeight != null) 2 else Int.MAX_VALUE,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            if (s.prefs.translation && row.translation.isNotBlank())
                Text(
                    row.translation,
                    Modifier.fillMaxWidth()
                        .padding(top = if (partitionHeight != null) 4.dp else 10.dp),
                    fontSize =
                        (if (partitionHeight != null) (textSize * .5f).coerceAtLeast(10f)
                            else
                                theme?.translation?.size
                                    ?: (s.prefs.fontSize * .48f).coerceAtLeast(13f))
                            .sp,
                    lineHeight = if (partitionHeight != null) 14.sp else 22.sp,
                    maxLines = if (partitionHeight != null) 1 else Int.MAX_VALUE,
                    overflow = TextOverflow.Ellipsis,
                    fontFamily = font,
                    color =
                        theme?.translation?.color?.let { Color(it) } ?: color.copy(alpha = .64f),
                    textAlign = align,
                )
            if (s.prefs.romanization && row.romanization.isNotBlank())
                Text(
                    row.romanization,
                    Modifier.fillMaxWidth().padding(top = 5.dp),
                    fontSize = (theme?.romanization?.size ?: 13f).sp,
                    fontFamily = font,
                    color =
                        theme?.romanization?.color?.let { Color(it) } ?: color.copy(alpha = .6f),
                    textAlign = align,
                )
        }
    }
    if (s.prefs.free) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(vertical = 10.dp)) {
            val count = minOf(theme?.maxGroups ?: 3, lines.size)
            val first = (active - count / 2).coerceIn(0, (lines.size - count).coerceAtLeast(0))
            val partition = maxHeight.value / count
            Column(Modifier.fillMaxSize()) {
                (first until first + count).forEach { index ->
                    key(index) {
                        val seed =
                            (s.playback.track?.key.orEmpty() + index + style?.name).hashCode()
                        val cjk = lines[index].text.any { it in '\u3040'..'\u9fff' }
                        val vertical =
                            cjk &&
                                (theme?.vertical == "vertical" ||
                                    theme?.vertical == "mixed" && seed % 2 == 0)
                        Box(
                            Modifier.weight(1f).fillMaxWidth().clipToBounds(),
                            contentAlignment =
                                if (index % 2 == 0) Alignment.CenterStart else Alignment.CenterEnd,
                        ) {
                            val rotation = if (s.prefs.reduced) 0f else (seed % 5).toFloat()
                            group(
                                index,
                                Modifier.rotate(rotation)
                                    .padding(start = if (index % 2 == 0) 0.dp else 24.dp),
                                partition,
                                vertical,
                            )
                        }
                    }
                }
            }
        }
    } else
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val top = maxHeight * (theme?.anchor ?: .26f)
            LaunchedEffect(active) {
                if (
                    active >= 0 &&
                        SystemClock.elapsedRealtime() > manualUntil &&
                        !list.isScrollInProgress
                ) {
                    if (s.prefs.reduced) list.scrollToItem(active)
                    else list.animateScrollToItem(active)
                }
            }
            LaunchedEffect(dragged) {
                if (dragged) manualUntil = SystemClock.elapsedRealtime() + 5000
            }
            LaunchedEffect(manualUntil) {
                if (manualUntil > 0) {
                    delay(5100)
                    if (!list.isScrollInProgress && active >= 0) list.animateScrollToItem(active)
                    manualUntil = 0
                }
            }
            LazyColumn(
                state = list,
                contentPadding = PaddingValues(top = top, bottom = maxHeight * .55f),
                modifier = Modifier.fillMaxSize().semantics { contentDescription = "同步歌词" },
            ) {
                itemsIndexed(lines) { i, _ -> group(i) }
            }
            if (manualUntil > SystemClock.elapsedRealtime())
                TextButton(
                    onClick = {
                        manualUntil = 0
                        scope.launch { list.animateScrollToItem(active.coerceAtLeast(0)) }
                    },
                    modifier = Modifier.align(Alignment.BottomEnd),
                ) {
                    Text("回到当前歌词")
                }
        }
}

@Composable
fun LandscapeCover(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawRect(
            Brush.verticalGradient(listOf(Color(0xffb6b9a7), Color(0xffdcceb7), Color(0xff9eac94)))
        )
        drawCircle(Color(0xfff5dfb6).copy(alpha = .8f), w * .12f, Offset(w * .68f, h * .29f))
        fun hill(y: Float, color: Color, phase: Float) {
            val p =
                Path().apply {
                    moveTo(-w * .1f, h * y)
                    cubicTo(w * .18f, h * (y - phase), w * .38f, h * (y + .05f), w * .56f, h * y)
                    cubicTo(
                        w * .8f,
                        h * (y + .1f),
                        w * .9f,
                        h * (y - .08f),
                        w * 1.1f,
                        h * (y - .03f),
                    )
                    lineTo(w * 1.1f, h)
                    lineTo(-w * .1f, h)
                    close()
                }
            drawPath(p, color)
        }
        hill(.60f, Color(0xffa5ae94), .3f)
        hill(.71f, Color(0xff82967f), .22f)
        hill(.83f, Color(0xff5e7867), .2f)
        hill(.96f, Color(0xff39534a), .22f)
        drawOval(
            Color(0xffe1dcc8).copy(alpha = .25f),
            Offset(w * .03f, h * .65f),
            Size(w * .86f, h * .045f),
        )
        val p =
            Path().apply {
                moveTo(w * .19f, h * 1.05f)
                quadraticTo(w * .2f, h * .73f, w * .165f, h * .47f)
            }
        drawPath(p, Color(0xff293f37), style = Stroke(w * .007f))
        listOf(
                floatArrayOf(.18f, .81f, .04f, .70f),
                floatArrayOf(.18f, .73f, .32f, .64f),
                floatArrayOf(.17f, .64f, .07f, .55f),
                floatArrayOf(.17f, .58f, .27f, .49f),
                floatArrayOf(.165f, .52f, .12f, .47f),
            )
            .forEach { a ->
                drawLine(
                    Color(0xff293f37),
                    Offset(w * a[0], h * a[1]),
                    Offset(w * a[2], h * a[3]),
                    w * .004f,
                    StrokeCap.Round,
                )
            }
        drawLine(
            Color(0xff3f5145),
            Offset(w * .50f, h * .36f),
            Offset(w * .52f, h * .35f),
            w * .004f,
        )
        drawLine(
            Color(0xff3f5145),
            Offset(w * .52f, h * .35f),
            Offset(w * .54f, h * .36f),
            w * .004f,
        )
    }
}
