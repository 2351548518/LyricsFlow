package com.example.lyricsflow.data

import com.example.lyricsflow.core.*
import java.io.*
import java.util.zip.*
import org.json.JSONArray
import org.json.JSONObject

data class StyleSpec(
    val name: String,
    val mode: String,
    val size: Float,
    val color: Long?,
    val alignment: String,
    val opacity: Float,
    val raw: String,
)

object StyleCodec {
    fun parse(raw: String): StyleSpec {
        require(raw.toByteArray().size <= 256000) { "主题超过 256 KB" }
        val o = jsonObject(raw)
        StyleSchema.validate(o)
        require(
            o.getString("format") == "lyricsflow.style" && o.getString("schemaVersion") == "1.0"
        ) {
            "不支持的主题格式或版本"
        }
        require(o.getString("id").matches(Regex("[A-Za-z0-9_-]{1,80}"))) { "主题 ID 无效" }
        require(o.getString("name").length in 1..80) { "主题名无效" }
        val targets = o.getJSONArray("targets")
        require(targets.length() in 1..2)
        for (i in 0 until targets.length()) require(
            targets.getString(i) in listOf("home", "overlay")
        )
        val mode = o.getString("mode")
        require(mode in listOf("classic", "free"))
        val text = o.optJSONObject("text")
        val original = text?.optJSONObject("original")
        val size = original?.optDouble("textSizeSp", 30.0)?.toFloat() ?: 30f
        require(size in 10f..96f)
        fun validate(node: Any?, key: String = "") {
            if (node is JSONObject) {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    require(
                        k !in
                            listOf("script", "javascript", "html", "url", "eval", "code", "onClick")
                    ) {
                        "主题不能包含可执行代码或远程资源"
                    }
                    validate(node.get(k), k)
                }
            }
            if (node is JSONArray) {
                require(node.length() <= 64)
                for (i in 0 until node.length()) validate(node.get(i), key)
            }
            if (node is Number) {
                val d = node.toDouble()
                require(d.isFinite())
                when (key) {
                    "textSizeSp" -> require(d in 10.0..96.0)
                    "weight" -> require(d in 100.0..900.0)
                    "paddingDp",
                    "lineGapDp" -> require(d in 0.0..96.0)
                    "inactiveOpacity",
                    "activeAnchorY",
                    "dimAmount",
                    "atProgress" -> require(d in 0.0..1.0)
                    "scrollDurationMs" -> require(d in 0.0..1000.0)
                }
            }
            if (key == "fontAlias") require(node in listOf("sans-serif", "serif", "monospace"))
            if (key == "type")
                require(node in listOf("coverColor", "cover", "customImage", "solid"))
            if (key == "easing") require(node in listOf("linear", "easeIn", "easeOut", "easeInOut"))
            if (key == "property")
                require(
                    node in
                        listOf(
                            "opacity",
                            "scale",
                            "rotation",
                            "translationXRatio",
                            "translationYRatio",
                        )
                )
            if (key == "tracks" && node is JSONArray) require(node.length() <= 8) { "最多 8 条动画轨道" }
            if (node is JSONObject && node.has("property")) {
                val property = node.getString("property")
                val frames = node.getJSONArray("keyframes")
                for (i in 0 until frames.length()) {
                    val v = frames.getJSONObject(i).getDouble("value")
                    require(v.isFinite())
                    require(
                        when (property) {
                            "opacity" -> v in 0.0..1.0
                            "scale" -> v in .5..2.0
                            "rotation" -> v in -180.0..180.0
                            else -> v in -1.0..1.0
                        }
                    ) {
                        "动画数值超出允许范围"
                    }
                }
            }
            if (key == "keyframes" && node is JSONArray) {
                require(node.length() in 1..16)
                var last = -1.0
                var unit = ""
                for (i in 0 until node.length()) {
                    val f = node.getJSONObject(i)
                    require(f.has("atMs") xor f.has("atProgress"))
                    val u = if (f.has("atMs")) "atMs" else "atProgress"
                    require(unit.isBlank() || unit == u)
                    unit = u
                    val t = f.getDouble(u)
                    require(t >= last && t >= 0 && t <= if (u == "atMs") 10000 else 1)
                    last = t
                }
            }
            if (key == "color" || key == "fallbackColor")
                require(node is String && node.matches(Regex("#[0-9A-Fa-f]{8}"))) {
                    "颜色必须为 #AARRGGBB"
                }
        }
        validate(o)
        if (mode == "free") {
            val f = o.getJSONObject("freeLayout")
            require(
                f.getString("seedMode") == "trackLineStyle" &&
                    f.getInt("maxVisibleGroups") in 1..8 &&
                    f.getString("collisionPolicy") == "avoidThenPartition"
            )
        }
        val alignment = o.optJSONObject("layout")?.optString("alignment", "start") ?: "start"
        require(alignment in listOf("start", "center", "end"))
        val color =
            original
                ?.optString("color")
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    require(it.matches(Regex("#[0-9A-Fa-f]{8}")))
                    it.drop(1).toLong(16)
                }
        return StyleSpec(
            o.getString("name"),
            mode,
            size,
            color,
            alignment,
            text?.optDouble("inactiveOpacity", .35)?.toFloat() ?: .35f,
            raw,
        )
    }

    fun preset(free: Boolean = false) =
        JSONObject()
            .put("format", "lyricsflow.style")
            .put("schemaVersion", "1.0")
            .put("id", if (free) "soft-free" else "soft-classic")
            .put("name", if (free) "流动诗页" else "经典双语")
            .put("targets", JSONArray().put("home").put("overlay"))
            .put("mode", if (free) "free" else "classic")
            .put("text", JSONObject().put("original", JSONObject().put("textSizeSp", 30)))
            .apply {
                if (free)
                    put(
                        "freeLayout",
                        JSONObject()
                            .put("seedMode", "trackLineStyle")
                            .put("maxVisibleGroups", 3)
                            .put("minTextSizeSp", 18)
                            .put("orientation", "mixed")
                            .put("collisionPolicy", "avoidThenPartition"),
                    )
            }
            .toString(2)
}

object Transfer {
    fun lyricPackage(t: Track, l: Lyrics, delay: Long) =
        lyricsJson(l)
            .put("format", "lyricsflow.lyrics")
            .put("schemaVersion", "1.0")
            .put("track", trackJson(t))
            .put(
                "source",
                JSONObject()
                    .put("providerId", l.source.name.lowercase())
                    .put("providerTrackId", t.sourceId.ifBlank { null })
                    .put("attribution", l.source.label),
            )
            .put("delayMs", delay)
            .toString(2)

    fun importLyrics(raw: String, duration: Long): Pair<Track?, Lyrics> {
        require(raw.toByteArray().size <= 2_000_000) { "歌词超过 2 MB" }
        if (!raw.trimStart().startsWith("{"))
            return null to Lyrics(LyricParser.parse(raw, duration), Provider.LOCAL)
        val o = jsonObject(raw)
        require(
            o.getString("format") == "lyricsflow.lyrics" && o.getString("schemaVersion") == "1.0"
        ) {
            "不支持的歌词包"
        }
        val track =
            o.optJSONObject("track")?.let {
                val a = it.optJSONArray("artists")
                Track(
                    it.getString("title"),
                    it.optString("artist").ifBlank {
                        if (a != null)
                            (0 until a.length()).joinToString(" / ") { n -> a.getString(n) }
                        else ""
                    },
                    it.optString("album"),
                    it.optLong("durationMs", it.optLong("duration")),
                )
            }
        return track to readLyrics(o).copy(source = Provider.LOCAL)
    }

    fun lrc(l: Lyrics, translation: Boolean = false): String =
        l.lines.joinToString("\n") { r ->
            val s = r.start
            val prefix =
                if (s == null) "" else "[%02d:%02d.%03d]".format(s / 60000, s / 1000 % 60, s % 1000)
            prefix + if (translation) r.translation else r.text
        }

    fun backup(graph: AppGraph, includeBackground: Boolean = true): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun add(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
            add(
                "manifest.json",
                JSONObject()
                    .put("format", "lyricsflow.backup")
                    .put("schemaVersion", "1.0")
                    .toString(),
            )
            val rows = JSONArray()
            var size = 0
            for (k in
                listOf("binding", "commentBinding", "lyrics", "saved", "style", "selection")) graph
                .store
                .all(k)
                .forEach { (id, body) ->
                    size += body.toByteArray().size
                    require(size <= 16_000_000 && rows.length() < 3000) {
                        "资料超过单份备份 16 MB／3000 项限制，请先导出部分歌词"
                    }
                    rows.put(JSONObject().put("kind", k).put("id", id).put("body", body))
                }
            add("data.json", rows.toString())
            val p = graph.state.value.prefs
            val settings =
                JSONObject()
                    .put("translation", p.translation)
                    .put("romanization", p.romanization)
                    .put("free", p.free)
                    .put("fontSize", p.fontSize)
                    .put("dark", p.dark)
                    .put("palette", p.palette)
                    .put("reduced", p.reduced)
                    .put("autoSources", p.autoSources)
                    .put("overlaySize", p.overlaySize)
                    .put("overlayAlpha", p.overlayAlpha)
                    .put("overlayTranslation", p.overlayTranslation)
                    .put("overlayColor", p.overlayColor)
                    .put("background", p.background)
                    .put("coverBlur", p.coverBlur)
                    .put("coverMesh", p.coverMesh)
                    .put("trackPreferences", graph.trackPreferences())
            val image = File(graph.app.filesDir, "background.jpg")
            if (includeBackground && image.exists())
                settings.put(
                    "backgroundImage",
                    android.util.Base64.encodeToString(
                        image.readBytes(),
                        android.util.Base64.NO_WRAP,
                    ),
                )
            add("settings.json", settings.toString())
        }
        return out.toByteArray()
    }

    fun validateBackup(bytes: ByteArray): Pair<List<Triple<String, String, String>>, JSONObject> {
        require(bytes.size <= 20_000_000) { "备份超过 20 MB" }
        val entries = mutableMapOf<String, String>()
        var total = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                require(
                    e.name in listOf("manifest.json", "data.json", "settings.json") &&
                        !entries.containsKey(e.name)
                ) {
                    "备份含未知、重复或不安全路径"
                }
                val b = zip.readBytesLimited(20_000_000)
                total += b.size
                require(total <= 20_000_000)
                entries[e.name] = String(b, Charsets.UTF_8)
            }
        }
        val manifest = jsonObject(entries["manifest.json"] ?: error("缺少 manifest"))
        require(
            manifest.getString("format") == "lyricsflow.backup" &&
                manifest.getString("schemaVersion") == "1.0"
        )
        val a = jsonArray(entries["data.json"] ?: error("缺少数据"))
        require(a.length() <= 3000)
        val rows =
            (0 until a.length()).map {
                val r = a.getJSONObject(it)
                val k = r.getString("kind")
                val id = r.getString("id")
                val b = r.getString("body")
                require(
                    k in
                        listOf(
                            "binding",
                            "commentBinding",
                            "lyrics",
                            "saved",
                            "style",
                            "selection",
                        ) && id.length <= 300
                )
                when (k) {
                    "selection" -> SongSelection.read(id, jsonObject(b))
                    "binding" -> readTrack(jsonObject(b))
                    "commentBinding" -> {
                        val record = readTrack(jsonObject(b))
                        require(record.provider in listOf(Provider.QQ, Provider.NETEASE))
                    }
                    "lyrics" -> readLyrics(jsonObject(b))
                    "saved" -> {
                        val o = jsonObject(b)
                        readTrack(o.getJSONObject("track"))
                        readLyrics(o.getJSONObject("lyrics"))
                    }
                    "style" -> StyleCodec.parse(b)
                }
                Triple(k, id, b)
            }
        val settings = jsonObject(entries["settings.json"] ?: "{}")
        if (settings.has("overlayColor"))
            require(OverlayColors.normalize(settings.getString("overlayColor")) != null) {
                "悬浮歌词颜色应为 #RRGGBB，或留空跟随主题"
            }
        settings.optJSONObject("trackPreferences")?.let { o ->
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                require(k.length <= 200)
                when {
                    k.startsWith("delay:") -> require(o.getLong(k) in -10000L..10000L)
                    k.startsWith("source:") -> Provider.valueOf(o.getString(k))
                    k.startsWith("comments:") -> require(o.getString(k) in listOf("QQ", "NETEASE"))
                    else -> error("未知逐曲配置")
                }
            }
        }
        if (settings.has("backgroundImage")) {
            val b =
                android.util.Base64.decode(
                    settings.getString("backgroundImage"),
                    android.util.Base64.DEFAULT,
                )
            require(b.size <= 10_000_000)
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size, opts)
            require(opts.outWidth in 1..1600 && opts.outHeight in 1..1600) { "备份图片无效" }
        }
        return rows to settings
    }
}
