package dev.opencode.mobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.openCodeColor

enum class OpenCodeKeyValueStyle {
    /** Label and value on one line. */
    Inline,

    /** Label above the value. */
    Stacked,
}

/** Label and value pair used for detail lines and stat cells. */
@Composable
fun OpenCodeKeyValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    style: OpenCodeKeyValueStyle = OpenCodeKeyValueStyle.Inline,
) {
    when (style) {
        OpenCodeKeyValueStyle.Inline ->
            Row(
                modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = openCodeColor("text-base"),
                )
                Spacer(modifier = Modifier.widthIn(min = 8.dp))
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall,
                    color = openCodeColor("text-strong"),
                    textAlign = TextAlign.End,
                )
            }
        OpenCodeKeyValueStyle.Stacked ->
            Column(
                modifier = modifier.semantics(mergeDescendants = true) {},
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
    }
}
