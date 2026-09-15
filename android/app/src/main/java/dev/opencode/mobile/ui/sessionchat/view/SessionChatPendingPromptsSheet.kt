package dev.opencode.mobile.ui.sessionchat.view

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ProductAnalytics
import dev.opencode.mobile.ProductPresentedScreenViewEffect
import dev.opencode.mobile.domain.message.FollowUpDelivery
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.OpenCodeBottomSheet
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonContrast
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import dev.opencode.mobile.ui.icons.OpenCodeIcons
import dev.opencode.mobile.ui.sessionchat.PendingPrompt

private val PendingPromptBadgeShape = RoundedCornerShape(6.dp)
private val PendingPromptBadgeSize = 22.dp
private val PendingPromptBadgeIconSize = 12.dp

@Composable
internal fun SessionChatPendingPromptsSheet(
    visible: Boolean,
    prompts: List<PendingPrompt>,
    onDismiss: () -> Unit,
    onSteer: (String) -> Unit,
    onQueue: (String) -> Unit,
    onDelete: (String) -> Unit,
    onClearAll: () -> Unit,
) {
    ProductPresentedScreenViewEffect(
        screenId = ProductAnalytics.Screen.SESSION_PENDING_PROMPTS,
        presentation = ProductAnalytics.Presentation.SHEET,
        visible = visible,
    )

    OpenCodeBottomSheet(
        visible = visible,
        title = "Up next",
        onDismissRequest = {
            logPendingPromptsAction(ProductAnalytics.Action.PENDING_PROMPTS_DISMISS)
            onDismiss()
        },
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "${prompts.size} waiting",
                style = MaterialTheme.typography.labelSmall,
                color = openCodeColor("text-weak"),
            )
            Text(
                text = "Clear all",
                style = MaterialTheme.typography.labelSmall,
                color = openCodeColor("text-weak"),
                modifier =
                    Modifier.clickable(
                        role = Role.Button,
                        onClick = {
                            logPendingPromptsAction(ProductAnalytics.Action.PENDING_PROMPTS_CLEAR)
                            onClearAll()
                        },
                    ),
            )
        }
        var queuedIndex = 0
        prompts.forEach { prompt ->
            val queuePosition =
                if (prompt.delivery == FollowUpDelivery.Queue) {
                    queuedIndex += 1
                    queuedIndex
                } else {
                    null
                }
            PendingPromptRow(
                prompt = prompt,
                queuePosition = queuePosition,
                onSteer = onSteer,
                onQueue = onQueue,
                onDelete = onDelete,
            )
        }
    }
}

@Composable
private fun PendingPromptRow(
    prompt: PendingPrompt,
    queuePosition: Int?,
    onSteer: (String) -> Unit,
    onQueue: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val isSteer = prompt.delivery == FollowUpDelivery.Steer
    val canSteerNow = !prompt.isSettling && !isSteer
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier =
                Modifier
                    .weight(1f)
                    .then(
                        if (canSteerNow) {
                            Modifier
                                .clickable(
                                    role = Role.Button,
                                    onClick = {
                                        logPendingPromptsAction(
                                            ProductAnalytics.Action.PENDING_PROMPTS_STEER,
                                        )
                                        onSteer(prompt.id)
                                    },
                                ).padding(horizontal = 16.dp, vertical = 12.dp)
                        } else {
                            Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                        },
                    ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PendingPromptBadge(
                delivery = prompt.delivery,
                queuePosition = queuePosition,
            )
            Text(
                text = prompt.text,
                style = MaterialTheme.typography.bodyMedium,
                color =
                    if (isSteer) {
                        openCodeColor("text-strong")
                    } else {
                        openCodeColor("text-base")
                    },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        if (prompt.isSettling) {
            Text(
                text = "Sending…",
                style = MaterialTheme.typography.labelSmall,
                color = openCodeColor("text-weak"),
                modifier = Modifier.padding(end = 16.dp),
            )
        } else {
            OpenCodeButton(
                variant =
                    if (isSteer) {
                        OpenCodeButtonVariant.IconSquare(
                            OpenCodeIcons.ArrowUpward,
                            "Move to queue",
                        )
                    } else {
                        OpenCodeButtonVariant.IconSquare(
                            OpenCodeIcons.ArrowWarmUp,
                            "Steer now",
                        )
                    },
                contrast = OpenCodeButtonContrast.Low,
                onClick = {
                    if (isSteer) {
                        logPendingPromptsAction(ProductAnalytics.Action.PENDING_PROMPTS_QUEUE)
                        onQueue(prompt.id)
                    } else {
                        logPendingPromptsAction(ProductAnalytics.Action.PENDING_PROMPTS_STEER)
                        onSteer(prompt.id)
                    }
                },
            )
            OpenCodeButton(
                variant = OpenCodeButtonVariant.IconSquare(OpenCodeIcons.Delete, "Delete"),
                contrast = OpenCodeButtonContrast.Low,
                onClick = {
                    logPendingPromptsAction(ProductAnalytics.Action.PENDING_PROMPTS_DELETE)
                    onDelete(prompt.id)
                },
                modifier = Modifier.padding(end = 8.dp),
            )
        }
    }
}

@Composable
private fun PendingPromptBadge(
    delivery: FollowUpDelivery,
    queuePosition: Int?,
) {
    val isSteer = delivery == FollowUpDelivery.Steer
    Box(
        modifier =
            Modifier
                .size(PendingPromptBadgeSize)
                .background(
                    color =
                        if (isSteer) {
                            openCodeColor("surface-interactive-weak")
                        } else {
                            openCodeColor("surface-raised-base")
                        },
                    shape = PendingPromptBadgeShape,
                ),
        contentAlignment = Alignment.Center,
    ) {
        if (isSteer) {
            Icon(
                imageVector = OpenCodeIcons.ArrowWarmUp,
                contentDescription = null,
                modifier = Modifier.size(PendingPromptBadgeIconSize),
                tint = openCodeColor("interactive"),
            )
        } else {
            Text(
                text = (queuePosition ?: 1).toString(),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = openCodeColor("warning"),
            )
        }
    }
}

private fun logPendingPromptsAction(actionId: String) {
    ProductAnalytics.logUserAction(
        screenId = ProductAnalytics.Screen.SESSION_PENDING_PROMPTS,
        actionId = actionId,
    )
}
