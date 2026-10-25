package dev.opencode.mobile.ui.sessionchat.view

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.domain.message.SessionFailure
import dev.opencode.mobile.theme.openCodeColor
import kotlinx.coroutines.delay

/**
 * Quiet mark under a failed reply. The screen opens the sheet when there is a body or an action.
 * [retryAtEpochMs] adds a "Try again in …" line below the caption until that time passes.
 */
@Composable
fun SessionFailureCaption(
    text: String,
    isNeutral: Boolean,
    onOpen: (() -> Unit)?,
    modifier: Modifier = Modifier,
    retryAtEpochMs: Long? = null,
    clock: () -> Long = { System.currentTimeMillis() },
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
                .then(
                    if (onOpen == null) {
                        Modifier
                    } else {
                        Modifier.clickable(role = Role.Button, onClick = onOpen)
                    },
                ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CaptionDotGap),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(CaptionDotSize)
                        .background(
                            openCodeColor(if (isNeutral) "text-weak" else "error"),
                            CircleShape,
                        ),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = openCodeColor("text-weak"),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (retryAtEpochMs != null) {
            SessionFailureRetryAfter(retryAtEpochMs = retryAtEpochMs, clock = clock)
        }
    }
}

/** Counts down to [retryAtEpochMs] and disappears at zero. */
@Composable
private fun SessionFailureRetryAfter(
    retryAtEpochMs: Long,
    clock: () -> Long,
) {
    var nowMs by remember(retryAtEpochMs) { mutableLongStateOf(clock()) }
    LaunchedEffect(retryAtEpochMs) {
        while (retryAtEpochMs > nowMs) {
            delay(((retryAtEpochMs - nowMs - 1) % 1_000L) + 1)
            nowMs = clock()
        }
    }
    val label = SessionFailure.retryAfterLabel(retryAtEpochMs - nowMs) ?: return
    Text(
        text = label,
        modifier = Modifier.padding(start = CaptionDotSize + CaptionDotGap, top = 2.dp),
        style = MaterialTheme.typography.bodySmall,
        color = openCodeColor("text-weak"),
        maxLines = 1,
    )
}

private val CaptionDotSize = 6.dp
private val CaptionDotGap = 8.dp

/** Monospace provider body shown in the existing detail sheet, with Copy. */
@Composable
fun SessionProviderResponseBody(
    body: String,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        TextButton(onClick = onCopy) {
            Text("Copy")
        }
        Text(
            text = body,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
