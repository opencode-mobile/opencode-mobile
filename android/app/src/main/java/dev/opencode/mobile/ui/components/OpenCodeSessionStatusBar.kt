package dev.opencode.mobile.ui.components

import android.content.ClipData
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor

private val SessionStatusBarHeight = 36.dp
private val SessionStatusTouchSize = 22.dp
private val SessionStatusContextRingSize = 14.dp

@Composable
fun OpenCodeSessionStatusBar(
    contextUsageFraction: Float?,
    onContextClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(SessionStatusBarHeight)
                .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            onClick = onContextClick,
            modifier =
                Modifier
                    .size(SessionStatusTouchSize)
                    .semantics { contentDescription = contextUsageDescription(contextUsageFraction) },
            shape = CircleShape,
            color = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.primary,
        ) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { contextUsageFraction ?: 0f },
                    modifier = Modifier.size(SessionStatusContextRingSize),
                    color = contextUsageColor(contextUsageFraction),
                    trackColor = openCodeColor("icon-base"),
                    strokeWidth = 1.5.dp,
                )
            }
        }
    }
}

@Composable
private fun contextUsageColor(fraction: Float?): Color =
    when {
        fraction == null -> Color.Transparent
        fraction >= 0.85f -> openCodeColor("error")
        fraction >= 0.7f -> openCodeColor("warning")
        else -> openCodeColor("icon-strong-base")
    }

private fun contextUsageDescription(fraction: Float?): String =
    fraction?.let { "Context usage ${(it * 100).toInt()} percent" }
        ?: "Context usage unavailable"

fun copySessionIdToClipboard(
    context: Context,
    sessionId: String,
) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Session ID", sessionId))
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeSessionStatusBarPreview() {
    OpenCodeTheme(darkTheme = false) {
        OpenCodeSessionStatusBar(
            contextUsageFraction = 0.42f,
            onContextClick = {},
        )
    }
}
