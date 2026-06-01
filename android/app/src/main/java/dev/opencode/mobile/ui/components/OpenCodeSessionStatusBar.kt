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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.ui.icons.OpenCodeIcons

private const val VisibleSessionIdCharacters = 9
private val SessionStatusBarHeight = 36.dp
private val SessionStatusTouchSize = 22.dp
private val SessionStatusIconSize = 13.dp
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
    sessionId: String,
    connectionStatus: OpenCodeSessionConnectionStatus,
    contextUsageFraction: Float?,
    onCopySessionId: () -> Unit,
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
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Session: ${sessionId.takeLast(VisibleSessionIdCharacters)}",
                modifier = Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
            Surface(
                onClick = onCopySessionId,
                modifier =
                    Modifier
                        .padding(start = 4.dp)
                        .size(SessionStatusTouchSize)
                        .semantics { contentDescription = "Copy session ID" },
                shape = CircleShape,
                color = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = OpenCodeIcons.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(SessionStatusIconSize),
                    )
                }
            }
        }

        Surface(
            onClick = onConnectionClick,
            modifier =
                Modifier
                    .padding(start = 10.dp)
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
                    color = if (contextUsageFraction == null) Color.Transparent else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.outlineVariant,
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
        OpenCodeSessionConnectionStatus.Live -> MaterialTheme.colorScheme.primary
        OpenCodeSessionConnectionStatus.Connecting -> MaterialTheme.colorScheme.tertiary
        OpenCodeSessionConnectionStatus.Offline -> MaterialTheme.colorScheme.outline
    }

private fun contextUsageDescription(fraction: Float?): String =
    fraction?.let { "Context usage ${(it * 100).toInt()} percent" }
        ?: "Context usage unavailable"

@Preview(showBackground = true)
@Composable
private fun OpenCodeSessionStatusBarPreview() {
    val context = LocalContext.current
    OpenCodeTheme(darkTheme = false) {
        OpenCodeSessionStatusBar(
            sessionId = "ses_01HZX9TvS3EfE4",
            connectionStatus = OpenCodeSessionConnectionStatus.Live,
            contextUsageFraction = 0.42f,
            onCopySessionId = { copySessionIdToClipboard(context, "ses_01HZX9TvS3EfE4") },
            onConnectionClick = {},
            onContextClick = {},
        )
    }
}
