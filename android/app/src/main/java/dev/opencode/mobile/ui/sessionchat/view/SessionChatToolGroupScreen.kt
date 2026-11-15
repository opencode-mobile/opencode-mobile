package dev.opencode.mobile.ui.sessionchat.view

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ProductAnalytics
import dev.opencode.mobile.SessionToolGroup
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.domain.message.PresentationBlockId
import dev.opencode.mobile.domain.message.ToolStatus
import dev.opencode.mobile.domain.text.UiText
import dev.opencode.mobile.ui.components.LocalOpenCodeAttachmentImageLoader
import dev.opencode.mobile.ui.components.LocalOpenCodeGalleryImageLoader
import dev.opencode.mobile.ui.components.OpenCodeImageGallery
import dev.opencode.mobile.ui.components.OpenCodeReadableColumn
import dev.opencode.mobile.ui.components.OpenCodeToolCallCard
import dev.opencode.mobile.ui.components.OpenCodeToolCallCardStatus
import dev.opencode.mobile.ui.components.OpenCodeTopBar
import dev.opencode.mobile.ui.components.OpenCodeTopBarLeading
import dev.opencode.mobile.ui.sessionchat.SessionChatGalleryImageLoader
import dev.opencode.mobile.ui.sessionchat.SessionChatImageGalleries
import dev.opencode.mobile.ui.sessionchat.SessionChatImageViewerState
import dev.opencode.mobile.ui.sessionchat.SessionChatTimelineBlock
import dev.opencode.mobile.ui.sessionchat.SessionChatTimelineBlocks
import dev.opencode.mobile.ui.text.resolve

internal data class SessionChatToolGroupTarget(
    val blockID: String,
    val title: UiText,
    val subtitle: UiText?,
    val members: List<SessionChatTimelineBlock.ToolCard>,
    val messageId: String,
)

/**
 * The route key for this group. It names the members by block id only (#1033); the route
 * resolves them from the transcript loaded under [transcriptWorktree] ([SessionToolGroupMembers]).
 */
internal fun SessionChatToolGroupTarget.toRoute(
    sessionId: String,
    worktree: String,
    transcriptWorktree: String,
    parentId: String?,
    owningTurnMessageId: String?,
    supportsSessionMessageDiff: Boolean,
    lastTurnDiffUnavailable: Boolean = false,
): SessionToolGroup =
    SessionToolGroup(
        sessionId = sessionId,
        worktree = worktree,
        transcriptWorktree = transcriptWorktree,
        parentId = parentId,
        blockID = blockID,
        title = title,
        subtitle = subtitle,
        messageId = messageId,
        owningTurnMessageId = owningTurnMessageId,
        supportsSessionMessageDiff = supportsSessionMessageDiff,
        lastTurnDiffUnavailable = lastTurnDiffUnavailable,
        memberBlockIDs = members.map { it.blockID },
    )

/** The route's target with [members] resolved from the transcript. */
internal fun SessionToolGroup.toTarget(members: List<SessionChatTimelineBlock.ToolCard>): SessionChatToolGroupTarget =
    SessionChatToolGroupTarget(
        blockID = blockID,
        title = title,
        subtitle = subtitle,
        members = members,
        messageId = messageId,
    )

/** Resolves a [SessionToolGroup] route's members from its session's messages. */
internal object SessionToolGroupMembers {
    /**
     * The member cards, projected from their tool parts as the timeline projects them, in the
     * route's order. A group spans every consecutive tool-only assistant message of its run
     * (one per step), so members are looked up across the transcript, not only in the message
     * the route names. Null when every member is gone (a revert or a compaction moved the
     * session on); members that are gone are left out.
     */
    fun resolve(
        route: SessionToolGroup,
        messages: List<Message>,
    ): List<SessionChatTimelineBlock.ToolCard>? {
        val wanted = route.memberBlockIDs.toHashSet()
        val toolsByBlockID = HashMap<String, MessagePart.Tool>(wanted.size)
        for (message in messages) {
            for (part in message.parts) {
                if (part !is MessagePart.Tool) continue
                val blockID = part.id?.let(PresentationBlockId::direct) ?: continue
                if (blockID in wanted) toolsByBlockID[blockID] = part
            }
        }
        val members = route.memberBlockIDs.mapNotNull { id -> toolsByBlockID[id]?.let(SessionChatTimelineBlocks::toolCard) }
        return members.takeIf { it.isNotEmpty() }
    }
}

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
    LaunchedEffect(target.blockID) {
        ProductAnalytics.logScreenView(
            screenId = ProductAnalytics.Screen.SESSION_TOOL_GROUP,
            presentation = ProductAnalytics.Presentation.ROUTE,
        )
    }
    // This route has no screen model; the viewer is local to it (a card's gallery and page).
    var imageViewer by remember { mutableStateOf<SessionChatImageViewerState?>(null) }
    val toolDetails = LocalSessionChatToolDetailResolver.current
    val serverImageLoader = LocalOpenCodeAttachmentImageLoader.current
    val galleryLoader = remember(serverImageLoader) { SessionChatGalleryImageLoader(serverLoader = serverImageLoader) }
    galleryLoader.update(messages = emptyList(), extra = target.members.flatMap { it.images }, extraMessageId = target.messageId)

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
    ) {
        OpenCodeTopBar(
            title = target.title.resolve(),
            subtitle = target.subtitle?.resolve(),
            leading =
                OpenCodeTopBarLeading.Back(
                    onClick = {
                        logToolGroupAction(ProductAnalytics.Action.SESSION_TOOL_GROUP_BACK)
                        onNavigateBack()
                    },
                ),
            showDivider = true,
        )
        OpenCodeReadableColumn(modifier = Modifier.weight(1f)) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                target.members.forEach { card ->
                    OpenCodeToolCallCard(
                        status = card.status.toGroupToolCallCardStatus(interrupted = card.isInterrupted),
                        title = card.title.resolve(),
                        subtitle = card.subtitle?.resolve(),
                        additions = card.additions,
                        deletions = card.deletions,
                        errorText = card.errorText?.resolve(),
                        noteText = card.noteText?.resolve(),
                        onClick =
                            toolGroupMemberOnClick(
                                card = card,
                                messageId = target.messageId,
                                supportsSessionMessageDiff = supportsSessionMessageDiff,
                                onToolDetailTap = onToolDetailTap,
                                onChildSessionTap = onChildSessionTap,
                                onChangesReviewTap = onChangesReviewTap,
                                toolDetails = toolDetails,
                            ),
                    )
                    if (card.images.isNotEmpty()) {
                        CompositionLocalProvider(LocalOpenCodeGalleryImageLoader provides galleryLoader) {
                            OpenCodeImageGallery(
                                images = card.images.map(SessionChatImageGalleries::galleryImage),
                                onImageClick = { index -> imageViewer = SessionChatImageViewerState(card.blockID, index) },
                            )
                        }
                    }
                }
            }
        }
    }
    CompositionLocalProvider(LocalOpenCodeGalleryImageLoader provides galleryLoader) {
        SessionChatImageViewerHost(
            state = imageViewer,
            images = imageViewer?.let { open -> target.members.firstOrNull { it.blockID == open.galleryId }?.images },
            loader = galleryLoader,
            onPageChange = { index -> imageViewer = imageViewer?.copy(index = index) },
            onDismiss = { imageViewer = null },
        )
    }
}

private fun toolGroupMemberOnClick(
    card: SessionChatTimelineBlock.ToolCard,
    messageId: String,
    supportsSessionMessageDiff: Boolean,
    onToolDetailTap: (SessionChatToolDetailTarget) -> Unit,
    onChildSessionTap: (sessionId: String, sessionTitle: String) -> Unit,
    onChangesReviewTap: (assistantMessageId: String, initialFilePath: String?) -> Unit,
    toolDetails: SessionChatToolDetailResolver,
): (() -> Unit)? =
    when {
        card.childSessionId != null -> {
            { onChildSessionTap(card.childSessionId, card.childSessionTitle) }
        }
        card.opensChangesReview && supportsSessionMessageDiff -> {
            { onChangesReviewTap(messageId, card.changesInitialFilePath) }
        }
        SessionChatToolDetails.opensDetail(card) -> {
            { toolDetails.detail(card)?.let(onToolDetailTap) }
        }
        else -> null
    }

private fun ToolStatus.toGroupToolCallCardStatus(interrupted: Boolean = false): OpenCodeToolCallCardStatus =
    when {
        this == ToolStatus.Error && interrupted -> OpenCodeToolCallCardStatus.Interrupted
        else -> toGroupToolCallCardStatusBase()
    }

private fun ToolStatus.toGroupToolCallCardStatusBase(): OpenCodeToolCallCardStatus =
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
