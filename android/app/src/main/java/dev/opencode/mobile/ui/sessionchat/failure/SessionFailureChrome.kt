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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.openCodeColor

/** Quiet mark under a failed reply. The screen opens the sheet when there is a body or an action. */
@Composable
fun SessionFailureCaption(
    text: String,
    isNeutral: Boolean,
    onOpen: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
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
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(6.dp)
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
