package dev.opencode.mobile.ui.sessionchat.view

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ProductAnalytics
import dev.opencode.mobile.ProductPresentedScreenViewEffect
import dev.opencode.mobile.domain.message.ToolStatus
import dev.opencode.mobile.ui.components.OpenCodeBottomSheet
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetContentMode
import dev.opencode.mobile.ui.components.OpenCodeListItem
import dev.opencode.mobile.ui.components.OpenCodeListItemStatus
import dev.opencode.mobile.ui.components.OpenCodeListItemStatusTone
import dev.opencode.mobile.ui.sessionchat.SessionChatSkillUse

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
        title = "Skills",
        onDismissRequest = {
            ProductAnalytics.logUserAction(
                screenId = ProductAnalytics.Screen.SESSION_SKILLS,
                actionId = ProductAnalytics.Action.SKILLS_DISMISS,
            )
            onDismiss()
        },
        contentMode = OpenCodeBottomSheetContentMode.ActionList,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
        ) {
            target?.skills.orEmpty().forEach { skill ->
                OpenCodeListItem(
                    title = skill.label,
                    subtitle = skill.directory,
                    status = skill.status.toListItemStatus(),
                )
            }
        }
    }
}

private fun ToolStatus.toListItemStatus(): OpenCodeListItemStatus? =
    when (this) {
        ToolStatus.Completed -> null
        ToolStatus.Pending, ToolStatus.Running -> OpenCodeListItemStatus("Loading", OpenCodeListItemStatusTone.Info)
        ToolStatus.Error -> OpenCodeListItemStatus("Failed", OpenCodeListItemStatusTone.Error)
    }
