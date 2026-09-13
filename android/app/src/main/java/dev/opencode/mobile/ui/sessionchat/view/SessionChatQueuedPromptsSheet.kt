package dev.opencode.mobile.ui.sessionchat.view

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ProductAnalytics
import dev.opencode.mobile.ProductPresentedScreenViewEffect
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.OpenCodeBottomSheet
import dev.opencode.mobile.ui.icons.OpenCodeIcons
import dev.opencode.mobile.ui.sessionchat.QueuedPrompt

@Composable
internal fun SessionChatQueuedPromptsSheet(
    visible: Boolean,
    prompts: List<QueuedPrompt>,
    onDismiss: () -> Unit,
    onSteer: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    ProductPresentedScreenViewEffect(
        screenId = ProductAnalytics.Screen.SESSION_QUEUED_PROMPTS,
        presentation = ProductAnalytics.Presentation.SHEET,
        visible = visible,
    )

    OpenCodeBottomSheet(
        visible = visible,
        title = "Queued prompts",
        onDismissRequest = {
            logQueuedPromptsAction(ProductAnalytics.Action.QUEUED_PROMPTS_DISMISS)
            onDismiss()
        },
    ) {
        prompts.forEach { prompt ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = prompt.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = openCodeColor("text-strong"),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier =
                        Modifier
                            .weight(1f)
                            .clickable(
                                role = Role.Button,
                                onClick = {
                                    logQueuedPromptsAction(ProductAnalytics.Action.QUEUED_PROMPTS_STEER)
                                    onSteer(prompt.id)
                                },
                            ).padding(horizontal = 20.dp, vertical = 12.dp),
                )
                IconButton(
                    onClick = {
                        logQueuedPromptsAction(ProductAnalytics.Action.QUEUED_PROMPTS_DELETE)
                        onDelete(prompt.id)
                    },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = OpenCodeIcons.Delete,
                        contentDescription = "Delete",
                        tint = openCodeColor("icon-base"),
                    )
                }
            }
        }
    }
}

private fun logQueuedPromptsAction(actionId: String) {
    ProductAnalytics.logUserAction(
        screenId = ProductAnalytics.Screen.SESSION_QUEUED_PROMPTS,
        actionId = actionId,
    )
}
