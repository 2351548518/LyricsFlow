package com.example.lyricsflow.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindowProvider
import com.example.lyricsflow.core.Provider

/** One row at every width; large accessibility text can scroll rather than wrap or shrink. */
@Composable
internal fun FlowProviderRow(
    providers: List<Provider>,
    selected: Provider,
    onSelect: (Provider) -> Unit,
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val labels = providers.map {
        when (it) {
            Provider.QQ -> "QQ音乐"
            Provider.NETEASE -> "网易云"
            else -> it.label
        }
    }
    val minSlot =
        with(density) {
            labels
                .maxOfOrNull {
                    measurer
                        .measure(it, TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold))
                        .size
                        .width
                }
                ?.toDp() ?: 0.dp
        } + 12.dp
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val rowWidth =
            maxOf(maxWidth, minSlot * providers.size + 6.dp * (providers.size - 1).coerceAtLeast(0))
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            Row(
                Modifier.width(rowWidth).selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                providers.forEachIndexed { index, provider ->
                    val active = provider == selected
                    val colors = MaterialTheme.colorScheme
                    Surface(
                        modifier =
                            Modifier.weight(1f)
                                .heightIn(min = 44.dp)
                                .testTag("provider-choice-${provider.name}")
                                .selectable(
                                    active,
                                    role = Role.RadioButton,
                                    onClick = { onSelect(provider) },
                                ),
                        shape = RoundedCornerShape(14.dp),
                        color = if (active) colors.primary else colors.surfaceContainer,
                        contentColor = if (active) colors.onPrimary else colors.onSurface,
                        border =
                            BorderStroke(
                                if (active) 2.dp else 1.dp,
                                if (active) colors.primary else colors.outline,
                            ),
                    ) {
                        Box(
                            Modifier.fillMaxWidth().heightIn(min = 44.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                labels[index],
                                Modifier.padding(horizontal = 4.dp, vertical = 10.dp),
                                fontSize = 12.sp,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                softWrap = false,
                            )
                            if (active)
                                Glyph(
                                    "check",
                                    Modifier.align(Alignment.TopEnd)
                                        .padding(top = 3.dp, end = 3.dp)
                                        .size(9.dp)
                                        .testTag("selected-provider-${provider.name}"),
                                )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun FlowMatchActions(enabled: Boolean, autoMatch: () -> Unit, openMatch: () -> Unit) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val slot =
        with(density) {
            measurer.measure("重新自动匹配", TextStyle(fontSize = 13.sp)).size.width.toDp()
        } + 28.dp
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val width = maxOf(maxWidth, slot * 2 + 10.dp)
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            Row(Modifier.width(width), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FlowOutlinedButton(
                    autoMatch,
                    Modifier.weight(1f).testTag("source-rematch"),
                    enabled,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text("重新自动匹配", fontSize = 13.sp, maxLines = 1, softWrap = false)
                }
                FlowOutlinedButton(
                    openMatch,
                    Modifier.weight(1f).testTag("source-open-match"),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text("歌曲匹配", fontSize = 13.sp, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

@Composable
internal fun FlowSheetSystemBars() {
    val view = LocalView.current
    val light = MaterialTheme.colorScheme.surfaceContainerLow.luminance() > .18f
    SideEffect {
        // Material 3 1.4 initializes these flags only when its dialog is created.
        // Update the existing window when a cover changes, without reopening the sheet.
        (view.parent as? DialogWindowProvider)?.window?.let { window ->
            androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = light
                isAppearanceLightNavigationBars = light
            }
        }
    }
}

@Composable
internal fun FlowChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                label,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            )
        },
        leadingIcon =
            if (selected)
                ({ Glyph("check", Modifier.size(17.dp).testTag("selected-choice-$label")) })
            else null,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.heightIn(min = 44.dp).semantics { role = Role.RadioButton },
        colors =
            FilterChipDefaults.filterChipColors(
                containerColor = c.surfaceContainer,
                labelColor = c.onSurface,
                selectedContainerColor = c.primary,
                selectedLabelColor = c.onPrimary,
                selectedLeadingIconColor = c.onPrimary,
            ),
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) c.primary else c.outline),
    )
}

@Composable
internal fun FlowButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick,
        modifier.heightIn(min = 48.dp),
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        colors =
            ButtonDefaults.buttonColors(
                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        content = content,
    )
}

@Composable
internal fun FlowOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    val c = MaterialTheme.colorScheme
    OutlinedButton(
        onClick,
        modifier.heightIn(min = 48.dp),
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, if (enabled) c.outline else c.outlineVariant),
        colors =
            ButtonDefaults.outlinedButtonColors(
                containerColor = c.surfaceContainerLow,
                contentColor = c.onSurface,
                disabledContainerColor = c.surfaceContainerLow,
                disabledContentColor = c.onSurfaceVariant,
            ),
        contentPadding = contentPadding,
        content = content,
    )
}
