package dev.opencode.mobile.ui.sessionchat.view

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ProductAnalytics
import dev.opencode.mobile.domain.message.ToolStatus
import dev.opencode.mobile.ui.components.OpenCodeToolCallCard
import dev.opencode.mobile.ui.components.OpenCodeToolCallCardStatus
import dev.opencode.mobile.ui.components.OpenCodeTopBar
import dev.opencode.mobile.ui.components.OpenCodeTopBarLeading
import dev.opencode.mobile.ui.sessionchat.SessionChatTimelineBlock

internal data class SessionChatToolGroupTarget(
    val blockID: String,
    val title: String,
    val subtitle: String?,
    val members: List<SessionChatTimelineBlock.ToolCard>,
    val messageId: String,
)

@Composable
internal fun SessionChatToolGroupScreen(
    target: SessionChatToolGroupTarget,
    supportsSessionMessageDiff: Boolean,
    onNavigateBack: () -> Unit,
    onToolDetailTap: (SessionChatToolDetailTarget) -> Unit,
    onChildSessionTap: (sessionId: String, sessionTitle: String) -> Unit,
    onChangesReviewTap: (assistantMessageId: String, initialFilePath: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = {
        logToolGroupAction(ProductAnalytics.Action.SESSION_TOOL_GROUP_BACK)
        onNavigateBack()
    })
    LaunchedEffect(target.blockID) {
        ProductAnalytics.logScreenView(
            screenId = ProductAnalytics.Screen.SESSION_TOOL_GROUP,
            presentation = ProductAnalytics.Presentation.ROUTE,
        )
    }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
    ) {
        OpenCodeTopBar(
            title = target.title,
            subtitle = target.subtitle,
            leading =
                OpenCodeTopBarLeading.Back(
                    onClick = {
                        logToolGroupAction(ProductAnalytics.Action.SESSION_TOOL_GROUP_BACK)
                        onNavigateBack()
                    },
                ),
            showDivider = true,
        )
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            target.members.forEach { card ->
                OpenCodeToolCallCard(
                    status = card.status.toGroupToolCallCardStatus(),
                    title = card.title,
                    subtitle = card.subtitle,
                    additions = card.additions,
                    deletions = card.deletions,
                    errorText = card.errorText,
                    onClick =
                        toolGroupMemberOnClick(
                            card = card,
                            messageId = target.messageId,
                            supportsSessionMessageDiff = supportsSessionMessageDiff,
                            onToolDetailTap = onToolDetailTap,
                            onChildSessionTap = onChildSessionTap,
                            onChangesReviewTap = onChangesReviewTap,
                        ),
                )
            }
        }
    }
}

private fun toolGroupMemberOnClick(
    card: SessionChatTimelineBlock.ToolCard,
    messageId: String,
    supportsSessionMessageDiff: Boolean,
    onToolDetailTap: (SessionChatToolDetailTarget) -> Unit,
    onChildSessionTap: (sessionId: String, sessionTitle: String) -> Unit,
    onChangesReviewTap: (assistantMessageId: String, initialFilePath: String?) -> Unit,
): (() -> Unit)? =
    when {
        card.childSessionId != null -> {
            { onChildSessionTap(card.childSessionId, card.title) }
        }
        card.opensChangesReview && supportsSessionMessageDiff -> {
            { onChangesReviewTap(messageId, card.changesInitialFilePath) }
        }
        card.detailText != null -> {
            {
                onToolDetailTap(
                    SessionChatToolDetailTarget(
                        title = card.title,
                        body = card.detailText,
                    ),
                )
            }
        }
        else -> null
    }

private fun ToolStatus.toGroupToolCallCardStatus(): OpenCodeToolCallCardStatus =
    when (this) {
        ToolStatus.Pending -> OpenCodeToolCallCardStatus.Pending
        ToolStatus.Running -> OpenCodeToolCallCardStatus.Running
        ToolStatus.Completed -> OpenCodeToolCallCardStatus.Completed
        ToolStatus.Error -> OpenCodeToolCallCardStatus.Error
    }

private fun logToolGroupAction(actionId: String) {
    ProductAnalytics.logUserAction(
        screenId = ProductAnalytics.Screen.SESSION_TOOL_GROUP,
        actionId = actionId,
    )
}
