package dev.opencode.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.openCodeColor

private val ConnectionStatusDotSize = 6.dp
private val ConnectionStatusDotSpacing = 4.dp

enum class OpenCodeSessionConnectionStatus(
    val label: String,
) {
    Live("live"),
    Connecting("connecting"),
    Offline("offline"),
}

@Composable
fun OpenCodeConnectionStatusLabel(
    status: OpenCodeSessionConnectionStatus,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier.semantics {
                contentDescription = "Live updates ${status.label}"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ConnectionStatusDotSpacing),
    ) {
        Box(
            modifier =
                Modifier
                    .size(ConnectionStatusDotSize)
                    .background(status.dotColor(), CircleShape),
        )
        Text(
            text = status.label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun OpenCodeSessionConnectionStatus.dotColor(): Color =
    when (this) {
        OpenCodeSessionConnectionStatus.Live -> openCodeColor("success")
        OpenCodeSessionConnectionStatus.Connecting -> openCodeColor("warning")
        OpenCodeSessionConnectionStatus.Offline -> openCodeColor("error")
    }
