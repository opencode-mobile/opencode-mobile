package dev.opencode.mobile.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor

private val SessionTodosPillShape = RoundedCornerShape(percent = 50)

enum class OpenCodeSessionTodosPillVariant {
    LiveProgress,
}

enum class SessionTodosPillPreviewCase(
    val completedCount: Int,
    val totalCount: Int,
    val activePreview: String?,
    val enabled: Boolean,
    val darkTheme: Boolean,
) {
    MidProgressLight(2, 5, "Implement auth middleware", true, false),
    MidProgressDark(2, 5, "Implement auth middleware", true, true),
    NearCompleteLight(4, 5, "Write integration tests", true, false),
    NoPreviewDark(1, 3, null, true, true),
    DisabledLight(2, 5, "Implement auth middleware", false, false),
    DisabledDark(0, 2, "Sketch API surface", false, true),
}

fun sessionTodosPillProgressLabel(
    completedCount: Int,
    totalCount: Int,
): String = "$completedCount/$totalCount"

fun sessionTodosPillAccessibilityLabel(
    completedCount: Int,
    totalCount: Int,
    activePreview: String?,
): String {
    val parts =
        buildList {
            add("Session todos")
            add("$completedCount of $totalCount completed")
            activePreview?.takeIf { it.isNotBlank() }?.let(::add)
        }
    return parts.joinToString(", ")
}

@Composable
fun OpenCodeSessionTodosPill(
    completedCount: Int,
    totalCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: OpenCodeSessionTodosPillVariant = OpenCodeSessionTodosPillVariant.LiveProgress,
    activePreview: String? = null,
    enabled: Boolean = true,
) {
    require(totalCount > 0) { "Session Todos Pill requires a positive total count" }
    require(completedCount >= 0) { "Session Todos Pill requires nonnegative completed count" }
    require(completedCount <= totalCount) { "Session Todos Pill completed count cannot exceed total" }
    @Suppress("UNUSED_VARIABLE")
    val unusedVariant = variant
    val preview = activePreview?.takeIf { it.isNotBlank() }
    val interactionSource = remember { MutableInteractionSource() }
    val countColor =
        if (enabled) openCodeColor("text-strong") else MaterialTheme.colorScheme.onSurfaceVariant
    val previewColor =
        if (enabled) openCodeColor("text-base") else MaterialTheme.colorScheme.onSurfaceVariant
    val dotColor =
        if (enabled) openCodeColor("text-interactive-base") else MaterialTheme.colorScheme.onSurfaceVariant
    val surfaceRaised = openCodeColor("surface-raised-base")
    val accessibility =
        sessionTodosPillAccessibilityLabel(completedCount, totalCount, preview)

    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.semantics { contentDescription = accessibility },
        shape = SessionTodosPillShape,
        color = surfaceRaised.copy(alpha = if (enabled) 0.95f else 0.7f),
        border = BorderStroke(1.dp, openCodeColor("border-weaker-base")),
        tonalElevation = 2.dp,
        shadowElevation = if (enabled) 4.dp else 0.dp,
        interactionSource = interactionSource,
    ) {
        Row(
            modifier =
                Modifier
                    .widthIn(max = 320.dp)
                    .defaultMinSize(minHeight = 36.dp)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .then(
                            if (enabled) {
                                Modifier.border(3.dp, dotColor.copy(alpha = 0.25f), CircleShape)
                            } else {
                                Modifier
                            },
                        ),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(dotColor),
                )
            }
            Text(
                text = sessionTodosPillProgressLabel(completedCount, totalCount),
                style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace),
                color = countColor,
                maxLines = 1,
            )
            if (preview != null) {
                Text(
                    text = preview,
                    style = MaterialTheme.typography.labelLarge,
                    color = previewColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
    }
}

@Composable
private fun SessionTodosPillPreview(case: SessionTodosPillPreviewCase) {
    OpenCodeTheme(darkTheme = case.darkTheme) {
        OpenCodeSessionTodosPill(
            completedCount = case.completedCount,
            totalCount = case.totalCount,
            activePreview = case.activePreview,
            enabled = case.enabled,
            onClick = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeSessionTodosPillMidProgressLightPreview() {
    SessionTodosPillPreview(SessionTodosPillPreviewCase.MidProgressLight)
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeSessionTodosPillMidProgressDarkPreview() {
    SessionTodosPillPreview(SessionTodosPillPreviewCase.MidProgressDark)
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeSessionTodosPillNearCompleteLightPreview() {
    SessionTodosPillPreview(SessionTodosPillPreviewCase.NearCompleteLight)
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeSessionTodosPillNoPreviewDarkPreview() {
    SessionTodosPillPreview(SessionTodosPillPreviewCase.NoPreviewDark)
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeSessionTodosPillDisabledLightPreview() {
    SessionTodosPillPreview(SessionTodosPillPreviewCase.DisabledLight)
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeSessionTodosPillDisabledDarkPreview() {
    SessionTodosPillPreview(SessionTodosPillPreviewCase.DisabledDark)
}
