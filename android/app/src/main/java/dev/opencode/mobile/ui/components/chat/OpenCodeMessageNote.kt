package dev.opencode.mobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.icons.OpenCodeIcons

private val MessageNoteIconSize = 14.dp
private val MessageNoteSpacing = 6.dp

/** Why an assistant reply is incomplete; each variant owns its copy and glyph. */
enum class OpenCodeMessageNoteVariant(
    val label: String,
) {
    /** The model hit its output limit. */
    Truncated("Response cut off (length limit)"),

    /** The user (or another client) stopped the run. */
    Interrupted("Interrupted"),
}

/**
 * Small, persistent, non-interactive note under a message that ended early.
 * Muted on purpose: it explains state, it is not an error.
 */
@Composable
fun OpenCodeMessageNote(
    variant: OpenCodeMessageNoteVariant,
    modifier: Modifier = Modifier,
) {
    val color = openCodeColor("text-weak")
    Row(
        modifier = modifier.clearAndSetSemantics { contentDescription = variant.label },
        horizontalArrangement = Arrangement.spacedBy(MessageNoteSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = variant.icon,
            contentDescription = null,
            modifier = Modifier.size(MessageNoteIconSize),
            tint = color,
        )
        Text(text = variant.label, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

private val OpenCodeMessageNoteVariant.icon: ImageVector
    get() =
        when (this) {
            OpenCodeMessageNoteVariant.Truncated -> OpenCodeIcons.CollapseContent
            OpenCodeMessageNoteVariant.Interrupted -> OpenCodeIcons.StopCircle
        }

@Preview(showBackground = true, name = "Message Note Light")
@Composable
private fun OpenCodeMessageNotePreviewLight() {
    OpenCodeTheme(darkTheme = false) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OpenCodeMessageNoteVariant.entries.forEach { OpenCodeMessageNote(variant = it) }
        }
    }
}

@Preview(showBackground = true, name = "Message Note Dark")
@Composable
private fun OpenCodeMessageNotePreviewDark() {
    OpenCodeTheme(darkTheme = true) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OpenCodeMessageNoteVariant.entries.forEach { OpenCodeMessageNote(variant = it) }
        }
    }
}
