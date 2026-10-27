package com.example.lyricsflow.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.lyricsflow.core.*

private val overlaySwatches =
    listOf(
        "白色" to "#FFFFFF",
        "黑色" to "#000000",
        "暖金" to "#FFD479",
        "薄荷" to "#A8E6CF",
        "天蓝" to "#A8D8FF",
        "粉色" to "#FFC1D6",
    )

@Composable
private fun ColorPreview(color: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf("浅色背景" to Color(0xfff2f1ed), "深色背景" to Color(0xff24292e)).forEach {
            (name, background) ->
            Column(Modifier.weight(1f)) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = background,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(
                            "流动的歌词",
                            color = Color(color),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "中文译文",
                            color = Color(color),
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 5.dp),
                        )
                    }
                }
                Text(
                    name,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 5.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun OverlayColorSettings(s: ScreenState, graph: AppGraph) {
    var showPicker by remember { mutableStateOf(false) }
    val themeColor =
        remember(s.overlayStyleRaw) {
            s.overlayStyleRaw
                .takeIf { it.isNotBlank() }
                ?.let { runCatching { ThemeSpec.from(it, "overlay").original.color }.getOrNull() }
        }
    val color = OverlayColors.resolve(s.prefs.overlayColor, themeColor)
    Column(
        Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("悬浮歌词颜色", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "原文和中文译文使用相同颜色，修改后立即生效。",
            fontSize = 12.sp,
            lineHeight = 19.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FlowChoiceChip("跟随主题", s.prefs.overlayColor.isBlank()) {
                graph.preferences(s.prefs.copy(overlayColor = ""))
            }
            overlaySwatches.forEach { (label, hex) ->
                FlowChoiceChip(label, s.prefs.overlayColor == hex) {
                    graph.preferences(s.prefs.copy(overlayColor = hex))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.size(24.dp)
                    .background(Color(color), CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            )
            Text(
                if (s.prefs.overlayColor.isBlank()) "跟随主题 · 未设置主题色时为白色"
                else "当前颜色 ${s.prefs.overlayColor}",
                fontSize = 12.sp,
            )
        }
        FlowOutlinedButton(onClick = { showPicker = true }) { Text("自定义颜色") }
        ColorPreview(color)
    }
    if (showPicker) {
        var draft by remember { mutableStateOf("#%06X".format(color and 0xffffff)) }
        val parsed = OverlayColors.normalize(draft)?.takeIf { it.isNotBlank() }
        val preview = parsed?.let { OverlayColors.resolve(it) } ?: color
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text("自定义悬浮歌词颜色") },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ColorPreview(preview)
                    OutlinedTextField(
                        draft,
                        { draft = it },
                        Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("颜色值 #RRGGBB") },
                        isError = parsed == null,
                        supportingText = {
                            Text(if (parsed == null) "请输入 6 位十六进制颜色，例如 #FFFFFF" else "也可拖动下方滑块调整颜色")
                        },
                    )
                    listOf("红" to 16, "绿" to 8, "蓝" to 0).forEach { (name, shift) ->
                        val value = preview ushr shift and 255
                        Text("$name · $value", fontSize = 13.sp)
                        Slider(
                            value.toFloat(),
                            onValueChange = { next ->
                                val rgb =
                                    (preview and 0xffffff and (255 shl shift).inv()) or
                                        (next.toInt().coerceIn(0, 255) shl shift)
                                draft = "#%06X".format(rgb)
                            },
                            valueRange = 0f..255f,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = parsed != null,
                    onClick = {
                        parsed?.let { graph.preferences(s.prefs.copy(overlayColor = it)) }
                        showPicker = false
                    },
                ) {
                    Text("应用颜色")
                }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text("取消") } },
        )
    }
}
