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

class AppGraph(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val store = Store(app)
    val secrets = Secrets(app)
    val api = MusicApi(store)
    private val prefs = app.getSharedPreferences("settings", 0)
    private val mutable = MutableStateFlow(ScreenState(prefs = readPrefs()))
    val state = mutable.asStateFlow()
    private var loadJob: Job? = null
    private var generation = 0L
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
            prefs.getFloat("overlayAlpha", .85f),
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
            .putString("url", p.translationUrl)
            .putString("model", p.model)
            .putBoolean("consent", p.consent)
            .putString("gateway", p.gateway)
            .putString("playbackApp", p.playbackApp)
            .putString("background", p.background)
            .putFloat("coverBlur", p.coverBlur)
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
        } else if (durationArrived) loadAutomatic(p.track!!)
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
        mutable.update {
            it.copy(
                lyrics = null,
                binding = null,
                busy = false,
                delay = 0,
                message = "",
                matchNotice = "",
                saved = false,
            )
        }
    }

    fun selectSource(p: Provider) {
        require(p in LYRIC_PROVIDERS)
        prefs.edit().putString("lyricPriority", p.name).apply()
        mutable.update { it.copy(source = p) }
        state.value.playback.track?.let {
            prefs.edit().putString("source:${it.key}", p.name).apply()
            loadAutomatic(it, force = true)
        }
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
        val track = state.value.playback.track ?: t
        val token = ++generation
        loadJob?.cancel()
        prefs.edit().putString("source:${track.key}", t.provider.name).apply()
        mutable.update {
            it.copy(
                binding = t,
                source = t.provider,
                busy = true,
                matchNotice = "",
                message = "正在获取 ${t.provider.label} 歌词…",
            )
        }
        loadJob = scope.launch {
            try {
                val lyrics = api.lyrics(t)
                withContext(Dispatchers.IO) {
                    store.put("binding", track.key + ":" + t.provider.name, trackJson(t).toString())
                    store.put(
                        "lyrics",
                        track.key + ":" + t.provider.name,
                        lyricsJson(lyrics).toString(),
                    )
                }
                if (token == generation) {
                    mutable.update {
                        it.copy(
                            lyrics = lyrics,
                            binding = t,
                            busy = false,
                            message =
                                if (lyrics.lines.isEmpty())
                                    if (lyrics.instrumental) "纯音乐，无歌词" else "该歌曲暂无歌词"
                                else "",
                            delay = offset(track, t.provider),
                        )
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (token == generation)
                    mutable.update { it.copy(busy = false, message = e.message ?: "加载失败") }
            }
        }
    }

    private fun loadAutomatic(track: Track, force: Boolean = false) {
        val token = ++generation
        loadJob?.cancel()
        val preferred = state.value.source
        val fallback = state.value.prefs.autoSources
        mutable.update {
            it.copy(busy = true, message = "正在匹配 ${preferred.label}…", matchNotice = "")
        }
        loadJob = scope.launch {
            try {
                // Wait briefly for the player's title/artist/album/duration update burst to settle.
                kotlinx.coroutines.delay(300)
                val current = state.value.playback.track?.takeIf { it.key == track.key } ?: track
                val result =
                    withTimeout(30000) {
                        AutomaticLyrics.resolve(
                            preferred,
                            fallback,
                            { source, index, total ->
                                if (token == generation)
                                    mutable.update {
                                        it.copy(message = "正在匹配 ${source.label}（$index/$total）…")
                                    }
                            },
                        ) { source ->
                            val binding =
                                withContext(Dispatchers.IO) {
                                    store.get("binding", track.key + ":" + source.name)?.let {
                                        readTrack(JSONObject(it))
                                    }
                                }
                            val cached =
                                withContext(Dispatchers.IO) {
                                    store.get("lyrics", track.key + ":" + source.name)?.let {
                                        readLyrics(JSONObject(it))
                                    }
                                }
                            if (token != generation) throw CancellationException("Track changed")
                            val staleQq =
                                cached?.source == Provider.QQ &&
                                    cached.providerRevision < QqLyricCodec.REVISION
                            if (cached != null && !force && !staleQq) {
                                return@resolve SourceLookup(ResolvedLyrics(binding, cached, true))
                            }
                            if (cached != null && staleQq && source == preferred)
                                mutable.update {
                                    it.copy(
                                        binding = binding,
                                        lyrics = cached,
                                        delay = offset(track, source),
                                        message = "正在更新 QQ 歌词与译文…",
                                    )
                                }
                            val decision =
                                if (binding == null)
                                    SongSearch.decide(
                                        current,
                                        api.searchExpanded(
                                            source,
                                            "${current.title} ${current.artist}",
                                            current,
                                        ),
                                    )
                                else null
                            val t = binding ?: decision?.track
                            if (token != generation) throw CancellationException("Track changed")
                            if (t == null) {
                                return@resolve SourceLookup(
                                    reason = decision?.reason ?: "没有歌曲候选",
                                    candidates = decision?.candidates ?: 0,
                                )
                            }
                            val fetched = api.lyrics(t, force)
                            if (token != generation) throw CancellationException("Track changed")
                            if (fetched.lines.isEmpty() && !fetched.instrumental)
                                return@resolve SourceLookup(reason = "匹配到歌曲，但该来源没有歌词")
                            // A provider upgrade must not discard an existing machine/user
                            // translation for an
                            // unchanged line.
                            val oldRows =
                                cached?.lines.orEmpty().associateBy { it.start to it.text }
                            val l =
                                fetched.copy(
                                    lines =
                                        fetched.lines.map { row ->
                                            val old = oldRows[row.start to row.text]
                                            if (
                                                row.translation.isBlank() &&
                                                    old != null &&
                                                    old.translation.isNotBlank()
                                            )
                                                row.copy(
                                                    translation = old.translation,
                                                    translationKind = old.translationKind,
                                                    translationModel = old.translationModel,
                                                )
                                            else row
                                        }
                                )
                            withContext(Dispatchers.IO) {
                                store.put(
                                    "binding",
                                    track.key + ":" + source.name,
                                    trackJson(t).toString(),
                                )
                                store.put(
                                    "lyrics",
                                    track.key + ":" + source.name,
                                    lyricsJson(l).toString(),
                                )
                            }
                            SourceLookup(ResolvedLyrics(t, l))
                        }
                    }
                if (token != generation) return@launch
                val found = result.resolved
                if (found == null)
                    mutable.update {
                        it.copy(
                            busy = false,
                            message = result.failureMessage(),
                            matchNotice = if (it.lyrics != null) "更新未完成，保留本地歌词" else "",
                        )
                    }
                else {
                    val actual = found.lyrics.source
                    mutable.update {
                        it.copy(
                            binding = found.binding,
                            lyrics = found.lyrics,
                            source = actual,
                            busy = false,
                            delay = offset(track, actual),
                            message = if (found.cached) "已载入本地歌词" else "",
                            matchNotice =
                                if (actual != preferred)
                                    "${preferred.label} 未取得可用匹配，已自动使用 ${actual.label}"
                                else "",
                        )
                    }
                }
            } catch (e: Exception) {
                if (e is TimeoutCancellationException) {
                    if (token == generation)
                        mutable.update { it.copy(busy = false, message = "匹配超时，可重试或搜索歌曲候选") }
                    return@launch
                }
                if (e is CancellationException) throw e
                if (token == generation)
                    mutable.update { it.copy(busy = false, message = e.message ?: "加载失败") }
            }
        }
    }

    fun refresh() {
        state.value.playback.track?.let { loadAutomatic(it, true) }
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
        generation++
        loadJob?.cancel()
        prefs.edit().putString("source:${t.key}", "LOCAL").apply()
        mutable.update {
            it.copy(lyrics = lyrics, source = Provider.LOCAL, busy = false, message = "已导入本地歌词")
        }
        scope.launch(Dispatchers.IO) {
            store.put("lyrics", t.key + ":LOCAL", lyricsJson(lyrics).toString())
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
                store.put("lyrics", trackKey + ":" + state.value.source, lyricsJson(l).toString())
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
