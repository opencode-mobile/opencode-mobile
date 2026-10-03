package dev.opencode.mobile.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.ui.icons.OpenCodeIcons

private val ModalCornerRadius = RoundedCornerShape(24.dp)
private val ModalMaxWidth = 360.dp
private val ModalPadding = 20.dp
private val ModalTopPadding = 48.dp

/**
 * Centered card with a close button and custom content, for promos and short
 * walkthroughs. Same window behavior as Dialog; back, scrim tap, and close all
 * call [onDismissRequest].
 */
@Composable
fun OpenCodeModal(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!visible) return

    Dialog(
        onDismissRequest = onDismissRequest,
        properties =
            DialogProperties(
                dismissOnBackPress = true,
                dismissOnClickOutside = true,
                usePlatformDefaultWidth = false,
            ),
    ) {
        Surface(
            modifier =
                modifier
                    .widthIn(max = ModalMaxWidth)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
            shape = ModalCornerRadius,
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 0.dp,
            shadowElevation = 8.dp,
        ) {
            Box {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(start = ModalPadding, end = ModalPadding, top = ModalTopPadding, bottom = ModalPadding),
                    content = content,
                )
                OpenCodeButton(
                    variant = OpenCodeButtonVariant.IconCircle(OpenCodeIcons.Close, "Close"),
                    contrast = OpenCodeButtonContrast.Low,
                    onClick = onDismissRequest,
                    modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeModalPreview() {
    OpenCodeTheme {
        OpenCodeModal(visible = true, onDismissRequest = {}) {
            Text("See the work at a glance", style = MaterialTheme.typography.titleLarge)
        }
    }
}
