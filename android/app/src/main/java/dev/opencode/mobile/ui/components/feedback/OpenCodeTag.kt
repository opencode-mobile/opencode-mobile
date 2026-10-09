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

/** Static capsule label, such as the onboarding "Unofficial" tag. */
@Composable
fun OpenCodeTag(
    title: String,
    modifier: Modifier = Modifier,
) {
    require(title.isNotBlank()) { "Tag requires a non-blank title" }
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
}
