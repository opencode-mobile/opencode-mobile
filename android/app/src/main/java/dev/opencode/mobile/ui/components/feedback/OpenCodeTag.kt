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

enum class OpenCodeTagSize {
    /** Standalone label, such as the onboarding "Unofficial" tag. */
    Regular,

    /** Inline marker on a title line, such as a list row's "git". */
    Small,
}

/** Static capsule label. */
@Composable
fun OpenCodeTag(
    title: String,
    modifier: Modifier = Modifier,
    size: OpenCodeTagSize = OpenCodeTagSize.Regular,
) {
    require(title.isNotBlank()) { "Tag requires a non-blank title" }
    when (size) {
        OpenCodeTagSize.Regular ->
            Surface(
                modifier = modifier,
                shape = OpenCodePillShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
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
                color = openCodeColor("surface-raised-base"),
                contentColor = openCodeColor("text-weak"),
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
