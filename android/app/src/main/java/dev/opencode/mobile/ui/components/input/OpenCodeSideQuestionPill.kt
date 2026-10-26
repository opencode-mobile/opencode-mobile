package dev.opencode.mobile.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.icons.OpenCodeIcons

private val SideQuestionPillShape = RoundedCornerShape(percent = 50)
private val SideQuestionPillPaddingHorizontal = 14.dp
private val SideQuestionPillPaddingVertical = 8.dp
private val SideQuestionPillMinHeight = 36.dp
private val SideQuestionPillIconSize = 16.dp
private val SideQuestionPillSpinnerSize = 14.dp
private val SideQuestionPillIconSpacing = 6.dp
private val SideQuestionPillShadowElevation = 4.dp

/** Where `/btw` side questions stand, shown above the composer. */
sealed interface OpenCodeSideQuestionPillVariant {
    val count: Int

    /** Requests still running. Not interactive; more than one shows `×N`. */
    data class Pending(
        override val count: Int,
    ) : OpenCodeSideQuestionPillVariant

    /** Answers waiting to be read; tapping opens the oldest. */
    data class AnswerReady(
        override val count: Int,
    ) : OpenCodeSideQuestionPillVariant

    /** Requests that failed; tapping asks them again and the trailing close forgets them. */
    data class Failed(
        override val count: Int,
    ) : OpenCodeSideQuestionPillVariant
}

/** Floating status pill for `/btw` side questions, matching the floating pill chrome. */
@Composable
fun OpenCodeSideQuestionPill(
    variant: OpenCodeSideQuestionPillVariant,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    onDismiss: (() -> Unit)? = null,
) {
    val surfaceRaised = openCodeColor("surface-raised-base")
    val borderColor = openCodeColor("border-weaker-base")
    val contentColor =
        when (variant) {
            is OpenCodeSideQuestionPillVariant.Pending -> openCodeColor("text-base")
            is OpenCodeSideQuestionPillVariant.AnswerReady -> openCodeColor("text-strong")
            is OpenCodeSideQuestionPillVariant.Failed -> MaterialTheme.colorScheme.error
        }
    val interactive = variant !is OpenCodeSideQuestionPillVariant.Pending
    val description = sideQuestionPillDescription(variant)

    Surface(
        modifier = modifier.defaultMinSize(minHeight = SideQuestionPillMinHeight),
        shape = SideQuestionPillShape,
        color = surfaceRaised.copy(alpha = 0.95f),
        contentColor = contentColor,
        border = BorderStroke(1.dp, borderColor),
        tonalElevation = 2.dp,
        shadowElevation = SideQuestionPillShadowElevation,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier =
                    Modifier
                        .clip(SideQuestionPillShape)
                        .then(
                            if (interactive) {
                                Modifier.clickable(role = Role.Button, onClick = onClick)
                            } else {
                                Modifier
                            },
                        ).semantics(mergeDescendants = true) { contentDescription = description }
                        .padding(horizontal = SideQuestionPillPaddingHorizontal, vertical = SideQuestionPillPaddingVertical),
                horizontalArrangement = Arrangement.spacedBy(SideQuestionPillIconSpacing),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (variant) {
                    is OpenCodeSideQuestionPillVariant.Pending ->
                        CircularProgressIndicator(
                            modifier = Modifier.size(SideQuestionPillSpinnerSize),
                            strokeWidth = 2.dp,
                            color = contentColor,
                            trackColor = contentColor.copy(alpha = 0.25f),
                        )
                    is OpenCodeSideQuestionPillVariant.AnswerReady ->
                        Icon(
                            OpenCodeIcons.Check,
                            contentDescription = null,
                            modifier = Modifier.size(SideQuestionPillIconSize),
                        )
                    is OpenCodeSideQuestionPillVariant.Failed -> Unit
                }
                Text(
                    text = sideQuestionPillLabel(variant),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (variant is OpenCodeSideQuestionPillVariant.Failed && onDismiss != null) {
                Icon(
                    OpenCodeIcons.Close,
                    contentDescription = "Dismiss failed side questions",
                    tint = openCodeColor("icon-base"),
                    modifier =
                        Modifier
                            .clip(SideQuestionPillShape)
                            .clickable(role = Role.Button, onClick = onDismiss)
                            .padding(end = 10.dp, top = 10.dp, bottom = 10.dp, start = 2.dp)
                            .size(SideQuestionPillIconSize),
                )
            }
        }
    }
}

internal fun sideQuestionPillLabel(variant: OpenCodeSideQuestionPillVariant): String {
    val suffix = if (variant.count > 1) " ×${variant.count}" else ""
    return when (variant) {
        is OpenCodeSideQuestionPillVariant.Pending -> "/btw$suffix"
        is OpenCodeSideQuestionPillVariant.AnswerReady -> "/btw answer ready$suffix"
        is OpenCodeSideQuestionPillVariant.Failed -> "/btw failed$suffix · Retry"
    }
}

internal fun sideQuestionPillDescription(variant: OpenCodeSideQuestionPillVariant): String {
    val many = variant.count > 1
    return when (variant) {
        is OpenCodeSideQuestionPillVariant.Pending ->
            if (many) "${variant.count} side questions running" else "Side question running"
        is OpenCodeSideQuestionPillVariant.AnswerReady ->
            if (many) "${variant.count} side question answers ready. Open" else "Side question answer ready. Open"
        is OpenCodeSideQuestionPillVariant.Failed ->
            if (many) "${variant.count} side questions failed. Retry" else "Side question failed. Retry"
    }
}

@Preview(showBackground = true, name = "Side Question Pill")
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, name = "Side Question Pill Dark")
@Composable
private fun OpenCodeSideQuestionPillPreview() {
    OpenCodeTheme {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OpenCodeSideQuestionPill(OpenCodeSideQuestionPillVariant.Pending(count = 1))
            OpenCodeSideQuestionPill(OpenCodeSideQuestionPillVariant.Pending(count = 3))
            OpenCodeSideQuestionPill(OpenCodeSideQuestionPillVariant.AnswerReady(count = 1))
            OpenCodeSideQuestionPill(OpenCodeSideQuestionPillVariant.Failed(count = 2), onDismiss = {})
        }
    }
}
