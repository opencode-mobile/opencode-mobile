package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import dev.opencode.mobile.ui.components.OpenCodeSnackbarHost
import dev.opencode.mobile.ui.components.OpenCodeSnackbarMessage
import dev.opencode.mobile.ui.components.OpenCodeSnackbarVariant

@Composable
fun SnackbarGalleryScreen(modifier: Modifier = Modifier) {
    var message by remember { mutableStateOf<OpenCodeSnackbarMessage?>(null) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Snackbar",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "Fire a one-shot snackbar. It auto-dismisses after a few seconds.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OpenCodeButton(
                variant = OpenCodeButtonVariant.CompactText("Default"),
                onClick = {
                    message =
                        OpenCodeSnackbarMessage(
                            text = "Session compacted.",
                            variant = OpenCodeSnackbarVariant.Default,
                        )
                },
            )
            OpenCodeButton(
                variant = OpenCodeButtonVariant.CompactText("Error"),
                onClick = {
                    message =
                        OpenCodeSnackbarMessage(
                            text = "Couldn't compact session context.",
                            variant = OpenCodeSnackbarVariant.Error,
                        )
                },
            )
            OpenCodeButton(
                variant = OpenCodeButtonVariant.CompactText("Action"),
                onClick = {
                    message =
                        OpenCodeSnackbarMessage(
                            text = "/btw failed: “What did we change so far?”",
                            variant = OpenCodeSnackbarVariant.Error,
                            actionLabel = "Retry",
                        )
                },
            )
        }
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(160.dp),
        ) {
            OpenCodeSnackbarHost(
                message = message,
                onDismissed = { message = null },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}
