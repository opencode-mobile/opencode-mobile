package dev.opencode.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.OpenCodeSmallShape
import dev.opencode.mobile.theme.openCodeColor

enum class OpenCodeInlineNoticeTone {
    Neutral,
    Error,
}

enum class OpenCodeInlineNoticeVariant {
    /** One line of type, no fill. */
    Plain,

    /** Padded tonal fill for a banner. */
    Banner,
}

data class OpenCodeInlineNoticeAction(
    val title: String,
    val onClick: () -> Unit,
)

/** Tonal inline message. An optional trailing action sits on the same row. */
@Composable
fun OpenCodeInlineNotice(
    message: String,
    modifier: Modifier = Modifier,
    tone: OpenCodeInlineNoticeTone = OpenCodeInlineNoticeTone.Neutral,
    variant: OpenCodeInlineNoticeVariant = OpenCodeInlineNoticeVariant.Plain,
    action: OpenCodeInlineNoticeAction? = null,
) {
    require(message.isNotBlank()) { "Inline Notice requires a non-blank message" }
    action?.let { require(it.title.isNotBlank()) { "Inline Notice action requires a non-blank title" } }

    val foreground = tone.foreground()
    val shape = OpenCodeSmallShape
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .then(
                    if (variant == OpenCodeInlineNoticeVariant.Banner) {
                        Modifier
                            .clip(shape)
                            .background(tone.bannerFill(), shape)
                            .padding(12.dp)
                    } else {
                        Modifier
                    },
                ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = foreground,
        )
        if (action != null) {
            OpenCodeButton(
                variant = OpenCodeButtonVariant.CompactText(action.title),
                onClick = action.onClick,
                contrast = OpenCodeButtonContrast.Low,
            )
        }
    }
}

@Composable
private fun OpenCodeInlineNoticeTone.foreground(): Color =
    when (this) {
        OpenCodeInlineNoticeTone.Neutral -> openCodeColor("text-base")
        OpenCodeInlineNoticeTone.Error -> openCodeColor("error")
    }

@Composable
private fun OpenCodeInlineNoticeTone.bannerFill(): Color =
    when (this) {
        OpenCodeInlineNoticeTone.Neutral -> openCodeColor("surface-raised-base")
        OpenCodeInlineNoticeTone.Error -> openCodeColor("error").copy(alpha = 0.1f)
    }

@Preview(showBackground = true)
@Composable
private fun OpenCodeInlineNoticePreview() {
    OpenCodeTheme {
        OpenCodeInlineNotice(
            message = "Refresh failed: offline",
            action = OpenCodeInlineNoticeAction(title = "Retry", onClick = {}),
        )
    }
}
