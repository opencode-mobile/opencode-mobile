package dev.opencode.mobile.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.OpenCodeTheme

private val OpenCodeSnackbarCornerRadius = 8.dp
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
    val id: Long = System.nanoTime(),
)

/**
 * Host for one-shot auto-dismissing snackbars. When [message] changes to a
 * non-null value, presents it and calls [onDismissed] after auto-dismiss or
 * user swipe-away so the caller can clear presentation state.
 */
@Composable
fun OpenCodeSnackbarHost(
    message: OpenCodeSnackbarMessage?,
    onDismissed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hostState = remember { SnackbarHostState() }

    LaunchedEffect(message?.id) {
        val current = message ?: return@LaunchedEffect
        hostState.currentSnackbarData?.dismiss()
        hostState.showSnackbar(
            message = current.text,
            duration = SnackbarDuration.Short,
        )
        onDismissed()
    }

    SnackbarHost(
        hostState = hostState,
        modifier =
            modifier
                .fillMaxWidth()
                .padding(
                    horizontal = OpenCodeSnackbarScreenMarginHorizontal - OpenCodeSnackbarMaterialOuterInset,
                )
                .padding(
                    bottom = OpenCodeSnackbarScreenMarginBottom - OpenCodeSnackbarMaterialOuterInset,
                ),
    ) { data ->
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
        Snackbar(
            snackbarData = data,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(OpenCodeSnackbarCornerRadius),
            containerColor = containerColor,
            contentColor = contentColor,
            actionContentColor = contentColor,
            dismissActionContentColor = contentColor,
        )
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
