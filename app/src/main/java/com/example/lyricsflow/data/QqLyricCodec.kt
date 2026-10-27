package com.example.lyricsflow.data

import android.util.Base64
import com.example.lyricsflow.core.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import org.json.JSONObject

object QqLyricCodec {
    const val REVISION = 2

    fun decodeField(value: String): String {
        val raw = value.trim()
        if (raw.isBlank() || raw == "null") return ""
        if (raw.matches(Regex("[0-9a-fA-F]{32,}"))) return "" // unsupported encrypted payload
        val decoded =
            if (
                raw.startsWith("[") ||
                    raw.startsWith("<") ||
                    !raw.matches(Regex("[A-Za-z0-9+/=\\s]+"))
            )
                raw
            else
                runCatching {
                    val bytes = Base64.decode(raw, Base64.DEFAULT)
                    Charsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes))
                        .toString()
                }
                    .getOrElse {
                        return ""
                    }
        if (
            decoded.any { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' } ||
                decoded.contains('\uFFFD')
        )
            return ""
        return Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos);").replace(decoded) { m ->
            when (val entity = m.groupValues[1]) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                else -> {
                    val code =
                        if (entity.startsWith("#x")) entity.drop(2).toIntOrNull(16)
                        else entity.drop(1).toIntOrNull()
                    if (code != null && Character.isValidCodePoint(code) && code !in 0xD800..0xDFFF)
                        String(Character.toChars(code))
                    else m.value
                }
            }
        }
    }

    fun lyrics(data: JSONObject, duration: Long, revision: Int = REVISION): Lyrics {
        val original = decodeField(data.optString("lyric"))
        val rows = LyricParser.lrc(original, duration)
        return Lyrics(
            LyricParser.merge(rows, decodeField(data.optString("trans")), "", duration),
            Provider.QQ,
            providerRevision = revision,
        )
    }
}
