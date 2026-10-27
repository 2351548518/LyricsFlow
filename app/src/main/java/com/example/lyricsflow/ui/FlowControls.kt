package com.example.lyricsflow.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindowProvider

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
        content = content,
    )
}
