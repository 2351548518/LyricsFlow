package com.example.lyricsflow.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.lyricsflow.core.Comment
import com.example.lyricsflow.core.graphemes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun FlowCommentCard(c: Comment) {
    Surface(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(38.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(graphemes(c.author).firstOrNull() ?: "听", fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.author, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    if (c.time > 0)
                        Text(
                            SimpleDateFormat("yyyy.MM.dd", Locale.CHINA).format(Date(c.time)),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                }
                Text(
                    "♡ ${c.likes}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                c.text,
                Modifier.padding(top = 14.dp, bottom = 4.dp),
                fontSize = 16.sp,
                lineHeight = 27.sp,
            )
        }
    }
}
