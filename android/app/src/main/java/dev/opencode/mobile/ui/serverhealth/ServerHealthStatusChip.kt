package dev.opencode.mobile.ui.serverhealth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.domain.serverhealth.ServerHealthConnectionState
import dev.opencode.mobile.theme.OpenCodePillShape
import dev.opencode.mobile.ui.components.OpenCodeChartTone
import dev.opencode.mobile.ui.components.OpenCodeListItemStatusTone
import dev.opencode.mobile.ui.components.OpenCodePulsingDot
import dev.opencode.mobile.ui.components.background
import dev.opencode.mobile.ui.components.foreground

internal fun ServerHealthConnectionState.statusTone(): OpenCodeListItemStatusTone =
    when (this) {
        ServerHealthConnectionState.Live -> OpenCodeListItemStatusTone.Success
        ServerHealthConnectionState.Connecting -> OpenCodeListItemStatusTone.Interactive
        ServerHealthConnectionState.Reconnecting -> OpenCodeListItemStatusTone.Warning
        ServerHealthConnectionState.Offline -> OpenCodeListItemStatusTone.Error
        ServerHealthConnectionState.AuthFailed -> OpenCodeListItemStatusTone.Error
        ServerHealthConnectionState.Stopped -> OpenCodeListItemStatusTone.Stale
    }

internal fun ServerHealthConnectionState.sparklineTone(): OpenCodeChartTone =
    if (this == ServerHealthConnectionState.Live) OpenCodeChartTone.Success else OpenCodeChartTone.Warning

/** Dot and label for the connection state; the dot pulses while live. */
@Composable
fun ServerHealthStatusChip(
    state: ServerHealthConnectionState,
    modifier: Modifier = Modifier,
) {
    val tone = state.statusTone()
    val foreground = tone.foreground()
    Row(
        modifier =
            modifier
                .clip(OpenCodePillShape)
                .background(tone.background())
                .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        OpenCodePulsingDot(color = foreground, animated = state == ServerHealthConnectionState.Live)
        Text(
            text = stringResource(ServerHealthFormat.statusLabel(state)),
            color = foreground,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
        )
    }
}
