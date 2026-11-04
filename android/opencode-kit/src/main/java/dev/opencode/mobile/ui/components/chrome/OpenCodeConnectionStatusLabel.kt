package dev.opencode.mobile.ui.components

import androidx.annotation.StringRes
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.kit.R
import dev.opencode.mobile.theme.openCodeColor

private val ConnectionStatusDotSize = 6.dp
private val ConnectionStatusDotSpacing = 4.dp

enum class OpenCodeSessionConnectionStatus(
    @param:StringRes val label: Int,
) {
    Live(R.string.live_updates_status_live),
    Connecting(R.string.live_updates_status_connecting),
    Offline(R.string.live_updates_status_offline),
    AuthFailed(R.string.live_updates_status_auth_failed),
}

/** "Live updates <status>", read by accessibility services. */
@Composable
internal fun OpenCodeSessionConnectionStatus.accessibilityLabel(): String =
    stringResource(R.string.live_updates_accessibility_label, stringResource(label))

@Composable
fun OpenCodeConnectionStatusLabel(
    status: OpenCodeSessionConnectionStatus,
    modifier: Modifier = Modifier,
) {
    val description = status.accessibilityLabel()
    Row(
        modifier =
            modifier.semantics {
                contentDescription = description
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
            text = stringResource(status.label),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The status dot alone, for places that name the status some other way (a top bar's subtitle). */
@Composable
internal fun OpenCodeConnectionStatusDot(
    status: OpenCodeSessionConnectionStatus,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .size(ConnectionStatusDotSize)
                .background(status.dotColor(), CircleShape),
    )
}

@Composable
internal fun OpenCodeSessionConnectionStatus.dotColor(): Color =
    when (this) {
        OpenCodeSessionConnectionStatus.Live -> openCodeColor("success")
        OpenCodeSessionConnectionStatus.Connecting -> openCodeColor("warning")
        OpenCodeSessionConnectionStatus.Offline,
        OpenCodeSessionConnectionStatus.AuthFailed,
        -> openCodeColor("error")
    }
