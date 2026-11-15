package dev.opencode.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.R
import dev.opencode.mobile.theme.OpenCodeSmallShape
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.icons.OpenCodeIcons
import java.text.NumberFormat

private const val TRUNCATION_ROW_FILL_ALPHA = 0.06f
private val TruncationRowMinHeight = 40.dp
private val TruncationRowIconSize = 16.dp

/** What a truncation row opens, with the counts its caption reports. */
sealed interface OpenCodeTruncationRowVariant {
    /** A message cut in the timeline: "Show full message" over "1,000 of 588,638 characters shown". */
    data class FullMessage(
        val shownCharacters: Int,
        val totalCharacters: Int,
    ) : OpenCodeTruncationRowVariant
}

/**
 * A full-width row at the point where content was cut, inside the content's own surface, that
 * opens the rest. A subtle fill, a title, a caption with what is shown, and a trailing expand icon.
 */
@Composable
fun OpenCodeTruncationRow(
    variant: OpenCodeTruncationRowVariant,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title: String
    val caption: String
    val hint: String
    when (variant) {
        is OpenCodeTruncationRowVariant.FullMessage -> {
            val numbers = NumberFormat.getIntegerInstance()
            title = stringResource(R.string.component_truncation_row_full_message_title)
            caption =
                stringResource(
                    R.string.component_truncation_row_full_message_caption,
                    numbers.format(variant.shownCharacters),
                    numbers.format(variant.totalCharacters),
                )
            hint = stringResource(R.string.component_truncation_row_full_message_hint)
        }
    }
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = TruncationRowMinHeight)
                .clip(OpenCodeSmallShape)
                .background(openCodeColor("text-strong").copy(alpha = TRUNCATION_ROW_FILL_ALPHA))
                .openCodeFocusRing(OpenCodeSmallShape)
                .clickable(onClick = onClick)
                .clearAndSetSemantics {
                    role = Role.Button
                    contentDescription = title
                    stateDescription = caption
                    onClick(label = hint) {
                        onClick()
                        true
                    }
                }.padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.labelLarge, color = openCodeColor("text-strong"))
            Text(text = caption, style = MaterialTheme.typography.bodySmall, color = openCodeColor("text-weak"))
        }
        Icon(
            imageVector = OpenCodeIcons.ExpandContent,
            contentDescription = null,
            modifier = Modifier.size(TruncationRowIconSize),
            tint = openCodeColor("text-base"),
        )
    }
}

@Preview(name = "Full message")
@Composable
private fun OpenCodeTruncationRowPreview() {
    OpenCodeTheme {
        OpenCodeTruncationRow(
            variant = OpenCodeTruncationRowVariant.FullMessage(shownCharacters = 1_000, totalCharacters = 588_638),
            onClick = {},
        )
    }
}
