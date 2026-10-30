package dev.opencode.mobile.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.OpenCodeRadius
import dev.opencode.mobile.theme.OpenCodeTheme

private val OpenCodeSnackbarCornerRadius = OpenCodeRadius.small
private val OpenCodeSnackbarScreenMarginHorizontal = 16.dp
private val OpenCodeSnackbarScreenMarginBottom = 16.dp

/** Outer inset Material's `Snackbar(snackbarData)` applies; host padding is the delta to tokens. */
private val OpenCodeSnackbarMaterialOuterInset = 12.dp

enum class OpenCodeSnackbarVariant {
    Default,
    Error,
}

data class OpenCodeSnackbarMessage(
    val text: String,
    val variant: OpenCodeSnackbarVariant = OpenCodeSnackbarVariant.Default,
    /** Trailing text button, such as "Retry". A snackbar with an action stays up longer. */
    val actionLabel: String? = null,
    val id: Long = System.nanoTime(),
)

/**
 * Host for one-shot auto-dismissing snackbars. When [message] changes to a
 * non-null value, presents it and calls [onDismissed] after auto-dismiss or
 * user swipe-away so the caller can clear presentation state. Tapping a message's
 * action calls [onAction], then dismisses.
 *
 * [onHeightChange] reports how far the shown snackbar reaches above the host's
 * bottom edge (card plus its bottom margin), or 0 when none is shown, so a list
 * behind it can keep its last row clear.
 */
@Composable
fun OpenCodeSnackbarHost(
    message: OpenCodeSnackbarMessage?,
    onDismissed: () -> Unit,
    modifier: Modifier = Modifier,
    onAction: (OpenCodeSnackbarMessage) -> Unit = {},
    onHeightChange: (Dp) -> Unit = {},
) {
    val hostState = remember { SnackbarHostState() }
    val density = LocalDensity.current
    var measuredHeightPx by remember { mutableIntStateOf(0) }
    val shown = hostState.currentSnackbarData != null
    LaunchedEffect(shown, measuredHeightPx) {
        onHeightChange(
            if (shown && measuredHeightPx > 0) {
                // The measured box includes Material's outer inset on both edges.
                with(density) { measuredHeightPx.toDp() } + OpenCodeSnackbarScreenMarginBottom -
                    OpenCodeSnackbarMaterialOuterInset * 2
            } else {
                0.dp
            },
        )
    }

    LaunchedEffect(message?.id) {
        val current = message ?: return@LaunchedEffect
        hostState.currentSnackbarData?.dismiss()
        val result =
            hostState.showSnackbar(
                message = current.text,
                actionLabel = current.actionLabel,
                duration = if (current.actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short,
            )
        if (result == SnackbarResult.ActionPerformed) onAction(current)
        onDismissed()
    }

    SnackbarHost(
        hostState = hostState,
        modifier =
            modifier
                .fillMaxWidth()
                .padding(
                    horizontal = OpenCodeSnackbarScreenMarginHorizontal - OpenCodeSnackbarMaterialOuterInset,
                ).padding(
                    bottom = OpenCodeSnackbarScreenMarginBottom - OpenCodeSnackbarMaterialOuterInset,
                ),
    ) { data ->
        Box(modifier = Modifier.onSizeChanged { measuredHeightPx = it.height }) {
            val variant = message?.variant ?: OpenCodeSnackbarVariant.Default
            val containerColor =
                when (variant) {
                    OpenCodeSnackbarVariant.Default -> MaterialTheme.colorScheme.surfaceVariant
                    OpenCodeSnackbarVariant.Error -> MaterialTheme.colorScheme.error
                }
            val contentColor =
                when (variant) {
                    OpenCodeSnackbarVariant.Default -> MaterialTheme.colorScheme.onSurface
                    OpenCodeSnackbarVariant.Error -> MaterialTheme.colorScheme.onError
                }
            val actionLabel = data.visuals.actionLabel
            if (actionLabel == null) {
                Snackbar(
                    snackbarData = data,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(OpenCodeSnackbarCornerRadius),
                    containerColor = containerColor,
                    contentColor = contentColor,
                    actionContentColor = contentColor,
                    dismissActionContentColor = contentColor,
                )
            } else {
                OpenCodeActionSnackbar(
                    message = data.visuals.message,
                    actionLabel = actionLabel,
                    containerColor = containerColor,
                    contentColor = contentColor,
                    onAction = data::performAction,
                )
            }
        }
    }
}

/**
 * Message and a trailing text action in one row: the message takes the remaining width and
 * wraps, so the action never sits on top of it. Insets match Material's `Snackbar`.
 */
@Composable
private fun OpenCodeActionSnackbar(
    message: String,
    actionLabel: String,
    containerColor: Color,
    contentColor: Color,
    onAction: () -> Unit,
) {
    Surface(
        modifier =
            Modifier
                .padding(OpenCodeSnackbarMaterialOuterInset)
                .fillMaxWidth(),
        shape = RoundedCornerShape(OpenCodeSnackbarCornerRadius),
        color = containerColor,
        contentColor = contentColor,
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier =
                Modifier
                    .heightIn(min = 48.dp)
                    .padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(vertical = 14.dp),
            )
            TextButton(
                onClick = onAction,
                colors = ButtonDefaults.textButtonColors(contentColor = contentColor),
            ) {
                Text(text = actionLabel, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Preview(showBackground = true, name = "Snackbar Default")
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, name = "Snackbar Default Dark")
@Composable
private fun OpenCodeSnackbarDefaultPreview() {
    OpenCodeTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            OpenCodeSnackbarHost(
                message =
                    OpenCodeSnackbarMessage(
                        text = "Session compacted.",
                        variant = OpenCodeSnackbarVariant.Default,
                        id = 1L,
                    ),
                onDismissed = {},
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Preview(showBackground = true, name = "Snackbar Action")
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, name = "Snackbar Action Dark")
@Composable
private fun OpenCodeSnackbarActionPreview() {
    OpenCodeTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            OpenCodeSnackbarHost(
                message =
                    OpenCodeSnackbarMessage(
                        text = "/btw failed: “What did we change so far?”",
                        variant = OpenCodeSnackbarVariant.Error,
                        actionLabel = "Retry",
                        id = 3L,
                    ),
                onDismissed = {},
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Preview(showBackground = true, name = "Snackbar Error")
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, name = "Snackbar Error Dark")
@Composable
private fun OpenCodeSnackbarErrorPreview() {
    OpenCodeTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            Text(
                text = "Preview content",
                modifier = Modifier.align(Alignment.Center),
            )
            OpenCodeSnackbarHost(
                message =
                    OpenCodeSnackbarMessage(
                        text = "Couldn't compact session context.",
                        variant = OpenCodeSnackbarVariant.Error,
                        id = 2L,
                    ),
                onDismissed = {},
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}
