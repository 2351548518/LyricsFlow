package com.example.lyricsflow.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

val LYRIC_PROVIDERS = listOf(Provider.QQ, Provider.NETEASE, Provider.AMLL, Provider.LRCLIB)

data class SourceAttempt(val source: Provider, val reason: String, val candidates: Int = 0)

data class ResolvedLyrics(val binding: Track?, val lyrics: Lyrics, val cached: Boolean = false)

data class SourceLookup(
    val resolved: ResolvedLyrics? = null,
    val reason: String = "",
    val candidates: Int = 0,
)

data class LyricsResolution(val resolved: ResolvedLyrics?, val attempts: List<SourceAttempt>) {
    fun failureMessage(): String =
        "暂时没有可自动使用的歌词\n" +
            attempts.joinToString("\n") {
                "${it.source.label}：${it.reason}" +
                    if (it.candidates > 0) "（${it.candidates} 个候选）" else ""
            }
}

object AutomaticLyrics {
    fun order(preferred: Provider, fallback: Boolean): List<Provider> =
        if (fallback) LYRIC_PROVIDERS else listOf(preferred).filter { it in LYRIC_PROVIDERS }

    suspend fun resolve(
        preferred: Provider,
        fallback: Boolean,
        progress: (Provider, Int, Int) -> Unit = { _, _, _ -> },
        sourceTimeoutMs: Long = 12000,
        lookup: suspend (Provider) -> SourceLookup,
    ): LyricsResolution {
        val sources = order(preferred, fallback)
        val attempts = mutableListOf<SourceAttempt>()
        var plain: ResolvedLyrics? = null
        for ((index, source) in sources.withIndex()) {
            progress(source, index + 1, sources.size)
            val result =
                try {
                    // The first AMLL lookup downloads its public metadata index (~1.65 MB).
                    val budget =
                        if (source == Provider.AMLL && sourceTimeoutMs == 12000L) 16000L
                        else sourceTimeoutMs
                    withTimeoutOrNull(budget) { lookup(source) } ?: SourceLookup(reason = "该来源查询超时")
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    SourceLookup(reason = "暂不可用：${e.message ?: "连接失败"}")
                }
            val found = result.resolved
            if (
                found != null &&
                    (found.lyrics.instrumental || found.lyrics.lines.any { it.start != null })
            )
                return LyricsResolution(found, attempts)
            if (found != null && found.lyrics.lines.isNotEmpty() && plain == null) plain = found
            attempts +=
                SourceAttempt(
                    source,
                    when {
                        found == null -> result.reason
                        found.lyrics.lines.isEmpty() -> "该来源暂无歌词"
                        else -> "仅有无时间轴歌词"
                    },
                    result.candidates,
                )
        }
        return LyricsResolution(plain, attempts)
    }
}
