package dev.opencode.mobile.ui.sessionchat.view

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.opencode.mobile.ProductAnalytics
import dev.opencode.mobile.ProductPresentedScreenViewEffect
import dev.opencode.mobile.R
import dev.opencode.mobile.domain.message.ToolStatus
import dev.opencode.mobile.ui.components.OpenCodeBottomSheet
import dev.opencode.mobile.ui.components.OpenCodeListItem
import dev.opencode.mobile.ui.components.OpenCodeListItemStatus
import dev.opencode.mobile.ui.components.OpenCodeListItemStatusTone
import dev.opencode.mobile.ui.sessionchat.SessionChatSkillUse
import dev.opencode.mobile.ui.text.resolve

/** Skills a message loaded, opened from any skill chip in the chip timeline. */
internal data class SessionChatSkillsTarget(
    val skills: List<SessionChatSkillUse>,
)

@Composable
internal fun SessionChatSkillsSheet(
    target: SessionChatSkillsTarget?,
    onDismiss: () -> Unit,
) {
    ProductPresentedScreenViewEffect(
        screenId = ProductAnalytics.Screen.SESSION_SKILLS,
        presentation = ProductAnalytics.Presentation.SHEET,
        visible = target != null,
    )

    OpenCodeBottomSheet(
        visible = target != null,
        title = stringResource(R.string.session_chat_sheet_skills_title),
        onDismissRequest = {
            ProductAnalytics.logUserAction(
                screenId = ProductAnalytics.Screen.SESSION_SKILLS,
                actionId = ProductAnalytics.Action.SKILLS_DISMISS,
            )
            onDismiss()
        },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            target?.skills.orEmpty().forEach { skill ->
                OpenCodeListItem(
                    title = skill.label.resolve(),
                    subtitle = skill.directory,
                    status = skill.status.toListItemStatus(),
                )
            }
        }
    }
}

@Composable
private fun ToolStatus.toListItemStatus(): OpenCodeListItemStatus? =
    when (this) {
        ToolStatus.Completed -> null
        ToolStatus.Pending, ToolStatus.Running ->
            OpenCodeListItemStatus(
                stringResource(R.string.session_chat_sheet_skills_status_loading),
                OpenCodeListItemStatusTone.Info,
            )
        ToolStatus.Error ->
            OpenCodeListItemStatus(
                stringResource(R.string.session_chat_sheet_skills_status_failed),
                OpenCodeListItemStatusTone.Error,
            )
    }
