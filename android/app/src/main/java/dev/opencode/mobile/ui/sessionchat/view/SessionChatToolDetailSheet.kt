package dev.opencode.mobile.ui.sessionchat.view

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ProductAnalytics
import dev.opencode.mobile.ProductPresentedScreenViewEffect
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
    ProductPresentedScreenViewEffect(
        screenId = ProductAnalytics.Screen.SESSION_TOOL_DETAIL,
        presentation = ProductAnalytics.Presentation.SHEET,
        visible = target != null,
    )

    OpenCodeBottomSheet(
        visible = target != null,
        title = target?.title.orEmpty(),
        onDismissRequest = {
            ProductAnalytics.logUserAction(
                screenId = ProductAnalytics.Screen.SESSION_TOOL_DETAIL,
                actionId = ProductAnalytics.Action.TOOL_DETAIL_DISMISS,
            )
            onDismiss()
        },
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
