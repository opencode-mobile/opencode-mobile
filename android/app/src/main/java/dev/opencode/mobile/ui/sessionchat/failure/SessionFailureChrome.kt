package dev.opencode.mobile.ui.sessionchat.view

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.openCodeColor

/** Caption under a failed reply, plus its action. The screen owns what the taps do. */
@Composable
fun SessionFailureCaption(
    text: String,
    isNeutral: Boolean,
    actionLabel: String?,
    onCaptionClick: (() -> Unit)?,
    onActionClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = openCodeColor(if (isNeutral) "text-weak" else "error"),
            modifier =
                Modifier
                    .padding(horizontal = 4.dp)
                    .then(
                        if (onCaptionClick == null) {
                            Modifier
                        } else {
                            Modifier.clickable(onClick = onCaptionClick)
                        },
                    ),
        )
        if (!actionLabel.isNullOrBlank() && onActionClick != null) {
            Text(
                text = actionLabel,
                style = MaterialTheme.typography.labelLarge,
                color = openCodeColor("text-strong"),
                modifier =
                    Modifier
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                        .clickable(onClick = onActionClick),
            )
        }
    }
}

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
