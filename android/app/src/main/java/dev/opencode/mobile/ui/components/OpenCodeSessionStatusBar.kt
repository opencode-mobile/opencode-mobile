package dev.opencode.mobile.ui.components

import android.content.ClipData
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
private val SessionStatusDotSize = 6.dp
private val SessionStatusContextRingSize = 14.dp
private val SessionStatusConnectionShape = RoundedCornerShape(11.dp)

enum class OpenCodeSessionConnectionStatus(
    val label: String,
) {
    Live("live"),
    Connecting("connecting"),
    Offline("offline"),
}

@Composable
fun OpenCodeSessionStatusBar(
    connectionStatus: OpenCodeSessionConnectionStatus,
    contextUsageFraction: Float?,
    onConnectionClick: () -> Unit,
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
            onClick = onConnectionClick,
            modifier =
                Modifier
                    .height(SessionStatusTouchSize)
                    .semantics { contentDescription = "Live updates ${connectionStatus.label}" },
            shape = SessionStatusConnectionShape,
            color = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = connectionStatus.label,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                )
                Box(
                    modifier =
                        Modifier
                            .size(SessionStatusDotSize)
                            .align(Alignment.CenterVertically)
                            .background(connectionStatus.dotColor(), CircleShape),
                )
            }
        }

        Surface(
            onClick = onContextClick,
            modifier =
                Modifier
                    .padding(start = 6.dp)
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

fun copySessionIdToClipboard(
    context: Context,
    sessionId: String,
) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Session ID", sessionId))
}

@Composable
private fun OpenCodeSessionConnectionStatus.dotColor(): Color =
    when (this) {
        OpenCodeSessionConnectionStatus.Live -> openCodeColor("success")
        OpenCodeSessionConnectionStatus.Connecting -> openCodeColor("warning")
        OpenCodeSessionConnectionStatus.Offline -> openCodeColor("error")
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

@Preview(showBackground = true)
@Composable
private fun OpenCodeSessionStatusBarPreview() {
    OpenCodeTheme(darkTheme = false) {
        OpenCodeSessionStatusBar(
            connectionStatus = OpenCodeSessionConnectionStatus.Live,
            contextUsageFraction = 0.42f,
            onConnectionClick = {},
            onContextClick = {},
        )
    }
}
