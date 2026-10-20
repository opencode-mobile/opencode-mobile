package dev.opencode.mobile.ui.root

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import dev.opencode.mobile.ui.icons.OpenCodeIcons

/** What the cover shows: nothing, a plain cover, or a cover with an Unlock action. */
data class PrivacyCoverState(
    val covered: Boolean = false,
    val locked: Boolean = false,
)

/**
 * Opaque surface over the whole window while the app is not in the foreground or the app
 * lock is engaged (#755). Swallows touches so nothing underneath reacts.
 */
@Composable
fun PrivacyCover(
    state: PrivacyCoverState,
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.covered && !state.locked) return
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .semantics { contentDescription = if (state.locked) "OpenCode Mobile is locked" else "OpenCode Mobile" },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            Icon(
                imageVector = OpenCodeIcons.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "OpenCode Mobile",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (state.locked) {
                Spacer(Modifier.height(24.dp))
                OpenCodeButton(
                    variant = OpenCodeButtonVariant.FullWidthText("Unlock"),
                    onClick = onUnlock,
                )
            }
        }
    }
}
