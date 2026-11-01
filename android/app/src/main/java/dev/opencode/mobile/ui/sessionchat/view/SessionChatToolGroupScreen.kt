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
import dev.opencode.mobile.SessionToolGroupMember
import dev.opencode.mobile.domain.message.ToolStatus
import dev.opencode.mobile.ui.components.LocalOpenCodeAttachmentImageLoader
import dev.opencode.mobile.ui.components.LocalOpenCodeGalleryImageLoader
import dev.opencode.mobile.ui.components.OpenCodeImageGallery
import dev.opencode.mobile.ui.components.OpenCodeReadableColumn
import dev.opencode.mobile.ui.components.OpenCodeToolCallCard
import dev.opencode.mobile.ui.components.OpenCodeToolCallCardStatus
import dev.opencode.mobile.ui.components.OpenCodeTopBar
import dev.opencode.mobile.ui.components.OpenCodeTopBarLeading
import dev.opencode.mobile.ui.sessionchat.SessionChatAttachmentItem
import dev.opencode.mobile.ui.sessionchat.SessionChatGalleryImageLoader
import dev.opencode.mobile.ui.sessionchat.SessionChatImageGalleries
import dev.opencode.mobile.ui.sessionchat.SessionChatImageViewerState
import dev.opencode.mobile.ui.sessionchat.SessionChatTimelineBlock

internal data class SessionChatToolGroupTarget(
    val blockID: String,
    val title: String,
    val subtitle: String?,
    val members: List<SessionChatTimelineBlock.ToolCard>,
    val messageId: String,
)

/**
 * Tool images for the tool group route, by group. The route key is saved state, so it never
 * carries image data; the Session Chat that opens a group leaves its members' images here
 * (references to the timeline's items, not copies). After process death the route shows its
 * cards without images.
 */
internal object SessionToolGroupImages {
    private const val MAX_GROUPS = 8

    private val byGroup =
        object : LinkedHashMap<String, Map<String, List<SessionChatAttachmentItem>>>(MAX_GROUPS, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, Map<String, List<SessionChatAttachmentItem>>>?,
            ): Boolean = size > MAX_GROUPS
        }

    fun put(
        sessionId: String,
        target: SessionChatToolGroupTarget,
    ) {
        val images = target.members.filter { it.images.isNotEmpty() }.associate { it.blockID to it.images }
        synchronized(byGroup) {
            if (images.isEmpty()) byGroup.remove(key(sessionId, target.blockID)) else byGroup[key(sessionId, target.blockID)] = images
        }
    }

    /** Member block id to that tool's images; empty when none were left for this group. */
    fun get(
        sessionId: String,
        blockID: String,
    ): Map<String, List<SessionChatAttachmentItem>> = synchronized(byGroup) { byGroup[key(sessionId, blockID)].orEmpty() }

    private fun key(
        sessionId: String,
        blockID: String,
    ): String = "$sessionId\u0000$blockID"
}

/** The route's target with the members' images put back from [SessionToolGroupImages]. */
internal fun SessionToolGroup.toTargetWithImages(): SessionChatToolGroupTarget {
    val target = toTarget()
    val images = SessionToolGroupImages.get(sessionId, blockID)
    if (images.isEmpty()) return target
    return target.copy(members = target.members.map { card -> images[card.blockID]?.let { card.copy(images = it) } ?: card })
}

internal fun SessionChatToolGroupTarget.toRoute(
    sessionId: String,
    worktree: String,
    parentId: String?,
    owningTurnMessageId: String?,
    supportsSessionMessageDiff: Boolean,
    lastTurnDiffUnavailable: Boolean = false,
): SessionToolGroup =
    SessionToolGroup(
        sessionId = sessionId,
        worktree = worktree,
        parentId = parentId,
        blockID = blockID,
        title = title,
        subtitle = subtitle,
        messageId = messageId,
        owningTurnMessageId = owningTurnMessageId,
        supportsSessionMessageDiff = supportsSessionMessageDiff,
        lastTurnDiffUnavailable = lastTurnDiffUnavailable,
        members = members.map { it.toRouteMember() },
    )

internal fun SessionToolGroup.toTarget(): SessionChatToolGroupTarget =
    SessionChatToolGroupTarget(
        blockID = blockID,
        title = title,
        subtitle = subtitle,
        members = members.map { it.toToolCard() },
        messageId = messageId,
    )

private fun SessionChatTimelineBlock.ToolCard.toRouteMember(): SessionToolGroupMember =
    SessionToolGroupMember(
        blockID = blockID,
        toolName = toolName,
        status = status.name,
        title = title,
        subtitle = subtitle,
        errorText = errorText,
        additions = additions,
        deletions = deletions,
        detailText = detailText,
        presentation = presentation,
        childSessionId = childSessionId,
        opensChangesReview = opensChangesReview,
        changesInitialFilePath = changesInitialFilePath,
    )

private fun SessionToolGroupMember.toToolCard(): SessionChatTimelineBlock.ToolCard =
    SessionChatTimelineBlock.ToolCard(
        blockID = blockID,
        toolName = toolName,
        status = ToolStatus.entries.firstOrNull { it.name == status } ?: ToolStatus.Completed,
        title = title,
        subtitle = subtitle,
        errorText = errorText,
        additions = additions,
        deletions = deletions,
        detailText = detailText,
        presentation = presentation,
        childSessionId = childSessionId,
        opensChangesReview = opensChangesReview,
        changesInitialFilePath = changesInitialFilePath,
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
    LaunchedEffect(target.blockID) {
        ProductAnalytics.logScreenView(
            screenId = ProductAnalytics.Screen.SESSION_TOOL_GROUP,
            presentation = ProductAnalytics.Presentation.ROUTE,
        )
    }
    // This route has no screen model; the viewer is local to it (a card's gallery and page).
    var imageViewer by remember { mutableStateOf<SessionChatImageViewerState?>(null) }
    val serverImageLoader = LocalOpenCodeAttachmentImageLoader.current
    val galleryLoader = remember(serverImageLoader) { SessionChatGalleryImageLoader(serverImageLoader) }
    galleryLoader.update(messages = emptyList(), extra = target.members.flatMap { it.images })

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
): (() -> Unit)? =
    when {
        card.childSessionId != null -> {
            { onChildSessionTap(card.childSessionId, card.title) }
        }
        card.opensChangesReview && supportsSessionMessageDiff -> {
            { onChangesReviewTap(messageId, card.changesInitialFilePath) }
        }
        card.detailText != null || card.presentation != null -> {
            {
                onToolDetailTap(
                    SessionChatToolDetailTarget(
                        title = card.presentation?.title ?: card.title,
                        body = card.detailText ?: card.presentation?.raw.orEmpty(),
                        presentation = card.presentation,
                    ),
                )
            }
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
