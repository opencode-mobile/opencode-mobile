package dev.opencode.mobile.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.domain.message.FollowUpDelivery
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.icons.OpenCodeIcons
import dev.opencode.mobile.ui.sessionchat.PendingPrompt

private val PendingPromptsStripShape = RoundedCornerShape(percent = 50)
private val CollapsedWhitespace = Regex("\\s+")

data class PendingPromptsStripLabel(
    val primary: String,
    val secondary: String?,
)

fun formatPendingPromptsStripLabel(prompts: List<PendingPrompt>): PendingPromptsStripLabel {
    val primary =
        prompts
            .firstOrNull()
            ?.text
            ?.replace(CollapsedWhitespace, " ")
            ?.trim()
            .orEmpty()
    val secondary =
        if (prompts.size > 1) {
            "+${prompts.size - 1} queued"
        } else {
            null
        }
    return PendingPromptsStripLabel(primary = primary, secondary = secondary)
}

fun formatPendingPromptsStripContentDescription(prompts: List<PendingPrompt>): String {
    val noun =
        if (prompts.size == 1) {
            "1 pending prompt"
        } else {
            "${prompts.size} pending prompts"
        }
    val next = formatPendingPromptsStripLabel(prompts).primary
    return "$noun, next: $next"
}

@Composable
fun OpenCodePendingPromptsStrip(
    prompts: List<PendingPrompt>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (prompts.isEmpty()) return
    val label = formatPendingPromptsStripLabel(prompts)
    val first = prompts.first()
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 32.dp)
                .semantics {
                    role = Role.Button
                    contentDescription = formatPendingPromptsStripContentDescription(prompts)
                }.clickable(onClick = onClick),
        shape = PendingPromptsStripShape,
        color = openCodeColor("surface-raised-base"),
        border = BorderStroke(1.dp, openCodeColor("border-weaker-base")),
        shadowElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector =
                    if (first.delivery == FollowUpDelivery.Steer) {
                        OpenCodeIcons.ArrowWarmUp
                    } else {
                        OpenCodeIcons.ArrowUpward
                    },
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint =
                    if (first.delivery == FollowUpDelivery.Steer) {
                        openCodeColor("interactive")
                    } else {
                        openCodeColor("warning")
                    },
            )
            Text(
                text = label.primary,
                style = MaterialTheme.typography.bodySmall,
                color = openCodeColor("text-strong"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            label.secondary?.let { secondary ->
                Text(
                    text = secondary,
                    style = MaterialTheme.typography.labelSmall,
                    color = openCodeColor("text-weak"),
                )
            }
            Icon(
                imageVector = OpenCodeIcons.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = openCodeColor("icon-weak-base"),
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodePendingPromptsStripSingleSteerPreview() {
    OpenCodeTheme(darkTheme = false) {
        OpenCodePendingPromptsStrip(
            prompts =
                listOf(
                    PendingPrompt("1", "Also check the compact path.", FollowUpDelivery.Steer),
                ),
            onClick = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodePendingPromptsStripSingleQueuedPreview() {
    OpenCodeTheme(darkTheme = false) {
        OpenCodePendingPromptsStrip(
            prompts =
                listOf(
                    PendingPrompt("1", "Also check the compact path.", FollowUpDelivery.Queue),
                ),
            onClick = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodePendingPromptsStripSteerPlusQueuedPreview() {
    OpenCodeTheme(darkTheme = false) {
        OpenCodePendingPromptsStrip(
            prompts =
                listOf(
                    PendingPrompt("1", "Steer this first.", FollowUpDelivery.Steer),
                    PendingPrompt("2", "Second queued prompt", FollowUpDelivery.Queue),
                    PendingPrompt("3", "Third queued prompt", FollowUpDelivery.Queue),
                ),
            onClick = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodePendingPromptsStripLongTextPreview() {
    OpenCodeTheme(darkTheme = true) {
        OpenCodePendingPromptsStrip(
            prompts =
                listOf(
                    PendingPrompt(
                        "1",
                        "First queued prompt\nwith a second line that should collapse and ellipsize",
                        FollowUpDelivery.Queue,
                    ),
                    PendingPrompt("2", "Another", FollowUpDelivery.Queue),
                ),
            onClick = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}
