package dev.opencode.mobile.ui.serverhealth

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.R
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.OpenCodeSparkline
import dev.opencode.mobile.ui.text.resolve

/** The active server card's health footer on the Servers page. Tapping it opens Server Health. */
@Composable
fun ServerHealthQuickViewFooter(
    quickView: ServerHealthQuickView,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(
                    role = Role.Button,
                    onClickLabel = stringResource(R.string.server_health_open_details),
                    onClick = onClick,
                ).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ServerHealthStatusChip(state = quickView.state)
            Spacer(modifier = Modifier.weight(1f))
            quickView.currentPingMs?.let { ping ->
                Text(
                    text = ServerHealthFormat.milliseconds(ping),
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = openCodeColor("text-strong"),
                )
            }
        }
        OpenCodeSparkline(
            values = quickView.recentPings.map { it?.toDouble() },
            tone = quickView.state.sparklineTone(),
            showsGaps = true,
            modifier = Modifier.fillMaxWidth().height(40.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = quickView.caption.resolve(),
                style = MaterialTheme.typography.labelSmall,
                color = openCodeColor("text-base"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.server_health_details),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = openCodeColor("interactive"),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}
