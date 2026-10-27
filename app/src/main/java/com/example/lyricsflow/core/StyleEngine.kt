package com.example.lyricsflow.core

import org.json.JSONObject

/** A finite timeline evaluator. No expressions, reflection, scripting, URLs or dynamic code. */
data class TextStyleSpec(val size: Float, val weight: Int, val color: Long?)

data class Motion(
    val opacity: Float = 1f,
    val scale: Float = 1f,
    val rotation: Float = 0f,
    val x: Float = 0f,
    val y: Float = 0f,
)

data class ThemeSpec(
    val original: TextStyleSpec,
    val translation: TextStyleSpec,
    val romanization: TextStyleSpec,
    val font: String,
    val align: String,
    val padding: Float,
    val gap: Float,
    val anchor: Float,
    val inactive: Float,
    val backgroundType: String,
    val backgroundColor: Long?,
    val dim: Float,
    val duration: Int,
    val vertical: String,
    val maxGroups: Int,
    val animation: JSONObject,
) {
    fun motion(elapsed: Long, length: Long): Motion {
        var motion = Motion()
        val easing = animation.optString("easing", "easeInOut")
        val tracks = buildList {
            animation.optJSONObject("enter")?.let { add(it) }
            animation.optJSONArray("tracks")?.let { a ->
                for (i in 0 until a.length()) add(a.getJSONObject(i))
            }
        }
        for (track in tracks) {
            val frames = track.optJSONArray("keyframes") ?: continue
            if (frames.length() == 0) continue
            val unit = if (frames.getJSONObject(0).has("atMs")) "atMs" else "atProgress"
            val pos =
                if (unit == "atMs") elapsed.toDouble()
                else if (length > 0) elapsed.toDouble() / length else 0.0
            var left = frames.getJSONObject(0)
            var right = left
            for (i in 0 until frames.length()) {
                val f = frames.getJSONObject(i)
                if (f.getDouble(unit) <= pos) left = f
                right = f
                if (f.getDouble(unit) >= pos) break
            }
            val a = left.getDouble(unit)
            val b = right.getDouble(unit)
            var p = if (b <= a) 1.0 else ((pos - a) / (b - a)).coerceIn(0.0, 1.0)
            p =
                when (easing) {
                    "easeIn" -> p * p
                    "easeOut" -> 1 - (1 - p) * (1 - p)
                    "easeInOut" -> p * p * (3 - 2 * p)
                    else -> p
                }
            val value =
                (left.getDouble("value") + (right.getDouble("value") - left.getDouble("value")) * p)
                    .toFloat()
            motion =
                when (track.getString("property")) {
                    "opacity" -> motion.copy(opacity = value.coerceIn(0f, 1f))
                    "scale" -> motion.copy(scale = value.coerceIn(.5f, 2f))
                    "rotation" -> motion.copy(rotation = value.coerceIn(-180f, 180f))
                    "translationXRatio" -> motion.copy(x = value.coerceIn(-1f, 1f))
                    "translationYRatio" -> motion.copy(y = value.coerceIn(-1f, 1f))
                    else -> motion
                }
        }
        return motion
    }

    companion object {
        fun from(raw: String, target: String = "home"): ThemeSpec {
            val root = JSONObject(raw)
            fun merge(a: JSONObject, b: JSONObject): JSONObject {
                val out = JSONObject(a.toString())
                val keys = b.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = b.get(k)
                    val old = out.opt(k)
                    out.put(k, if (old is JSONObject && v is JSONObject) merge(old, v) else v)
                }
                return out
            }
            val o =
                root.optJSONObject("targetOverrides")?.optJSONObject(target)?.let {
                    merge(root, it)
                } ?: root
            val text = o.optJSONObject("text") ?: JSONObject()
            val layout = o.optJSONObject("layout") ?: JSONObject()
            val bg = o.optJSONObject("background") ?: JSONObject()
            val free = o.optJSONObject("freeLayout") ?: JSONObject()
            fun color(value: String): Long? =
                if (value.matches(Regex("#[0-9A-Fa-f]{8}"))) value.drop(1).toLong(16) else null
            fun style(name: String, size: Double, weight: Int): TextStyleSpec {
                val t = text.optJSONObject(name) ?: JSONObject()
                return TextStyleSpec(
                    t.optDouble("textSizeSp", size).toFloat(),
                    t.optInt("weight", weight),
                    color(t.optString("color")),
                )
            }
            return ThemeSpec(
                style("original", if (target == "overlay") 22.0 else 30.0, 700),
                style("translation", if (target == "overlay") 16.0 else 15.0, 500),
                style("romanization", 13.0, 400),
                text.optString("fontAlias", "sans-serif"),
                layout.optString("alignment", "start"),
                layout.optDouble("paddingDp", 0.0).toFloat(),
                layout.optDouble("lineGapDp", 18.0).toFloat(),
                layout.optDouble("activeAnchorY", .35).toFloat(),
                text.optDouble("inactiveOpacity", .32).toFloat(),
                bg.optString("type", "coverColor"),
                color(bg.optString("color", bg.optString("fallbackColor"))),
                bg.optDouble("dimAmount", .18).toFloat(),
                o.optJSONObject("animation")?.optInt("scrollDurationMs", 280) ?: 280,
                free.optString("orientation", "horizontal"),
                free.optInt("maxVisibleGroups", 3).coerceIn(1, 8),
                o.optJSONObject("animation") ?: JSONObject(),
            )
        }
    }
}
