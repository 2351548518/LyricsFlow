@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.lyricsflow.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.*
import com.example.lyricsflow.core.*
import com.example.lyricsflow.data.*
import com.example.lyricsflow.overlay.LyricsOverlayService
import java.util.*
import kotlin.math.roundToInt
import kotlinx.coroutines.*
import org.json.JSONObject

@Composable
fun PageTitle(title: String, subtitle: String = "") {
    Text(
        title,
        Modifier.padding(top = 16.dp, bottom = 8.dp),
        fontSize = 25.sp,
        fontWeight = FontWeight.SemiBold,
    )
    if (subtitle.isNotBlank())
        Text(
            subtitle,
            Modifier.padding(bottom = 22.dp),
            fontSize = 14.sp,
            lineHeight = 21.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
}

@Composable
private fun Section(text: String) {
    Text(
        text,
        Modifier.padding(top = 26.dp, bottom = 12.dp),
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
    )
}

@Composable
private fun SettingRow(title: String, sub: String = "", onClick: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().padding(bottom = 8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            Modifier.fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 17.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                if (sub.isNotBlank())
                    Text(
                        sub,
                        Modifier.padding(top = 6.dp),
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
            }
            Spacer(Modifier.width(12.dp))
            Glyph("chevron", Modifier.size(18.dp))
        }
    }
}

@Composable
internal fun Toggle(
    title: String,
    checked: Boolean,
    sub: String = "",
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .semantics(mergeDescendants = true) { stateDescription = if (checked) "已开启" else "已关闭" }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontSize = 15.sp)
            if (sub.isNotBlank())
                Text(
                    sub,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 19.sp,
                )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Switch(
                checked = checked,
                onCheckedChange = null,
                thumbContent = { Glyph(if (checked) "check" else "minus", Modifier.size(12.dp)) },
                colors =
                    SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        checkedBorderColor = MaterialTheme.colorScheme.primary,
                        checkedIconColor = MaterialTheme.colorScheme.primary,
                        uncheckedThumbColor = MaterialTheme.colorScheme.onSurface,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        uncheckedBorderColor = MaterialTheme.colorScheme.outline,
                        uncheckedIconColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
            )
            Text(
                if (checked) "开" else "关",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Choice(labels: List<String>, selected: Int, change: (Int) -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        labels.forEachIndexed { i, l ->
            FlowChoiceChip(l, selected == i) { change(i) }
        }
    }
}

@Composable
fun MyScreen(
    s: ScreenState,
    navigate: (String) -> Unit,
    preview: () -> Unit,
    live: () -> Unit,
    githubLogin: String = "",
    githubLastSync: Long = 0,
) {
    val syncLabel =
        remember(githubLogin, githubLastSync) {
            when {
                githubLogin.isBlank() -> "数据保存在本地"
                githubLastSync <= 0 -> "上次同步：尚未同步"
                else ->
                    "上次同步：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(githubLastSync))}"
            }
        }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 16.dp)) {
        item {
            Surface(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border =
                    androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant,
                    ),
            ) {
                Column(Modifier.padding(22.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ) {
                            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                                GitHubAvatar(githubLogin)
                            }
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (githubLogin.isBlank()) "本地聆听者" else "@$githubLogin",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                            Text(
                                syncLabel,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 5.dp),
                            )
                        }
                    }
                }
            }
        }
        item { Section("聆听与表达") }
        item { SettingRow("本地歌词库", "保存的歌词，随时重读") { navigate("library") } }
        item { SettingRow("个性化", "排版、色彩与主题文件") { navigate("appearance") } }
        item { SettingRow("播放与悬浮窗", "播放状态权限 · 窗口与通知") { navigate("permissions") } }
        item { SettingRow("歌词与评论来源", "QQ 音乐 · 网易云音乐 · LRCLIB") { navigate("sources") } }
        item { SettingRow("在线翻译", "你的 API、你的模型") { navigate("translation") } }
        item { Section("数据与支持") }
        item {
            SettingRow(
                "GitHub 同步",
                if (githubLogin.isBlank()) "可选登录 · 主题、配置与歌词的私有仓库" else "@$githubLogin · 管理私有仓库同步",
            ) {
                navigate("github")
            }
        }
        item { SettingRow("导入、导出与备份", "本地文件与数据管理") { navigate("data") } }
        item { SettingRow("帮助与隐私", "权限说明、接口与兼容边界") { navigate("help") } }
        item {
            SettingRow(if (s.demo || s.browsing) "返回实时聆听" else "进入交互预览", "原创示例，不播放音频") {
                if (s.demo || s.browsing) live() else preview()
            }
        }
        item {
            Text(
                "MADE FOR THE WORDS BETWEEN NOTES",
                Modifier.padding(vertical = 28.dp),
                fontSize = 9.sp,
                letterSpacing = 1.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun SearchSheet(
    graph: AppGraph,
    current: Track?,
    dismiss: () -> Unit,
    providers: List<Provider> = LYRIC_PROVIDERS,
    initialSource: Provider? = null,
    select: (Track) -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var source by remember {
        mutableStateOf(
            (initialSource ?: graph.state.value.source).takeIf { it in providers }
                ?: providers.first()
        )
    }
    var query by remember { mutableStateOf(current?.let { "${it.title} ${it.artist}" }.orEmpty()) }
    var id by remember { mutableStateOf("") }
    var manual by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var rows by remember { mutableStateOf(emptyList<Track>()) }
    var expanded by remember { mutableStateOf(true) }
    var cursors by remember { mutableStateOf(emptyMap<String, Pair<Int, String>>()) }
    var signatures by remember { mutableStateOf(emptyMap<String, String>()) }
    var note by remember { mutableStateOf("") }
    var total by remember { mutableStateOf<Int?>(null) }
    var requestVersion by remember { mutableIntStateOf(0) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    fun invalidate() {
        requestVersion++
        searchJob?.cancel()
        busy = false
        error = ""
        rows = emptyList()
        cursors = emptyMap()
        signatures = emptyMap()
        total = null
        note = ""
    }
    fun request(block: suspend () -> List<Track>) {
        keyboard?.hide()
        focusManager.clearFocus()
        invalidate()
        val token = requestVersion
        searchJob = scope.launch {
            busy = true
            error = ""
            rows = emptyList()
            try {
                val found = block()
                if (token != requestVersion) return@launch
                rows = found
                if (rows.isEmpty()) error = "未返回候选，可简化关键词或使用歌曲链接／ID。"
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (token == requestVersion) error = e.message ?: "搜索失败"
            } finally {
                if (token == requestVersion) busy = false
            }
        }
    }
    fun search(append: Boolean = false) {
        keyboard?.hide()
        focusManager.clearFocus()
        if (!append) invalidate()
        val token = requestVersion
        val provider = source
        val text = query.trim()
        val pending =
            if (append) cursors
            else SongSearch.queries(text, current, expanded).associateWith { 0 to "" }
        val oldRows = if (append) rows else emptyList()
        searchJob = scope.launch {
            busy = true
            error = ""
            val collected = oldRows.toMutableList()
            val next = pending.toMutableMap()
            val nextSignatures = signatures.toMutableMap()
            val notices = mutableListOf<String>()
            val failures = mutableListOf<String>()
            try {
                for ((variant, cursor) in pending) {
                    try {
                        val result =
                            graph.api.searchPage(
                                provider,
                                variant,
                                cursor.first,
                                channel = cursor.second,
                                current = current,
                            )
                        if (token != requestVersion) return@launch
                        val signature = result.tracks.joinToString("|") { SongSearch.identity(it) }
                        val repeated = cursor.first > 0 && signature == nextSignatures[variant]
                        if (!repeated) collected += result.tracks
                        if (result.more && !repeated && cursor.first < 99)
                            next[variant] = cursor.first + 1 to result.channel
                        else next.remove(variant)
                        nextSignatures[variant] = signature
                        if (result.note.isNotBlank()) notices += result.note
                        if (variant == pending.keys.first() && !append) total = result.total
                        // Publish each successful query immediately, rather than wait for all
                        // aliases.
                        rows = SongSearch.rank(collected, text, current).take(300)
                        if (provider == Provider.LRCLIB && collected.isNotEmpty()) {
                            // LRCLIB aliases are fallbacks, not three unconditional round trips.
                            next.keys.retainAll(setOf(variant))
                            break
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        failures += e.message ?: "部分搜索失败，可重试"
                        if (provider == Provider.LRCLIB) break
                    }
                }
                if (token != requestVersion) return@launch
                rows = SongSearch.rank(collected, text, current).take(300)
                cursors = if (rows.size >= 300) emptyMap() else next
                signatures = nextSignatures
                note = notices.distinct().joinToString("\n")
                error = failures.distinct().joinToString("\n")
                if (rows.isEmpty() && error.isBlank()) error = "未返回候选，可尝试歌名片段、歌手或歌曲链接。"
            } finally {
                if (token == requestVersion) busy = false
            }
        }
    }
    ModalBottomSheet(
        onDismissRequest = dismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
    ) {
        FlowSheetSystemBars()
        Column(
            Modifier.fillMaxWidth()
                .heightIn(max = 780.dp)
                .imePadding()
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("寻找这首歌", fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
            Text(
                if (current != null) "核对歌名、歌手和专辑，再确认匹配。" else "浏览真实歌词与评论；不会播放音频。",
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
            )
            Choice(providers.map { it.label }, providers.indexOf(source).coerceAtLeast(0)) {
                source = providers[it]
                invalidate()
            }
            if (source == Provider.LRCLIB)
                Text("LRCLIB 社区记录可能包含罗马音，候选会标注文字类型。可能没有中文译文；评论仍来自 QQ／网易云。", fontSize = 12.sp)
            OutlinedTextField(
                query,
                {
                    query = it
                    invalidate()
                },
                label = { Text("歌曲 / 歌手") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            FlowButton(
                onClick = { search() },
                enabled = !busy && query.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (busy) "正在搜索…" else "搜索歌曲")
            }
            Toggle("扩展歌名与别名", expanded, "支持近似关键词并按相似度排序；开启后还会尝试去掉译名、仅搜当前歌名。") {
                expanded = it
                invalidate()
            }
            TextButton(onClick = { manual = !manual }) {
                Text(if (manual) "收起手动绑定" else "使用歌曲链接／ID 绑定")
            }
            if (manual) {
                Text(
                    when (source) {
                        Provider.QQ -> "填写 QQ 音乐歌曲链接中的 songmid（例如 0039MnYb0qxYhV）"
                        Provider.NETEASE -> "填写网易云歌曲链接或数字 ID（例如 186016）"
                        else -> "填写 LRCLIB 数字记录 ID 或 /api/get/<id> 链接"
                    },
                    fontSize = 12.sp,
                    lineHeight = 19.sp,
                )
                OutlinedTextField(
                    id,
                    { id = it },
                    label = {
                        Text(
                            when (source) {
                                Provider.QQ -> "songmid / 歌曲链接"
                                Provider.LRCLIB -> "LRCLIB 记录 ID / 链接"
                                else -> "歌曲 ID / 链接"
                            }
                        )
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    singleLine = true,
                )
                FlowOutlinedButton(
                    onClick = {
                        request {
                            val value = id.trim()
                            val key =
                                if (value.startsWith("http")) {
                                    val uri = Uri.parse(value)
                                    val hosts =
                                        when (source) {
                                            Provider.QQ -> listOf("y.qq.com")
                                            Provider.NETEASE ->
                                                listOf("music.163.com", "m.music.163.com")
                                            else -> listOf("lrclib.net", "www.lrclib.net")
                                        }
                                    require(uri.host in hosts) {
                                        "仅支持平台歌曲链接"
                                    }
                                    if (source == Provider.QQ)
                                        Regex("[A-Za-z0-9]{14}").find(value)?.value
                                            ?: error("链接中未找到 songmid")
                                    else if (source == Provider.NETEASE)
                                        Regex("(?:id=|song/)([0-9]+)")
                                            .find(value)
                                            ?.groupValues
                                            ?.get(1) ?: error("链接中未找到歌曲 ID")
                                    else
                                        Regex("/api/get/([0-9]+)")
                                            .find(uri.path.orEmpty())
                                            ?.groupValues
                                            ?.get(1) ?: error("链接中未找到 LRCLIB 记录 ID")
                                } else value
                            listOf(
                                when (source) {
                                    Provider.QQ -> graph.api.resolveQQ(key)
                                    Provider.NETEASE -> graph.api.resolveNet(key)
                                    else -> graph.api.resolveLrc(key)
                                }
                            )
                        }
                    },
                    enabled = !busy && id.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("读取歌曲信息")
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 12.dp))
            if (error.isNotBlank())
                Text(
                    error,
                    Modifier.padding(vertical = 12.dp),
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 13.sp,
                )
            if (note.isNotBlank())
                Text(note, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
            if (rows.isNotEmpty())
                Text(
                    "已显示 ${rows.size} 首" + (total?.let { " · 原关键词约 $it 首" } ?: ""),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            if (cursors.isNotEmpty())
                FlowOutlinedButton(
                    onClick = { search(true) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("加载更多候选")
                }
            if (rows.isNotEmpty()) Spacer(Modifier.height(16.dp))
            rows.forEach { t ->
                SettingRow(
                    t.title,
                    "${t.artist} · ${t.album}\n${t.duration/1000} 秒 · ${t.provider.label}" +
                        graph.api.lyricHint(t).let { if (it.isBlank()) "" else "\n$it" },
                ) {
                    select(t)
                }
            }
            if (cursors.isNotEmpty())
                FlowOutlinedButton(
                    onClick = { search(true) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("加载更多候选")
                }
            if (rows.size >= 300) Text("已显示 300 首，请缩小关键词继续查找。", fontSize = 12.sp)
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
fun CommentsScreen(graph: AppGraph, pinned: Track?, initial: Track?, back: () -> Unit) {
    val scope = rememberCoroutineScope()
    val current by graph.state.collectAsState()
    var provider by remember {
        mutableStateOf(
            runCatching { Provider.valueOf(graph.commentPreference(pinned?.key.orEmpty())) }
                .getOrNull()
                ?.takeIf { it in listOf(Provider.QQ, Provider.NETEASE) }
                ?: initial?.provider?.takeIf { it in listOf(Provider.QQ, Provider.NETEASE) }
                ?: Provider.QQ
        )
    }
    var hot by remember { mutableStateOf(true) }
    var comments by remember { mutableStateOf(emptyList<Comment>()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var more by remember { mutableStateOf(false) }
    var page by remember { mutableIntStateOf(0) }
    var cursor by remember { mutableStateOf("") }
    var binding by remember { mutableStateOf(initial) }
    var search by remember { mutableStateOf(false) }
    var version by remember { mutableIntStateOf(0) }
    var revision by remember { mutableIntStateOf(0) }
    var selectedJob by remember { mutableStateOf<Job?>(null) }
    fun fetch(reset: Boolean = false, force: Boolean = false) {
        selectedJob?.cancel()
        val token = ++version
        selectedJob = scope.launch {
            busy = true
            error = ""
            try {
                var t = binding?.takeIf { it.provider == provider }
                if (t == null && pinned != null) {
                    t =
                        withContext(Dispatchers.IO) {
                            graph.store.get("binding", pinned.key + ":" + provider.name)?.let {
                                readTrack(JSONObject(it))
                            }
                        }
                            ?: SongSearch.automatic(
                                pinned,
                                graph.api.searchExpanded(
                                    provider,
                                    "${pinned.title} ${pinned.artist}",
                                    pinned,
                                ),
                            )
                }
                if (t == null) throw ApiFailure("请先为此来源匹配歌曲。预览示例没有真实平台评论。")
                if (token != version) return@launch
                binding = t
                val result =
                    graph.api.comments(
                        t,
                        hot,
                        if (reset) 0 else page,
                        if (reset) "" else cursor,
                        force,
                    )
                if (token != version) return@launch
                val old = if (reset) emptyList() else comments
                val next = (old + result.comments).distinctBy { it.id }
                val advancing = reset || result.cursor.isBlank() || result.cursor != cursor
                more = result.more && next.size > old.size && advancing
                comments = next
                cursor = result.cursor
                page = if (reset) 1 else page + 1
                if (comments.isEmpty()) error = "这首歌暂时没有${if(hot)"热门" else "最新"}评论。"
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (token == version) error = e.message ?: "评论加载失败"
            } finally {
                if (token == version) busy = false
            }
        }
    }
    LaunchedEffect(provider, hot, revision) {
        graph.commentPreference(pinned?.key.orEmpty(), provider.name)
        comments = emptyList()
        page = 0
        cursor = ""
        more = false
        fetch(true)
    }
    DisposableEffect(Unit) { onDispose { selectedJob?.cancel() } }
    LazyColumn(Modifier.fillMaxSize()) {
        item { PageTitle("听听共鸣", pinned?.let { "${it.title} · ${it.artist}" } ?: "先选择一首歌") }
        if (pinned != null && current.playback.track?.key != pinned.key)
            item {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("正在阅读进入页面时的歌曲。播放已切换，可返回首页查看新歌。", Modifier.padding(14.dp), fontSize = 12.sp)
                }
            }
        item {
            Choice(listOf("QQ 音乐", "网易云音乐"), if (provider == Provider.QQ) 0 else 1) {
                provider = if (it == 0) Provider.QQ else Provider.NETEASE
                binding = initial?.takeIf { t -> t.provider == provider }
            }
        }
        item {
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                FlowChoiceChip("热门", hot) { hot = true }
                FlowChoiceChip("最新", !hot) { hot = false }
                TextButton(onClick = { search = true }) { Text("匹配此来源", fontSize = 12.sp) }
                IconButton(
                    onClick = { fetch(true, true) },
                    enabled = !busy,
                    modifier = Modifier.semantics { contentDescription = "刷新评论" },
                ) {
                    Glyph("refresh", Modifier.size(18.dp))
                }
            }
        }
        item {
            Text(
                "${provider.label} · 只读评论 · 不支持发布或点赞",
                Modifier.padding(bottom = 16.dp),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(comments, key = { provider.name + it.id }) { c -> FlowCommentCard(c) }
        if (busy)
            item {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(25.dp), strokeWidth = 2.dp)
                }
            }
        if (error.isNotBlank())
            item {
                Text(
                    error,
                    Modifier.padding(vertical = 20.dp),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowOutlinedButton(onClick = { fetch(comments.isEmpty(), true) }) { Text("重试") }
            }
        if (more && !busy)
            item {
                TextButton(onClick = { fetch() }, modifier = Modifier.fillMaxWidth()) {
                    Text("加载更多")
                }
            }
        if (!more && comments.isNotEmpty() && !busy)
            item {
                Text(
                    "共鸣暂时读到这里。",
                    Modifier.padding(vertical = 28.dp),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
    }
    if (search)
        SearchSheet(
            graph,
            pinned,
            { search = false },
            providers = listOf(Provider.QQ, Provider.NETEASE),
            initialSource = provider,
        ) { t ->
            if (t.provider != provider) {
                provider = t.provider
            }
            binding = t
            scope.launch(Dispatchers.IO) {
                if (pinned != null)
                    graph.store.put(
                        "binding",
                        pinned.key + ":" + t.provider,
                        trackJson(t).toString(),
                    )
            }
            search = false
            revision++
        }
}

@Composable
fun Appearance(
    s: ScreenState,
    graph: AppGraph,
    style: StyleSpec?,
    import: () -> Unit,
    export: () -> Unit,
    apply: (StyleSpec) -> Unit,
    background: () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageTitle("个性化", "文字的样子，也是聆听的一部分。")
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(Modifier.fillMaxWidth().padding(24.dp)) {
                Text(
                    "Leave a little room\nfor the light",
                    fontSize = s.prefs.fontSize.coerceAtMost(38f).sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 44.sp,
                )
                if (s.prefs.translation)
                    Text("为光，留一点空隙", Modifier.padding(top = 12.dp), fontSize = 14.sp)
            }
        }
        Section("歌词排版")
        Choice(listOf("经典滚动", "自由排版"), if (s.prefs.free) 1 else 0) {
            graph.preferences(s.prefs.copy(free = it == 1))
        }
        Text(
            "歌词字号 · ${s.prefs.fontSize.toInt()} sp",
            Modifier.padding(top = 18.dp),
            fontSize = 14.sp,
        )
        Slider(
            value = s.prefs.fontSize.coerceIn(16f, 48f),
            onValueChange = { graph.preferences(s.prefs.copy(fontSize = it)) },
            valueRange = 16f..48f,
        )
        Toggle("显示中文译文", s.prefs.translation) { graph.preferences(s.prefs.copy(translation = it)) }
        Toggle("显示来源提供的罗马音", s.prefs.romanization, "没有现成注音时不自动生成") {
            graph.preferences(s.prefs.copy(romanization = it))
        }
        Section("色彩与显示")
        Choice(listOf("苔绿", "陶土", "雾紫", "海蓝", "跟随封面"), s.prefs.palette) {
            graph.preferences(s.prefs.copy(palette = it))
        }
        Text(
            if (s.prefs.palette == 4 && s.playback.artwork == null) "暂无歌曲封面，暂用柔和海蓝。播放时自动更新。"
            else "跟随封面：取图片 RGB 均值，生成柔和主题色。封面背景下歌词使用反色并调整可读性。",
            fontSize = 12.sp,
            lineHeight = 19.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Choice(
            listOf("跟随系统", "浅色", "深色"),
            listOf("system", "light", "dark").indexOf(s.prefs.dark),
        ) {
            graph.preferences(s.prefs.copy(dark = listOf("system", "light", "dark")[it]))
        }
        Toggle("减少动态效果", s.prefs.reduced) { graph.preferences(s.prefs.copy(reduced = it)) }
        Section("歌词背景")
        Choice(
            listOf("纯色", "专辑封面", "自定义图片"),
            listOf("color", "cover", "custom").indexOf(s.prefs.background),
        ) {
            if (it == 2) background()
            else graph.preferences(s.prefs.copy(background = listOf("color", "cover")[it]))
        }
        Text(
            "封面模糊程度 · ${s.prefs.coverBlur.roundToInt()}",
            Modifier.padding(top = 12.dp),
            fontSize = 14.sp,
        )
        Slider(
            value = s.prefs.coverBlur.coerceIn(0f, 24f),
            onValueChange = { graph.preferences(s.prefs.copy(coverBlur = it)) },
            valueRange = 0f..24f,
            steps = 23,
            modifier =
                Modifier.testTag("cover-blur-slider").semantics { contentDescription = "封面模糊程度" },
        )
        Text(
            "0 保留原图，数值越大越柔和；仅模糊封面和自定义图片，歌词保持清晰。",
            fontSize = 12.sp,
            lineHeight = 19.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Section("主题文件")
        Text(style?.let { "当前主题：${it.name}" } ?: "当前主题：内置柔和", fontSize = 13.sp)
        Row {
            TextButton(onClick = import) { Text("导入并预览") }
            TextButton(onClick = export) { Text("导出主题") }
            TextButton(onClick = { apply(StyleCodec.parse(StyleCodec.preset())) }) { Text("重置主题") }
        }
        Text(
            "导入主题只执行原生声明式配置，支持字体、布局及白名单时间轴动画；不执行脚本。",
            fontSize = 12.sp,
            lineHeight = 20.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun PermissionSettings(s: ScreenState, graph: AppGraph, overlay: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageTitle("播放与悬浮窗", "为歌词打开一扇小窗。")
        SettingRow("播放状态权限", "通过通知使用权访问音乐 MediaSession；不读取或存储聊天通知。") {
            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        val live = !s.demo && !s.browsing && s.playback.track != null
        SettingRow(
            if (live) "已连接 ${playbackAppName(s.playback.packageName)}" else "等待本机播放器",
            if (live)
                "${s.playback.track!!.title} · ${if (s.playback.playing) "正在播放" else "已暂停"}；点击重新检测"
            else "授予通知使用权并在音乐 App 中播放歌曲。Spotify 本机连接无需登录授权或 API Key。点击重新检测。",
        ) {
            graph.live()
            graph.playback.connect()
        }
        Section("优先音乐 App")
        val pkgs = listOf("", "com.spotify.music", "com.tencent.qqmusic", "com.netease.cloudmusic")
        Choice(
            listOf("自动", "Spotify", "QQ 音乐", "网易云"),
            pkgs.indexOf(s.prefs.playbackApp).coerceAtLeast(0),
        ) {
            graph.preferences(s.prefs.copy(playbackApp = pkgs[it]))
        }
        Text(
            "自动选择优先正在播放的 Spotify，其次选择本机音乐会话。播放器需提供曲目与进度。",
            fontSize = 12.sp,
            lineHeight = 20.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
        Section("桌面悬浮窗")
        Toggle("开启悬浮歌词", s.overlay, "需要显示在其他应用上层权限") { overlay() }
        if (s.overlay)
            SettingRow(if (s.locked) "解锁悬浮窗" else "锁定并穿透悬浮窗", "也可在常驻通知中控制") {
                context.startService(
                    Intent(context, LyricsOverlayService::class.java)
                        .setAction(LyricsOverlayService.LOCK)
                )
            }
        Text(
            "悬浮字号 · ${s.prefs.overlaySize.toInt()} sp",
            Modifier.padding(top = 16.dp),
            fontSize = 14.sp,
        )
        Slider(
            value = s.prefs.overlaySize.coerceIn(12f, 48f),
            onValueChange = { graph.preferences(s.prefs.copy(overlaySize = it)) },
            valueRange = 12f..48f,
        )
        Text("背景不透明度 · ${(s.prefs.overlayAlpha*100).toInt()}%", fontSize = 14.sp)
        Slider(
            value = s.prefs.overlayAlpha.coerceIn(0f, 1f),
            onValueChange = { graph.preferences(s.prefs.copy(overlayAlpha = it)) },
            valueRange = 0f..1f,
        )
        OverlayColorSettings(s, graph)
        Toggle("悬浮窗显示译文", s.prefs.overlayTranslation) {
            graph.preferences(s.prefs.copy(overlayTranslation = it))
        }
        Text(
            "锁屏或熄屏隐藏；暂停保留当前歌词，5 分钟后隐藏，恢复播放时再次显示。关闭后需主动重新开启。",
            Modifier.padding(vertical = 20.dp),
            fontSize = 12.sp,
            lineHeight = 20.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun TranslationSettings(s: ScreenState, graph: AppGraph) {
    var url by remember { mutableStateOf(s.prefs.translationUrl) }
    var model by remember { mutableStateOf(s.prefs.model) }
    var key by remember { mutableStateOf("") }
    var consent by remember { mutableStateOf(s.prefs.consent) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())) {
        PageTitle("在线翻译", "使用你信任的 OpenAI 兼容服务。")
        Text(
            "仅向你设置的服务发送歌词文本及行 ID。可能产生费用。Key 通过 Android Keystore 加密保存，不进入备份或日志。",
            fontSize = 13.sp,
            lineHeight = 22.sp,
        )
        OutlinedTextField(
            url,
            { url = it },
            label = { Text("API Base URL（包含 /v1，如服务要求）") },
            placeholder = { Text("https://api.example.com/v1") },
            modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
            singleLine = true,
        )
        OutlinedTextField(
            model,
            { model = it },
            label = { Text("模型名称") },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            singleLine = true,
        )
        OutlinedTextField(
            key,
            { key = it },
            label = { Text("API Key（留空保留已保存的 Key）") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            singleLine = true,
        )
        Toggle("同意发送歌词并承担服务费用", consent) { consent = it }
        FlowButton(
            onClick = {
                runCatching {
                    require(url.isBlank() || Uri.parse(url).scheme == "https") { "仅支持 HTTPS" }
                    if (key.isNotBlank()) graph.secrets.write(key.trim())
                    graph.preferences(
                        s.prefs.copy(
                            translationUrl = url.trim().trimEnd('/'),
                            model = model.trim(),
                            consent = consent,
                            autoTranslate = consent,
                        )
                    )
                    key = ""
                    graph.message("翻译配置已保存")
                }
                    .onFailure { graph.message(it.message ?: "配置无效") }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("保存并${if(consent)"启用自动翻译" else "关闭翻译授权"}")
        }
        Toggle("自动翻译缺失的译文", s.prefs.autoTranslate, "仅已授权时生效；应用内每日不限字符数与请求次数") {
            graph.preferences(s.prefs.copy(autoTranslate = it))
        }
        FlowOutlinedButton(
            onClick = {
                val captured = graph.state.value
                val hash = captured.lyrics?.hash
                val tk = captured.playback.track?.key
                scope.launch {
                    busy = true
                    try {
                        val rows = graph.translate()
                        if (hash != null && tk != null) graph.applyTranslation(rows, hash, tk)
                        graph.message("译文已完成并缓存")
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        graph.message(e.message ?: "翻译失败")
                    } finally {
                        busy = false
                    }
                }
            },
            enabled = !busy && s.lyrics != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (busy) "正在翻译…" else "翻译当前歌词中的缺失译文")
        }
        TextButton(
            onClick = {
                graph.secrets.write("")
                graph.preferences(s.prefs.copy(consent = false))
                graph.message("API Key 已清除")
            }
        ) {
            Text("清除 API Key 并撤回同意")
        }
        Text(
            "授权后可自动翻译缺失译文，来源已有译文时保留。每批最多 30 行／4,000 字符，失败不自动无限重试。模型响应必须返回完整且不重复的行 ID。",
            fontSize = 12.sp,
            lineHeight = 20.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun SourceSettings(s: ScreenState, graph: AppGraph, search: () -> Unit) {
    var url by remember { mutableStateOf(s.prefs.gateway) }
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())) {
        PageTitle("歌词与评论来源", "先找到同一首歌，再读懂同一种心情。")
        Text("QQ 音乐、网易云音乐使用独立适配器。歌词与评论来源可分别选择，接口异常时不会展示伪造评论。", fontSize = 13.sp, lineHeight = 22.sp)
        Spacer(Modifier.height(16.dp))
        SettingRow("搜索与手动匹配", "通过歌名、歌手、网易云 ID 或 QQ songmid 确认歌曲", search)
        Section("网易云可选网关")
        Text(
            "留空直连。可配置兼容 api-enhanced 的 HTTPS 服务，使用 /search、/lyric/new、/comment/hot、/comment/music。自行配置的服务会收到歌曲检索信息。",
            fontSize = 12.sp,
            lineHeight = 20.sp,
        )
        OutlinedTextField(
            url,
            { url = it },
            label = { Text("网易云 HTTPS Base URL（可选）") },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            singleLine = true,
        )
        Spacer(Modifier.height(16.dp))
        FlowButton(
            onClick = {
                if (url.isBlank() || Uri.parse(url).scheme == "https") {
                    graph.preferences(s.prefs.copy(gateway = url.trim().trimEnd('/')))
                    graph.message("来源配置已保存")
                } else graph.message("仅支持 HTTPS 服务")
            }
        ) {
            Text("保存来源设置")
        }
        Section("当前能力")
        Text(
            "QQ：songmid 歌词、数字 songid 热门／最新评论、歌曲详情与搜索（搜索可能受限）。\n网易云：搜索、逐行歌词、译文、罗马音与热门／最新评论。兼容网关可额外返回 YRC 逐字时间轴。\nLRCLIB：补充歌词；不提供评论。\n\n不内置 Cookie 或第三方账号；不通过接口下载音乐。",
            fontSize = 13.sp,
            lineHeight = 23.sp,
        )
    }
}

@Composable
fun DataSettings(
    graph: AppGraph,
    import: () -> Unit,
    restore: () -> Unit,
    backup: () -> Unit,
    export: (Boolean) -> Unit,
) {
    var confirm by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageTitle("数据与文件", "喜欢的文字，可以留在身边。")
        SettingRow("导入歌词", "LRC · YRC · TTML · .lflyrics.json", import)
        SettingRow("导出当前歌词包", "保留原文、译文与逐字时间轴") { export(false) }
        SettingRow("导出当前 LRC", "导出原文的逐行时间戳") { export(true) }
        SettingRow("导出本地备份", "歌词、绑定、主题；不含 Key 或系统权限", backup)
        SettingRow("恢复本地备份", "校验内容后确认合并", restore)
        SettingRow("清理网络缓存", "保留保存的歌词和导入文件") { confirm = true }
        Text(
            "系统文件选择器用于导入导出，不申请所有文件访问权限。请自行备份重要资料。",
            Modifier.padding(vertical = 24.dp),
            fontSize = 12.sp,
            lineHeight = 20.sp,
        )
    }
    if (confirm)
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("清理网络缓存？") },
            text = { Text("已保存的歌词、手动绑定和主题会保留。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch(Dispatchers.IO) { graph.store.clearCache() }
                        confirm = false
                        graph.message("网络缓存已清理")
                    }
                ) {
                    Text("清理")
                }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } },
        )
}

@Composable
fun Library(graph: AppGraph, open: (Track, Lyrics) -> Unit) {
    var rows by remember { mutableStateOf(emptyList<Pair<Track, Lyrics>>()) }
    val scope = rememberCoroutineScope()
    var deleting by remember { mutableStateOf<Track?>(null) }
    LaunchedEffect(Unit) {
        rows =
            withContext(Dispatchers.IO) {
                graph.store.all("saved").mapNotNull { (_, b) ->
                    runCatching {
                        val o = JSONObject(b)
                        readTrack(o.getJSONObject("track")) to readLyrics(o.getJSONObject("lyrics"))
                    }
                        .getOrNull()
                }
            }
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item { PageTitle("本地歌词库", "把喜欢的歌词，留给下次聆听。") }
        if (rows.isEmpty())
            item {
                Text(
                    "暂时没有保存的歌词。\n在首页点击爱心即可保存当前歌词。",
                    fontSize = 14.sp,
                    lineHeight = 24.sp,
                    modifier = Modifier.padding(vertical = 30.dp),
                )
            }
        items(rows, key = { it.first.key }) { (t, l) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    SettingRow(t.title, "${t.artist} · ${l.lines.size} 行 · ${l.source.label}") {
                        open(t, l)
                    }
                }
                TextButton(onClick = { deleting = t }) { Text("删除", fontSize = 12.sp) }
            }
        }
    }
    deleting?.let { t ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("从歌词库移除「${t.title}」？") },
            text = { Text("只移除保存的副本，来源绑定和时间偏移会保留。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { graph.store.remove("saved", t.key) }
                            rows = rows.filter { it.first.key != t.key }
                            deleting = null
                        }
                    }
                ) {
                    Text("移除")
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
}

@Composable
fun HelpScreen(page: String) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageTitle("帮助与隐私", "LyricsFlow · 原生 Android 开发版")
        Section("如何开始")
        Text(
            "1. 在「播放与悬浮窗」授予通知使用权。\n2. 在 Spotify 或其他支持的本机播放器中播放歌曲。\n3. 返回首页，确认歌词来源；匹配失败时搜索或填写歌曲 ID。\n4. 开启悬浮窗后，可拖动、缩放，并在通知中锁定或关闭。",
            fontSize = 14.sp,
            lineHeight = 26.sp,
        )
        Section("数据与隐私")
        Text(
            "仅访问音乐应用的 MediaSession 曲目和进度，不读取聊天通知正文。网络请求发送必要的歌曲信息和所选来源 ID。在线翻译仅在你配置并同意后主动触发。\n\n密钥使用设备 Keystore 加密，备份不含凭证；本地数据库保存歌词、绑定和缓存。不采集设备分析数据。",
            fontSize = 13.sp,
            lineHeight = 24.sp,
        )
        Section("兼容边界")
        Text(
            "QQ 和网易云使用非官方接口，可用性受地区、网络与平台调整影响。同步精度取决于播放器提供的进度。没有真实逐字时间戳时仅逐行同步。预览模式为自有测试文本，不播放音频。\n\nGitHub 为可选登录，手动同步到个人私有仓库。Token 通过 Android Keystore 加密，不进入备份；退出不会删除远端文件。远程 Spotify、自动生成注音仍未提供。",
            fontSize = 13.sp,
            lineHeight = 24.sp,
        )
        Spacer(Modifier.height(24.dp))
    }
}
