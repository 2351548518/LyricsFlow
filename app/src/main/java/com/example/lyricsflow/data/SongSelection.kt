package com.example.lyricsflow.data

import com.example.lyricsflow.core.*
import org.json.JSONObject

/** A confirmed per-song choice. 'auto' is explicit, so restoring it can undo a former pin. */
data class SongSelection(
    val track: Track,
    val source: Provider,
    val record: Track?,
    val mode: String,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val pinned
        get() = mode != "auto"

    fun json(): JSONObject =
        JSONObject()
            .put("version", 1)
            .put("track", trackJson(track))
            .put("source", source.name)
            .put("record", record?.let(::trackJson) ?: JSONObject.NULL)
            .put("mode", mode)
            .put("updatedAt", updatedAt)

    companion object {
        fun read(id: String, o: JSONObject): SongSelection {
            require(o.getInt("version") == 1)
            val track = readTrack(o.getJSONObject("track"))
            val source = Provider.valueOf(o.getString("source"))
            val rawRecord = o.optJSONObject("record")?.let(::readTrack)
            val mode = o.getString("mode")
            require(id == track.key && mode in listOf("auto", "source", "record")) { "歌曲选择数据不一致" }
            require(rawRecord == null || rawRecord.provider == source) { "歌词记录与来源不一致" }
            // Older cache-only bindings can lack an ID. Keep their provider preference without
            // presenting an unusable metadata stub as a confirmed network record.
            val record = rawRecord?.takeIf {
                source == Provider.LOCAL || it.sourceId.isNotBlank() || it.mid.isNotBlank()
            }
            require(mode != "record" || source == Provider.LOCAL || record != null) {
                "缺少选定歌词记录 ID"
            }
            val updated = o.getLong("updatedAt")
            require(updated >= 0)
            return SongSelection(track, source, record, mode, updated)
        }
    }
}

interface LyricsLookup {
    suspend fun searchExpanded(
        provider: Provider,
        query: String,
        current: Track? = null,
    ): List<Track>

    suspend fun lyrics(t: Track, refresh: Boolean = false): Lyrics
}

/** Selection, matching and refresh share cache rules; only AppGraph commits confirmed results. */
internal class SongLyrics(private val store: Store, private val api: LyricsLookup) {
    fun selection(track: Track): SongSelection? {
        store.get("selection", track.key)?.let {
            return SongSelection.read(track.key, JSONObject(it))
        }
        return null
    }

    fun legacy(track: Track, source: Provider): SongSelection? {
        val key = "${track.key}:${source.name}"
        val binding =
            store
                .get("binding", key)
                ?.let { readTrack(JSONObject(it)) }
                ?.takeIf {
                    it.provider == source &&
                        (source == Provider.LOCAL ||
                            it.sourceId.isNotBlank() ||
                            it.mid.isNotBlank())
                }
        val lyrics = store.get("lyrics", key)?.let { readLyrics(JSONObject(it)) }
        // Older releases saved a source before switching succeeded. Do not migrate a failed wish.
        if (lyrics?.source != source || (source != Provider.LOCAL && binding == null)) return null
        return SongSelection(track, source, binding, "source")
    }

    suspend fun lookup(
        current: Track,
        source: Provider,
        force: Boolean,
        exact: Boolean,
        record: Track?,
        rematch: Boolean = false,
    ): SourceLookup {
        val key = "${current.key}:${source.name}"
        val stored =
            store
                .get("binding", key)
                ?.let { readTrack(JSONObject(it)) }
                ?.takeIf {
                    it.provider == source &&
                        (source == Provider.LOCAL ||
                            it.sourceId.isNotBlank() ||
                            it.mid.isNotBlank())
                }
        val wanted = if (rematch) null else record ?: stored
        val sameRecord =
            record == null ||
                stored != null && SongSearch.identity(record) == SongSearch.identity(stored)
        val cached =
            store
                .get("lyrics", key)
                ?.let { readLyrics(JSONObject(it)) }
                ?.takeIf {
                    sameRecord && it.source == source && (it.lines.isNotEmpty() || it.instrumental)
                }
        fun unavailable(reason: String, candidates: Int = 0): SourceLookup =
            if (cached != null && !force && !rematch)
                SourceLookup(
                    ResolvedLyrics(wanted, cached, true, "已显示 ${source.label} 缓存，更新未完成：$reason")
                )
            else SourceLookup(reason = reason, candidates = candidates)
        if (source == Provider.LOCAL)
            return if (cached != null) SourceLookup(ResolvedLyrics(null, cached, true))
            else SourceLookup(reason = "本地歌词不存在，请重新导入")
        val staleQq =
            cached != null &&
                source == Provider.QQ &&
                cached.providerRevision < QqLyricCodec.REVISION
        val staleLrc =
            cached != null &&
                source == Provider.LRCLIB &&
                !exact &&
                cached.providerRevision < LrcLibClient.REVISION &&
                cached.lines.none { row -> row.text.any { it in '\u3040'..'\u30ff' } }
        if (cached != null && !force && !rematch && !staleQq && !staleLrc)
            return SourceLookup(ResolvedLyrics(wanted, cached, true))
        try {
            val decision =
                if (wanted == null || staleLrc)
                    SongSearch.decide(
                        current,
                        api.searchExpanded(source, "${current.title} ${current.artist}", current),
                    )
                else null
            val candidate =
                (if (staleLrc) null else wanted)
                    ?: decision?.track
                    ?: return unavailable(decision?.reason ?: "没有歌曲候选", decision?.candidates ?: 0)
            val fetched = api.lyrics(candidate, force)
            if (fetched.source != source) return SourceLookup(reason = "接口返回的歌词来源不一致")
            if (
                source == Provider.AMLL &&
                    current.duration > 0 &&
                    (fetched.lines.maxOfOrNull { it.end ?: it.start ?: 0 } ?: 0) >
                        current.duration + 5000
            )
                return SourceLookup(reason = "AMLL 时间轴超过当前歌曲时长，需手动核对版本")
            if (fetched.lines.isEmpty() && !fetched.instrumental)
                return SourceLookup(reason = "匹配到歌曲，但该记录没有歌词")
            val old =
                if (
                    stored != null &&
                        (staleLrc || SongSearch.identity(stored) == SongSearch.identity(candidate))
                )
                    cached?.lines.orEmpty().associateBy { it.start to it.text }
                else emptyMap()
            val merged =
                fetched.copy(
                    lines =
                        fetched.lines.map { row ->
                            old[row.start to row.text]
                                ?.takeIf {
                                    row.translation.isBlank() && it.translation.isNotBlank()
                                }
                                ?.let {
                                    row.copy(
                                        translation = it.translation,
                                        translationKind = it.translationKind,
                                        translationModel = it.translationModel,
                                    )
                                } ?: row
                        }
                )
            return SourceLookup(ResolvedLyrics(candidate, merged))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (cached != null && !force && !rematch)
                return SourceLookup(
                    ResolvedLyrics(
                        wanted,
                        cached,
                        true,
                        "已显示 ${source.label} 缓存，更新未完成：${e.message.orEmpty()}",
                    )
                )
            throw e
        }
    }
}
