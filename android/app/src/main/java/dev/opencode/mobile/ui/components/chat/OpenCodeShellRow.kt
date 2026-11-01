package dev.opencode.mobile.ui.components

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.CodeTextStyle
import dev.opencode.mobile.theme.OpenCodeRadius
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.icons.OpenCodeIcons

private val ShellRowCornerRadius = OpenCodeRadius.small
private val ShellRowPaddingHorizontal = 12.dp
private val ShellRowPaddingVertical = 10.dp
private val ShellRowAvatarSize = 32.dp
private val ShellRowGlyphSize = 16.dp
private val ShellRowContentSpacing = 12.dp
private val ShellRowTextStackSpacing = 2.dp
private val ShellRowChevronSize = 16.dp

/**
 * A running agent shell in the background work panel, next to [OpenCodeChildSessionRow]: a
 * terminal glyph where the agent letter sits, the command in monospace on one line, what it
 * does below when known, and a live timer.
 */
@Composable
fun OpenCodeShellRow(
    command: String,
    description: String? = null,
    elapsed: String? = null,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val accessibilityLabel = buildShellRowAccessibilityLabel(command, description, elapsed)
    val shape = RoundedCornerShape(ShellRowCornerRadius)

    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {
                    contentDescription = accessibilityLabel
                    if (onClick != null) {
                        role = Role.Button
                    }
                }.then(
                    if (onClick != null) {
                        Modifier
                            .clip(shape)
                            .clickable(onClick = onClick)
                    } else {
                        Modifier
                    },
                ),
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ShellRowPaddingHorizontal, vertical = ShellRowPaddingVertical),
            horizontalArrangement = Arrangement.spacedBy(ShellRowContentSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(ShellRowAvatarSize)
                        .background(openCodeColor("surface-raised-base"), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = OpenCodeIcons.Terminal,
                    contentDescription = null,
                    modifier = Modifier.size(ShellRowGlyphSize),
                    tint = openCodeColor("text-strong"),
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(ShellRowTextStackSpacing),
            ) {
                Text(
                    text = shellRowCommandLine(command),
                    style = CodeTextStyle,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!description.isNullOrEmpty()) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (!elapsed.isNullOrEmpty()) {
                Text(
                    text = elapsed,
                    style = MaterialTheme.typography.bodySmall,
                    color = openCodeColor("text-weaker"),
                    maxLines = 1,
                )
            }
            if (onClick != null) {
                Icon(
                    imageVector = OpenCodeIcons.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(ShellRowChevronSize),
                    tint = openCodeColor("icon-strong-base"),
                )
            }
        }
    }
}

/** A multi-line command on one line, so the row truncates at its end instead of its first newline. */
internal fun shellRowCommandLine(command: String): String =
    command
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString(" ")

/** "Shell, npm test, Run the tests, running, 1:04". */
internal fun buildShellRowAccessibilityLabel(
    command: String,
    description: String?,
    elapsed: String?,
): String =
    buildList {
        add("Shell")
        add(shellRowCommandLine(command))
        if (!description.isNullOrEmpty()) add(description)
        add("running")
        if (!elapsed.isNullOrEmpty()) add(elapsed)
    }.joinToString(", ")

@Preview(showBackground = true, name = "Shell Row Light")
@Composable
private fun OpenCodeShellRowPreviewLight() {
    OpenCodeTheme(darkTheme = false) {
        ShellRowPreviewColumn()
    }
}

@Preview(showBackground = true, name = "Shell Row Dark")
@Composable
private fun OpenCodeShellRowPreviewDark() {
    OpenCodeTheme(darkTheme = true) {
        ShellRowPreviewColumn()
    }
}

@Composable
private fun ShellRowPreviewColumn() {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OpenCodeShellRow(command = "npm test", description = "Run the tests", elapsed = "1:04", onClick = {})
        OpenCodeShellRow(
            command = "./gradlew :app:testDebugUnitTest --tests 'dev.example.SessionChatViewModelTest' --info",
            elapsed = "0:12",
            onClick = {},
        )
    }
}
