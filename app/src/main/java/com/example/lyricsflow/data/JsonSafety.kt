package com.example.lyricsflow.data

import org.json.JSONArray
import org.json.JSONObject

fun checkJsonDepth(raw: String, limit: Int = 32) {
    var depth = 0
    var quoted = false
    var escaped = false
    for (c in raw) {
        if (quoted) {
            if (escaped) escaped = false
            else if (c == '\\') escaped = true else if (c == '"') quoted = false
        } else
            when (c) {
                '"' -> quoted = true
                '[',
                '{' -> {
                    depth++
                    require(depth <= limit) { "JSON 嵌套层数过多" }
                }
                ']',
                '}' -> {
                    depth--
                    require(depth >= 0) { "JSON 括号无效" }
                }
            }
    }
    require(depth == 0 && !quoted) { "JSON 不完整" }
}

fun jsonObject(raw: String): JSONObject {
    checkJsonDepth(raw)
    return JSONObject(raw)
}

fun jsonArray(raw: String): JSONArray {
    checkJsonDepth(raw)
    return JSONArray(raw)
}
