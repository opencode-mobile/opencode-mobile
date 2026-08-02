package dev.opencode.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.icons.OpenCodeIcons

enum class OpenCodeSessionTodoItemVariant {
    StatusRow,
}

enum class OpenCodeSessionTodoItemStatus {
    Pending,
    InProgress,
    Completed,
    Cancelled,
    ;

    val accessibilityPhrase: String
        get() =
            when (this) {
                Pending -> "pending"
                InProgress -> "in progress"
                Completed -> "completed"
                Cancelled -> "cancelled"
            }
}

enum class SessionTodoItemPreviewCase(
    val content: String,
    val status: OpenCodeSessionTodoItemStatus,
    val darkTheme: Boolean,
) {
    PendingLight("Summarize README", OpenCodeSessionTodoItemStatus.Pending, false),
    InProgressDark("List top-level files", OpenCodeSessionTodoItemStatus.InProgress, true),
    CompletedLight("Suggest a follow-up", OpenCodeSessionTodoItemStatus.Completed, false),
    CancelledDark("Optional deep dive", OpenCodeSessionTodoItemStatus.Cancelled, true),
}

@Composable
fun OpenCodeSessionTodoItem(
    content: String,
    status: OpenCodeSessionTodoItemStatus,
    modifier: Modifier = Modifier,
    variant: OpenCodeSessionTodoItemVariant = OpenCodeSessionTodoItemVariant.StatusRow,
) {
    @Suppress("UNUSED_VARIABLE")
    val unusedVariant = variant
    val finished =
        status == OpenCodeSessionTodoItemStatus.Completed ||
            status == OpenCodeSessionTodoItemStatus.Cancelled
    val bodyStyle = MaterialTheme.typography.bodyLarge
    val density = LocalDensity.current
    val markSize = 18.dp
    val markFirstLineOffset =
        with(density) {
            val lineHeightPx = bodyStyle.lineHeight.toPx()
            val markPx = markSize.toPx()
            ((lineHeightPx - markPx) / 2f).coerceAtLeast(0f).toDp()
        }
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp)
                .semantics {
                    contentDescription = "$content, ${status.accessibilityPhrase}"
                },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        OpenCodeSessionTodoItemMark(
            status = status,
            modifier = Modifier.padding(top = markFirstLineOffset),
        )
        Text(
            text = content,
            modifier =
                Modifier
                    .weight(1f)
                    .then(
                        if (status == OpenCodeSessionTodoItemStatus.Pending) {
                            Modifier.alpha(0.85f)
                        } else {
                            Modifier
                        },
                    ),
            style =
                bodyStyle.copy(
                    textDecoration = if (finished) TextDecoration.LineThrough else TextDecoration.None,
                ),
            color = if (finished) openCodeColor("text-base") else openCodeColor("text-strong"),
        )
    }
}

@Composable
private fun OpenCodeSessionTodoItemMark(
    status: OpenCodeSessionTodoItemStatus,
    modifier: Modifier = Modifier,
) {
    val interactive = openCodeColor("text-interactive-base")
    val border =
        when (status) {
            OpenCodeSessionTodoItemStatus.Pending -> openCodeColor("border-weaker-base")
            else -> interactive
        }
    val fill =
        when (status) {
            OpenCodeSessionTodoItemStatus.Pending -> Color.Transparent
            else -> interactive.copy(alpha = 0.15f)
        }
    Box(
        modifier =
            modifier
                .size(18.dp)
                .background(fill, RoundedCornerShape(4.dp))
                .border(1.5.dp, border, RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) {
        when (status) {
            OpenCodeSessionTodoItemStatus.Completed, OpenCodeSessionTodoItemStatus.Cancelled ->
                Icon(
                    imageVector = OpenCodeIcons.Check,
                    contentDescription = null,
                    tint = interactive,
                    modifier = Modifier.size(12.dp),
                )
            OpenCodeSessionTodoItemStatus.InProgress ->
                Box(
                    modifier =
                        Modifier
                            .size(width = 8.dp, height = 2.dp)
                            .background(interactive, RoundedCornerShape(1.dp)),
                )
            OpenCodeSessionTodoItemStatus.Pending -> Unit
        }
    }
}

@Composable
private fun SessionTodoItemPreview(case: SessionTodoItemPreviewCase) {
    OpenCodeTheme(darkTheme = case.darkTheme) {
        OpenCodeSessionTodoItem(content = case.content, status = case.status)
    }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeSessionTodoItemPendingLightPreview() {
    SessionTodoItemPreview(SessionTodoItemPreviewCase.PendingLight)
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeSessionTodoItemInProgressDarkPreview() {
    SessionTodoItemPreview(SessionTodoItemPreviewCase.InProgressDark)
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeSessionTodoItemCompletedLightPreview() {
    SessionTodoItemPreview(SessionTodoItemPreviewCase.CompletedLight)
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeSessionTodoItemCancelledDarkPreview() {
    SessionTodoItemPreview(SessionTodoItemPreviewCase.CancelledDark)
}
