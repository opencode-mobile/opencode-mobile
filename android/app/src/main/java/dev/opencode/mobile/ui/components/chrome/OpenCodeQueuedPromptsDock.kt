package dev.opencode.mobile.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor

private val QueuedPromptsDockShape = RoundedCornerShape(8.dp)
private val QueuedPromptsDockPaddingHorizontal = 12.dp
private val QueuedPromptsDockPaddingVertical = 8.dp

fun formatQueuedPromptsDockLabel(
    count: Int,
    preview: String?,
): String {
    val collapsed = preview?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
    return if (collapsed.isEmpty()) {
        "$count queued"
    } else {
        "$count queued · $collapsed"
    }
}

@Composable
fun OpenCodeQueuedPromptsDock(
    count: Int,
    preview: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = formatQueuedPromptsDockLabel(count, preview)
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .semantics {
                    role = Role.Button
                    contentDescription = "$count queued prompts"
                }.clickable(onClick = onClick),
        shape = QueuedPromptsDockShape,
        color = openCodeColor("surface-raised-base"),
        contentColor = openCodeColor("text-weak"),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = openCodeColor("text-weak"),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier =
                Modifier.padding(
                    horizontal = QueuedPromptsDockPaddingHorizontal,
                    vertical = QueuedPromptsDockPaddingVertical,
                ),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeQueuedPromptsDockSinglePreview() {
    OpenCodeTheme(darkTheme = false) {
        OpenCodeQueuedPromptsDock(
            count = 1,
            preview = "Also check the compact path.",
            onClick = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeQueuedPromptsDockMultiplePreview() {
    OpenCodeTheme(darkTheme = true) {
        OpenCodeQueuedPromptsDock(
            count = 3,
            preview = "First queued prompt\nwith a second line",
            onClick = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}
