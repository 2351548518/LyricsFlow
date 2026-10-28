package com.example.lyricsflow.core

import android.app.Application
import android.os.SystemClock
import com.example.lyricsflow.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

data class Preferences(
    val translation: Boolean = true,
    val romanization: Boolean = false,
    val free: Boolean = false,
    val fontSize: Float = 30f,
    val dark: String = "system",
    val palette: Int = 0,
    val reduced: Boolean = false,
    val overlaySize: Float = 22f,
    val overlayAlpha: Float = .85f,
    val overlayTranslation: Boolean = true,
    val translationUrl: String = "",
    val model: String = "",
    val consent: Boolean = false,
    val gateway: String = "",
    val playbackApp: String = "",
    val background: String = "color",
    val autoTranslate: Boolean = false,
    val autoSources: Boolean = true,
    val coverBlur: Float = 8f,
    val overlayColor: String = "",
    val coverMesh: Boolean = true,
)

data class ScreenState(
    val playback: Playback = Playback(),
    val lyrics: Lyrics? = null,
    val binding: Track? = null,
    val source: Provider = Provider.QQ,
    val busy: Boolean = false,
    val message: String = "",
    val matchNotice: String = "",
    val demo: Boolean = false,
    val browsing: Boolean = false,
    val delay: Long = 0,
    val prefs: Preferences = Preferences(),
    val overlay: Boolean = false,
    val locked: Boolean = false,
    val saved: Boolean = false,
    val styleRaw: String = "",
    val overlayStyleRaw: String = "",
    val toast: String = "",
    val imageRevision: Int = 0,
    val selectionMode: String = "auto",
    val requestedSource: Provider? = null,
)

class LyricsFlowApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        StyleSchema.load(
            assets.open("lyricsflow.style.schema.json").bufferedReader().use { it.readText() }
        )
        graph = AppGraph(this)
    }
}

class AppGraph(val app: Application, lookup: LyricsLookup? = null) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val store = Store(app)
    val secrets = Secrets(app)
    val api = MusicApi(store)
    private val songLyrics = SongLyrics(store, lookup ?: api)
    val github by lazy { GitHubSync(this) }
    private val prefs = app.getSharedPreferences("settings", 0)
    private val mutable = MutableStateFlow(ScreenState(prefs = readPrefs()))
    val state = mutable.asStateFlow()
    private var loadJob: Job? = null
    @Volatile private var generation = 0L

    private data class LyricRequest(val action: String, val source: Provider?, val record: Track?)

    private var pendingLyrics: LyricRequest? = null
    private var lastReal = Playback()
    private var clockJob: Job? = null
    private val translationLock = Mutex()
    val playback = com.example.lyricsflow.playback.PlaybackMonitor(app) { onPlayback(it) }

    init {
        CalibrationOffsets.repairAccidental300(prefs)
        api.neteaseGateway = state.value.prefs.gateway
        playback.preferred = state.value.prefs.playbackApp
        reloadStyles()
        scope.launch {
            state
                .map {
                    Triple(
                        it.playback.track?.key.orEmpty() + it.lyrics?.hash,
                        it.prefs.autoTranslate && it.prefs.consent,
                        it.lyrics,
                    )
                }
                .distinctUntilChanged()
                .collectLatest { (_, enabled, l) ->
                    if (
                        !enabled ||
                            l == null ||
                            l.lines.none {
                                it.translation.isBlank() && needsChineseTranslation(it.text)
                            } ||
                            state.value.demo
                    )
                        return@collectLatest
                    val captured = state.value
                    val track = captured.playback.track ?: return@collectLatest
                    try {
                        val rows = translate()
                        applyTranslation(rows, l.hash, track.key)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        message("自动翻译未完成：${e.message}")
                    }
                }
        }
    }

    fun style(raw: String) {
        val spec = StyleCodec.parse(raw)
        val o = jsonObject(raw)
        val a = o.getJSONArray("targets")
        val targets = (0 until a.length()).map { a.getString(it) }
        mutable.update {
            it.copy(
                styleRaw = if ("home" in targets) raw else it.styleRaw,
                overlayStyleRaw = if ("overlay" in targets) raw else it.overlayStyleRaw,
            )
        }
        if ("home" in targets)
            preferences(state.value.prefs.copy(free = spec.mode == "free", fontSize = spec.size))
        scope.launch(Dispatchers.IO) {
            for (target in targets) store.put("style", target, raw)
            store.put("style", "user:${o.getString("id")}", raw)
        }
    }

    fun reloadStyles() {
        scope.launch {
            val pair =
                withContext(Dispatchers.IO) {
                    val legacy = store.get("style", "active").orEmpty()
                    (store.get("style", "home") ?: legacy) to
                        (store.get("style", "overlay") ?: legacy)
                }
            mutable.update { it.copy(styleRaw = pair.first, overlayStyleRaw = pair.second) }
        }
    }

    private fun readPrefs() =
        Preferences(
            prefs.getBoolean("translation", true),
            prefs.getBoolean("romanization", false),
            prefs.getBoolean("free", false),
            prefs.getFloat("fontSize", 30f),
            prefs.getString("dark", "system")!!,
            prefs.getInt("palette", 0),
            prefs.getBoolean("reduced", false),
            prefs.getFloat("overlaySize", 22f),
            prefs.getFloat("overlayAlpha", .85f).takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: .85f,
            prefs.getBoolean("overlayTranslation", true),
            prefs.getString("url", "")!!,
            prefs.getString("model", "")!!,
            prefs.getBoolean("consent", false),
            prefs.getString("gateway", "")!!,
            prefs.getString("playbackApp", "")!!,
            prefs.getString("background", "color")!!,
            prefs.getBoolean("autoTranslate", false),
            prefs.getBoolean("autoSources", true),
            prefs.getFloat("coverBlur", 8f).takeIf { it.isFinite() }?.coerceIn(0f, 24f) ?: 8f,
            OverlayColors.normalize(prefs.getString("overlayColor", "").orEmpty()).orEmpty(),
            prefs.getBoolean("coverMesh", true),
        )

    fun preferences(p: Preferences) {
        val playbackSelectionChanged = state.value.prefs.playbackApp != p.playbackApp
        prefs
            .edit()
            .putBoolean("translation", p.translation)
            .putBoolean("romanization", p.romanization)
            .putBoolean("free", p.free)
            .putFloat("fontSize", p.fontSize)
            .putString("dark", p.dark)
            .putInt("palette", p.palette)
            .putBoolean("reduced", p.reduced)
            .putFloat("overlaySize", p.overlaySize)
            .putFloat("overlayAlpha", p.overlayAlpha)
            .putBoolean("overlayTranslation", p.overlayTranslation)
            .putString("overlayColor", p.overlayColor)
            .putString("url", p.translationUrl)
            .putString("model", p.model)
            .putBoolean("consent", p.consent)
            .putString("gateway", p.gateway)
            .putString("playbackApp", p.playbackApp)
            .putString("background", p.background)
            .putFloat("coverBlur", p.coverBlur)
            .putBoolean("coverMesh", p.coverMesh)
            .apply()
        prefs.edit().putBoolean("autoTranslate", p.autoTranslate).apply()
        prefs.edit().putBoolean("autoSources", p.autoSources).apply()
        api.neteaseGateway = p.gateway
        mutable.update { it.copy(prefs = p) }
        playback.preferred = p.playbackApp
        if (playbackSelectionChanged) playback.refresh()
    }

    fun message(value: String) {
        mutable.update { it.copy(message = value, toast = value) }
    }

    fun consumeToast(value: String) {
        mutable.update { if (it.toast == value) it.copy(toast = "") else it }
    }

    fun backgroundChanged() {
        mutable.update { it.copy(imageRevision = it.imageRevision + 1) }
    }

    fun onPlayback(p: Playback) {
        lastReal = p
        if (state.value.demo || state.value.browsing) return
        val old = state.value
        val changed = p.track?.key != old.playback.track?.key
        val durationArrived =
            !changed &&
                p.track != null &&
                p.track.duration > 0 &&
                (old.playback.track?.duration ?: 0) <= 0 &&
                old.lyrics == null
        mutable.update { it.copy(playback = p) }
        if (changed) {
            resetContent()
            if (p.track != null) {
                val source = runCatching {
                    Provider.valueOf(
                        prefs.getString(
                            "source:${p.track.key}",
                            prefs.getString("lyricPriority", "QQ"),
                        )!!
                    )
                }
                    .getOrDefault(Provider.QQ)
                mutable.update { it.copy(source = source) }
                loadAutomatic(p.track)
            }
        } else if (durationArrived) {
            val pending = pendingLyrics
            loadAutomatic(p.track!!, pending?.action ?: "load", pending?.source, pending?.record)
        }
    }

    fun live() {
        clockJob?.cancel()
        val source = runCatching {
            Provider.valueOf(
                prefs.getString(
                    "source:${lastReal.track?.key}",
                    prefs.getString("lyricPriority", "QQ"),
                )!!
            )
        }
            .getOrDefault(Provider.QQ)
        mutable.update {
            it.copy(demo = false, browsing = false, playback = lastReal, source = source)
        }
        resetContent()
        lastReal.track?.let { loadAutomatic(it) }
        playback.refresh()
    }

    fun preview() {
        loadJob?.cancel()
        generation++
        clockJob?.cancel()
        mutable.update {
            it.copy(
                demo = true,
                browsing = false,
                source = Provider.LOCAL,
                playback =
                    Playback(Demo.track, "预览 · 自有示例", false, 27000, SystemClock.elapsedRealtime()),
                lyrics = Demo.lyrics,
                binding = null,
                busy = false,
                message = "预览模式 · 示例歌词由 LyricsFlow 创作，不播放音频",
                delay = 0,
            )
        }
    }

    fun demoPlay() {
        if (!state.value.demo) return
        val p = state.value.playback
        mutable.update {
            it.copy(
                playback =
                    p.copy(
                        position = p.at(SystemClock.elapsedRealtime()),
                        updatedAt = SystemClock.elapsedRealtime(),
                        playing = !p.playing,
                    )
            )
        }
    }

    fun demoSeek(position: Long) {
        if (state.value.demo)
            mutable.update {
                it.copy(
                    playback =
                        it.playback.copy(
                            position = position,
                            updatedAt = SystemClock.elapsedRealtime(),
                        )
                )
            }
    }

    private fun resetContent() {
        generation++
        loadJob?.cancel()
        pendingLyrics = null
        mutable.update {
            it.copy(
                lyrics = null,
                binding = null,
                busy = false,
                delay = 0,
                message = "",
                matchNotice = "",
                saved = false,
                selectionMode = "auto",
                requestedSource = null,
            )
        }
    }

    fun selectSource(p: Provider) {
        require(p in LYRIC_PROVIDERS)
        val s = state.value
        if (!s.busy && s.lyrics?.source == p && s.selectionMode == "record") return
        state.value.playback.track?.let { loadAutomatic(it, "select", p) }
    }

    fun browse(t: Track) {
        clockJob?.cancel()
        mutable.update {
            it.copy(demo = false, browsing = true, playback = Playback(t, "手动浏览 · 未播放音频"))
        }
        resetContent()
        bind(t)
    }

    fun browseLocal(t: Track, l: Lyrics) {
        resetContent()
        mutable.update {
            it.copy(
                demo = false,
                browsing = true,
                playback = Playback(t, "本地歌词库"),
                lyrics = l,
                source = l.source,
            )
        }
    }

    fun bind(t: Track) {
        if (state.value.playback.track == null) {
            browse(t)
            return
        }
        loadAutomatic(state.value.playback.track ?: t, "bind", t.provider, t)
    }

    private fun loadAutomatic(
        track: Track,
        action: String = "load",
        requested: Provider? = null,
        record: Track? = null,
    ) {
        val token = ++generation
        loadJob?.cancel()
        pendingLyrics = LyricRequest(action, requested, record)
        val old = state.value
        val busyText =
            when (action) {
                "select" -> "正在切换到 ${requested?.label}…"
                "bind" -> "正在确认 ${requested?.label} 的这条歌词…"
                "refresh" -> "正在刷新 ${requested?.label} 的当前记录…"
                "auto" -> "正在按顺序重新匹配…"
                else -> "正在恢复这首歌的歌词选择…"
            }
        mutable.update {
            it.copy(
                busy = true,
                message = busyText,
                matchNotice = if (it.lyrics != null) busyText else "",
                requestedSource = requested,
            )
        }
        var target = requested ?: old.source
        fun currentRequest() = token == generation && state.value.playback.track?.key == track.key
        fun failed(reason: String) {
            if (!currentRequest()) return
            val label =
                when (action) {
                    "select" -> "切换到 ${target.label} 失败"
                    "bind" -> "绑定 ${target.label} 失败"
                    "refresh" -> "刷新 ${target.label} 失败"
                    else -> "歌词匹配未完成"
                }
            mutable.update {
                val kept = it.lyrics?.source
                val text =
                    "$label：$reason" +
                        if (kept != null) "；仍显示 ${kept.label} 歌词。可在歌曲匹配中确认记录。"
                        else "；可在歌曲匹配中搜索或手动绑定。"
                it.copy(
                    busy = false,
                    source = kept ?: target,
                    message = text,
                    matchNotice = if (kept != null) text else "",
                    requestedSource = target,
                )
            }
            // Retain the request for one retry if missing duration metadata arrives later.
        }
        loadJob = scope.launch {
            try {
                if (action == "load") delay(300)
                val current = state.value.playback.track?.takeIf { it.key == track.key } ?: track
                val choice =
                    withContext(Dispatchers.IO) {
                        songLyrics.selection(current)
                            ?: prefs
                                .getString("source:${current.key}", null)
                                ?.let { runCatching { Provider.valueOf(it) }.getOrNull() }
                                ?.let { songLyrics.legacy(current, it) }
                    }
                val pinned = choice?.takeIf { it.pinned && action == "load" }
                target =
                    requested
                        ?: pinned?.source
                        ?: if (state.value.prefs.autoSources || action == "auto") Provider.QQ
                        else
                            runCatching {
                                    Provider.valueOf(prefs.getString("lyricPriority", "QQ")!!)
                                }
                                .getOrDefault(Provider.QQ)
                val fallback =
                    action == "auto" ||
                        action == "load" && pinned == null && state.value.prefs.autoSources
                val exact = action in listOf("refresh", "bind") || pinned?.mode == "record"
                val wanted = record ?: pinned?.record
                suspend fun lookup(source: Provider) =
                    withContext(Dispatchers.IO) {
                        songLyrics.lookup(
                            current,
                            source,
                            action == "refresh",
                            exact,
                            wanted?.takeIf { it.provider == source },
                            action == "auto",
                        )
                    }
                val result =
                    withTimeout(40000) {
                        if (target == Provider.LOCAL) {
                            val local = lookup(target)
                            LyricsResolution(
                                local.resolved,
                                if (local.resolved == null)
                                    listOf(SourceAttempt(target, local.reason))
                                else emptyList(),
                            )
                        } else
                            AutomaticLyrics.resolve(
                                target,
                                fallback,
                                { source, index, total ->
                                    if (currentRequest())
                                        mutable.update {
                                            it.copy(
                                                message = "正在匹配 ${source.label}（$index/$total）…"
                                            )
                                        }
                                },
                            ) {
                                lookup(it)
                            }
                    }
                if (!currentRequest()) return@launch
                val found = result.resolved
                if (found == null) {
                    failed(
                        result.attempts
                            .joinToString("；") { "${it.source.label}：${it.reason}" }
                            .ifBlank { "没有可用歌词" }
                    )
                    return@launch
                }
                val actual = found.lyrics.source
                val mode =
                    when (action) {
                        "select" -> "source"
                        "bind" -> "record"
                        "auto" -> "auto"
                        else -> choice?.mode ?: "auto"
                    }
                val candidateChoice = SongSelection(current, actual, found.binding, mode)
                val selected =
                    choice?.takeIf {
                        it.copy(updatedAt = 0) == candidateChoice.copy(updatedAt = 0)
                    } ?: candidateChoice
                withContext(Dispatchers.IO) {
                    val rows =
                        mutableListOf(
                            Triple(
                                "lyrics",
                                "${track.key}:${actual.name}",
                                lyricsJson(found.lyrics).toString(),
                            ),
                            Triple("selection", track.key, selected.json().toString()),
                        )
                    found.binding?.let {
                        rows +=
                            Triple(
                                "binding",
                                "${track.key}:${actual.name}",
                                trackJson(it).toString(),
                            )
                    }
                    store.restore(rows) { token == generation }
                }
                if (!currentRequest()) return@launch
                prefs
                    .edit()
                    .apply {
                        if (selected.pinned) putString("source:${track.key}", actual.name)
                        else remove("source:${track.key}")
                    }
                    .apply()
                val notice =
                    found.notice.ifBlank {
                        when {
                            action == "select" || action == "bind" -> "已记住这首歌的 ${actual.label} 歌词选择"
                            action == "refresh" -> "已刷新 ${actual.label} 的当前记录"
                            action == "auto" -> "已重新自动匹配到 ${actual.label}"
                            pinned != null -> "已恢复这首歌的 ${actual.label} 歌词选择"
                            actual != target -> "${target.label} 未取得可用匹配，已自动使用 ${actual.label}"
                            else -> ""
                        }
                    }
                mutable.update {
                    it.copy(
                        binding = found.binding,
                        lyrics = found.lyrics,
                        source = actual,
                        busy = false,
                        delay = offset(track, actual),
                        message = if (found.cached) "已载入本地歌词" else "",
                        matchNotice = notice,
                        selectionMode = mode,
                        requestedSource = null,
                    )
                }
                pendingLyrics = null
            } catch (_: TimeoutCancellationException) {
                failed("请求超时，请稍后重试")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed(e.message ?: "请求失败")
            }
        }
    }

    /** Refresh the displayed record; never restart global provider matching. */
    fun refresh() {
        val s = state.value
        s.playback.track?.let {
            loadAutomatic(
                it,
                "refresh",
                s.lyrics?.source ?: s.requestedSource ?: s.source,
                s.binding,
            )
        }
    }

    fun matchAutomatically() {
        state.value.playback.track?.let { loadAutomatic(it, "auto") }
    }

    fun reloadRememberedLyrics() {
        val s = state.value
        if (!s.demo && !s.browsing) s.playback.track?.let { loadAutomatic(it) }
    }

    private fun offset(t: Track, p: Provider) = prefs.getLong("delay:${t.key}:${p.name}", 0)

    fun setLyricOffset(value: Long) {
        val v = value.coerceIn(-10000, 10000)
        state.value.playback.track?.let {
            prefs.edit().putLong("delay:${it.key}:${state.value.source}", v).apply()
        }
        mutable.update { it.copy(delay = v) }
    }

    fun overlay(active: Boolean, locked: Boolean = state.value.locked) {
        mutable.update { it.copy(overlay = active, locked = locked) }
    }

    fun installLyrics(track: Track?, lyrics: Lyrics) {
        val t = track ?: state.value.playback.track ?: Track("导入的歌词")
        if (
            state.value.playback.track == null ||
                (track != null && track.key != state.value.playback.track?.key)
        )
            mutable.update {
                it.copy(demo = false, browsing = true, playback = Playback(t, "本地导入"))
            }
        val token = ++generation
        loadJob?.cancel()
        pendingLyrics = null
        val local = lyrics.copy(source = Provider.LOCAL)
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    store.restore(
                        listOf(
                            Triple("lyrics", "${t.key}:LOCAL", lyricsJson(local).toString()),
                            Triple(
                                "selection",
                                t.key,
                                SongSelection(t, Provider.LOCAL, null, "record").json().toString(),
                            ),
                        )
                    ) {
                        token == generation
                    }
                }
                if (token == generation) {
                    prefs.edit().putString("source:${t.key}", "LOCAL").apply()
                    mutable.update {
                        it.copy(
                            lyrics = local,
                            binding = null,
                            source = Provider.LOCAL,
                            busy = false,
                            message = "已导入本地歌词",
                            matchNotice = "已记住本曲的本地歌词",
                            selectionMode = "record",
                            requestedSource = null,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (token == generation) message("导入未完成：${e.message}")
            }
        }
    }

    fun commentPreference(key: String) = prefs.getString("comments:$key", "").orEmpty()

    fun commentPreference(key: String, value: String) {
        prefs.edit().putString("comments:$key", value).apply()
    }

    fun trackPreferences(): JSONObject =
        JSONObject().apply {
            prefs.all.forEach { (k, v) ->
                if (k.startsWith("source:") || k.startsWith("delay:") || k.startsWith("comments:"))
                    put(k, v)
            }
        }

    fun restoreTrackPreferences(o: JSONObject) {
        val edit = prefs.edit()
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            require(k.length <= 200)
            when {
                k.startsWith("delay:") -> {
                    val v = o.getLong(k)
                    require(v in -10000L..10000L)
                    edit.putLong(k, v)
                }
                k.startsWith("source:") -> {
                    val v = o.getString(k)
                    Provider.valueOf(v)
                    edit.putString(k, v)
                }
                k.startsWith("comments:") -> {
                    val v = o.getString(k)
                    require(v in listOf("QQ", "NETEASE"))
                    edit.putString(k, v)
                }
                else -> error("未知逐曲配置")
            }
        }
        edit.apply()
    }

    fun saveCurrent() {
        val s = state.value
        val t = s.playback.track ?: return
        val l = s.lyrics ?: return
        scope.launch {
            withContext(Dispatchers.IO) {
                store.put(
                    "saved",
                    t.key,
                    JSONObject().put("track", trackJson(t)).put("lyrics", lyricsJson(l)).toString(),
                )
            }
            mutable.update { it.copy(saved = true, message = "已保存到本地歌词库") }
        }
    }

    fun applyTranslation(rows: List<Line>, hash: String, trackKey: String) {
        if (state.value.playback.track?.key == trackKey && state.value.lyrics?.hash == hash) {
            val l = state.value.lyrics!!.copy(lines = rows)
            mutable.update { it.copy(lyrics = l) }
            scope.launch(Dispatchers.IO) {
                if (
                    state.value.playback.track?.key == trackKey &&
                        state.value.lyrics?.hash == hash &&
                        state.value.source == l.source
                )
                    store.put("lyrics", trackKey + ":" + l.source.name, lyricsJson(l).toString())
            }
        }
    }

    suspend fun translate(): List<Line> = translationLock.withLock {
        val s = state.value
        val l = s.lyrics ?: error("暂无歌词")
        val p = s.prefs
        require(
            p.consent &&
                p.translationUrl.startsWith("https://") &&
                p.model.isNotBlank() &&
                secrets.read().isNotBlank()
        ) {
            "请先配置翻译服务，并确认发送歌词及可能产生的费用"
        }
        val result = l.lines.toMutableList()
        val batches = mutableListOf<List<Int>>()
        var batch = mutableListOf<Int>()
        var size = 0
        for (index in
            l.lines.indices.filter {
                l.lines[it].translation.isBlank() && needsChineseTranslation(l.lines[it].text)
            }) {
            val length = l.lines[index].text.length
            require(length <= 4000) { "单行超过 4000 字符，暂不自动翻译" }
            if (size + length > 4000 || batch.size >= 30) {
                batches += batch
                batch = mutableListOf()
                size = 0
            }
            batch += index
            size += length
        }
        if (batch.isNotEmpty()) batches += batch
        for (batch in batches) {
            val day =
                java.text
                    .SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT)
                    .format(java.util.Date())
            val count = prefs.getInt("translation-chars:$day", 0)
            val chars = batch.sumOf { l.lines[it].text.length }
            val calls = prefs.getInt("translation-calls:$day", 0)
            // Usage statistics only: manual and automatic translation have no daily app quota.
            prefs
                .edit()
                .putInt("translation-chars:$day", count + chars)
                .putInt("translation-calls:$day", calls + 1)
                .apply()
            val body = TranslationProtocol.request(p.translationUrl, p.model, batch, l.lines)
            val response =
                api.request(
                    p.translationUrl.trimEnd('/') + "/chat/completions",
                    body,
                    secrets.read(),
                    readTimeoutMs = 45000,
                )
            val decoded = TranslationProtocol.decode(response, batch, l.lines, p.model)
            for (id in batch) result[id] = decoded[id]
        }
        result
    }
}
