package com.example.lyricsflow.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Restricted JSON Schema evaluator for the bundled contract; references cannot leave this document.
 */
object StyleSchema {
    private var root: JSONObject? = null

    fun load(raw: String) {
        root = JSONObject(raw)
    }

    fun validate(value: JSONObject) {
        root?.let { schema -> check(value, schema, schema, "$", 0) }
    }

    private fun check(value: Any?, s: JSONObject, root: JSONObject, path: String, depth: Int) {
        require(depth < 40) { "$path: 主题嵌套过深" }
        s.optString("\$ref")
            .takeIf { it.isNotBlank() }
            ?.let { ref ->
                require(ref.startsWith("#/"))
                var target = root
                ref.drop(2).split('/').forEach { target = target.getJSONObject(it) }
                check(value, target, root, path, depth + 1)
                return
            }
        if (s.has("const")) require(value == s.get("const")) { "$path: 字段值不符合版本契约" }
        s.optJSONArray("enum")?.let { a ->
            require((0 until a.length()).any { a.get(it) == value }) { "$path: 不支持的枚举值" }
        }
        val type = s.optString("type")
        require(
            when (type) {
                "object" -> value is JSONObject
                "array" -> value is JSONArray
                "string" -> value is String
                "number" -> value is Number && value.toDouble().isFinite()
                "integer" ->
                    value is Number && value.toDouble().isFinite() && value.toDouble() % 1.0 == 0.0
                "boolean" -> value is Boolean
                else -> true
            }
        ) {
            "$path: 应为 $type"
        }
        if (value is JSONObject) {
            s.optJSONArray("required")?.let { a ->
                for (i in 0 until a.length()) require(value.has(a.getString(i))) {
                    "$path.${a.getString(i)}: 缺少必填字段"
                }
            }
            val properties = s.optJSONObject("properties") ?: JSONObject()
            val keys = value.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val child = properties.optJSONObject(k)
                if (child != null) check(value.get(k), child, root, "$path.$k", depth + 1)
                else if (s.opt("additionalProperties") == false) error("$path.$k: 未知字段")
            }
        }
        if (value is JSONArray) {
            require(
                value.length() >= s.optInt("minItems", 0) &&
                    value.length() <= s.optInt("maxItems", Int.MAX_VALUE)
            ) {
                "$path: 数组长度越界"
            }
            if (s.optBoolean("uniqueItems"))
                require(
                    (0 until value.length()).map { value.get(it).toString() }.distinct().size ==
                        value.length()
                ) {
                    "$path: 数组包含重复项"
                }
            s.optJSONObject("items")?.let { item ->
                for (i in 0 until value.length()) check(
                    value.get(i),
                    item,
                    root,
                    "$path[$i]",
                    depth + 1,
                )
            }
        }
        if (value is String) {
            require(
                value.length >= s.optInt("minLength", 0) &&
                    value.length <= s.optInt("maxLength", Int.MAX_VALUE)
            ) {
                "$path: 文本长度越界"
            }
            s.optString("pattern")
                .takeIf { it.isNotBlank() }
                ?.let { require(Regex(it).containsMatchIn(value)) { "$path: 文本格式无效" } }
        }
        if (value is Number)
            require(
                value.toDouble() >= s.optDouble("minimum", -Double.MAX_VALUE) &&
                    value.toDouble() <= s.optDouble("maximum", Double.MAX_VALUE)
            ) {
                "$path: 数值越界"
            }
        s.optJSONObject("not")?.let {
            require(runCatching { check(value, it, root, path, depth + 1) }.isFailure) {
                "$path: 出现互斥字段"
            }
        }
        s.optJSONArray("oneOf")?.let { a ->
            require(
                (0 until a.length()).count {
                    runCatching { check(value, a.getJSONObject(it), root, path, depth + 1) }
                        .isSuccess
                } == 1
            ) {
                "$path: 字段组合不唯一"
            }
        }
        s.optJSONArray("allOf")?.let { a ->
            for (i in 0 until a.length()) check(value, a.getJSONObject(i), root, path, depth + 1)
        }
        s.optJSONObject("if")?.let { condition ->
            if (runCatching { check(value, condition, root, path, depth + 1) }.isSuccess)
                s.optJSONObject("then")?.let { check(value, it, root, path, depth + 1) }
        }
    }
}
