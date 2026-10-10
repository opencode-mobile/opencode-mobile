package dev.opencode.mobile.ui.sessionchat.view

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.domain.session.SessionRetryCard
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Live retry status fused to the top of the composer. The link is the only button while the sheet is closed. */
@Composable
fun SessionRetryCountdown(
    card: SessionRetryCard,
    onOpenLink: (String) -> Unit,
    modifier: Modifier = Modifier,
    onOpenBody: (() -> Unit)? = null,
    clock: () -> Long = { System.currentTimeMillis() },
) {
    var nowMs by remember(card.nextAtMs) { mutableLongStateOf(clock()) }
    LaunchedEffect(card.nextAtMs) {
        while (isActive) {
            nowMs = clock()
            delay(1_000)
        }
    }
    val seconds = ((card.nextAtMs - nowMs) / 1_000L).coerceAtLeast(0L)
    val attempt = card.attempt?.let { "attempt $it, " }.orEmpty()
    val label = card.actionLabel?.trim()?.takeIf { it.isNotEmpty() }
    val link = card.actionLink?.trim()?.takeIf { it.isNotEmpty() }
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .then(
                        if (onOpenBody == null) {
                            Modifier
                        } else {
                            Modifier.clickable(role = Role.Button, onClick = onOpenBody)
                        },
                    ),
        ) {
            Text(
                text = card.message,
                style = MaterialTheme.typography.bodyMedium,
                color = openCodeColor("text-strong"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${attempt}retrying in ${seconds}s",
                modifier = Modifier.padding(top = 2.dp),
                style = MaterialTheme.typography.labelMedium,
                color = openCodeColor("text-weak"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (label != null && link != null) {
            OpenCodeButton(
                variant = OpenCodeButtonVariant.CompactText(label),
                onClick = { onOpenLink(link) },
            )
        }
    }
}
