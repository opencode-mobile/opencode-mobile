package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.components.OpenCodeInlineNotice
import dev.opencode.mobile.ui.components.OpenCodeInlineNoticeAction
import dev.opencode.mobile.ui.components.OpenCodeInlineNoticeTone
import dev.opencode.mobile.ui.components.OpenCodeInlineNoticeVariant

@Composable
fun InlineNoticeGalleryScreen(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OpenCodeInlineNotice(message = "Selected model is unavailable.")
        OpenCodeInlineNotice(
            message = "Couldn't prepare diagnostic log for sharing.",
            tone = OpenCodeInlineNoticeTone.Error,
        )
        OpenCodeInlineNotice(
            message = "Sessions stay in Recent while Running or Ready.",
            variant = OpenCodeInlineNoticeVariant.Banner,
        )
        OpenCodeInlineNotice(
            message = "12 entries dropped under writer pressure.",
            tone = OpenCodeInlineNoticeTone.Error,
            variant = OpenCodeInlineNoticeVariant.Banner,
        )
        OpenCodeInlineNotice(
            message = "Refresh failed: offline",
            action = OpenCodeInlineNoticeAction(title = "Retry", onClick = {}),
        )
        OpenCodeInlineNotice(
            message = "Storage error: disk full.",
            tone = OpenCodeInlineNoticeTone.Error,
            variant = OpenCodeInlineNoticeVariant.Banner,
            action = OpenCodeInlineNoticeAction(title = "Retry", onClick = {}),
        )
    }
}
