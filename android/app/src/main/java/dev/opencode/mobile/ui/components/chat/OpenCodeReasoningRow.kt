package dev.opencode.mobile.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.OpenCodeSmallShape
import dev.opencode.mobile.ui.icons.OpenCodeIcons

/** "Thinking" while a thought is streaming, or "Thought · Ns" once it settles. */
@Composable
fun OpenCodeReasoningRow(
    state: OpenCodeChatActivityState,
    modifier: Modifier = Modifier,
    durationLabel: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val label = reasoningLabel(state, durationLabel)
    val collapsed = state == OpenCodeChatActivityState.Collapsed
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .then(if (collapsed) Modifier.height(0.dp) else Modifier)
                .clipToBounds()
                .padding(
                    start = 4.dp,
                    top = if (collapsed) 0.dp else 12.dp,
                    bottom = if (collapsed) 0.dp else 8.dp,
                )
                .then(
                    when {
                        collapsed -> Modifier.semantics { hideFromAccessibility() }
                        onClick != null ->
                            Modifier
                                .openCodeFocusRing(OpenCodeSmallShape)
                                .clickable(role = Role.Button, onClick = onClick)
                                .semantics { contentDescription = label }
                        else -> Modifier.semantics { contentDescription = label }
                    },
                ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (state) {
            OpenCodeChatActivityState.Working -> OpenCodePulsingDot()
            OpenCodeChatActivityState.Idle ->
                Icon(
                    imageVector = OpenCodeIcons.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(12.dp),
                )
            OpenCodeChatActivityState.Collapsed, OpenCodeChatActivityState.Expanded -> Unit
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun reasoningLabel(
    state: OpenCodeChatActivityState,
    durationLabel: String?,
): String =
    when (state) {
        OpenCodeChatActivityState.Working -> "Thinking"
        OpenCodeChatActivityState.Expanded ->
            if (durationLabel.isNullOrBlank()) "Thought" else "Thought · $durationLabel"
        OpenCodeChatActivityState.Idle, OpenCodeChatActivityState.Collapsed -> "Thought"
    }
