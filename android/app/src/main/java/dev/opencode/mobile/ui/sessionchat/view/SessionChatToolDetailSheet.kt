package dev.opencode.mobile.ui.sessionchat.view

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.components.OpenCodeBottomSheet
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetContentMode

internal data class SessionChatToolDetailTarget(
    val title: String,
    val body: String,
)

@Composable
internal fun SessionChatToolDetailSheet(
    target: SessionChatToolDetailTarget?,
    onDismiss: () -> Unit,
) {
    OpenCodeBottomSheet(
        visible = target != null,
        title = target?.title.orEmpty(),
        onDismissRequest = onDismiss,
        contentMode = OpenCodeBottomSheetContentMode.ActionList,
    ) {
        Text(
            text = target?.body.orEmpty(),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
