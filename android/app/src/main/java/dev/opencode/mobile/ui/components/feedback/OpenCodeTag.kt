package dev.opencode.mobile.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.OpenCodePillShape
import dev.opencode.mobile.theme.openCodeColor

/** Fill opacity of the interactive color behind an [OpenCodeTagTone.Accent] tag. */
internal const val OPEN_CODE_TAG_ACCENT_FILL_ALPHA = 0.12f

enum class OpenCodeTagSize {
    /** Standalone label, such as the onboarding "Unofficial" tag. */
    Regular,

    /** Inline marker on a title line, such as a list row's "git". */
    Small,
}

/** Color treatment of an [OpenCodeTag]. */
enum class OpenCodeTagTone {
    /** Quiet gray label; the default. */
    Neutral,

    /** Interactive-tinted label that draws the eye, such as a "Recommended" choice. */
    Accent,
}

/** Static capsule label. */
@Composable
fun OpenCodeTag(
    title: String,
    modifier: Modifier = Modifier,
    size: OpenCodeTagSize = OpenCodeTagSize.Regular,
    tone: OpenCodeTagTone = OpenCodeTagTone.Neutral,
) {
    require(title.isNotBlank()) { "Tag requires a non-blank title" }
    val accentFill = openCodeColor("interactive").copy(alpha = OPEN_CODE_TAG_ACCENT_FILL_ALPHA)
    val accentText = openCodeColor("text-interactive-base")
    when (size) {
        OpenCodeTagSize.Regular ->
            Surface(
                modifier = modifier,
                shape = OpenCodePillShape,
                color =
                    when (tone) {
                        OpenCodeTagTone.Neutral -> MaterialTheme.colorScheme.surfaceVariant
                        OpenCodeTagTone.Accent -> accentFill
                    },
                contentColor =
                    when (tone) {
                        OpenCodeTagTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
                        OpenCodeTagTone.Accent -> accentText
                    },
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        OpenCodeTagSize.Small ->
            Surface(
                modifier = modifier,
                shape = OpenCodePillShape,
                color =
                    when (tone) {
                        OpenCodeTagTone.Neutral -> openCodeColor("surface-raised-base")
                        OpenCodeTagTone.Accent -> accentFill
                    },
                contentColor =
                    when (tone) {
                        OpenCodeTagTone.Neutral -> openCodeColor("text-weak")
                        OpenCodeTagTone.Accent -> accentText
                    },
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
    }
}
