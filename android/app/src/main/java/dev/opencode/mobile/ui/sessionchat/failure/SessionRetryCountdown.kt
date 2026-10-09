package dev.opencode.mobile.ui.sessionchat.view

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.domain.session.SessionRetryCard
import dev.opencode.mobile.theme.openCodeColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Live "attempt N, retrying in Xs" card. Sits above the timeline, not in the working lamp. */
@Composable
fun SessionRetryCountdown(
    card: SessionRetryCard,
    onOpenLink: (String) -> Unit,
    modifier: Modifier = Modifier,
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
    Column(modifier = modifier) {
        Text(
            text = card.message,
            style = MaterialTheme.typography.bodySmall,
            color = openCodeColor("error"),
        )
        Text(
            text = "${attempt}retrying in ${seconds}s",
            modifier = Modifier.padding(top = 2.dp),
            style = MaterialTheme.typography.labelMedium,
            color = openCodeColor("text-weak"),
        )
        val label = card.actionLabel
        if (!label.isNullOrBlank()) {
            val link = card.actionLink
            Text(
                text = label,
                modifier =
                    Modifier
                        .padding(top = 4.dp)
                        .then(
                            if (link.isNullOrBlank()) {
                                Modifier
                            } else {
                                Modifier.clickable { onOpenLink(link) }
                            },
                        ),
                style = MaterialTheme.typography.labelLarge,
                color = openCodeColor("text-strong"),
            )
        }
    }
}
