package dev.opencode.mobile.ui.sessionchat.view

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ProductAnalytics
import dev.opencode.mobile.ProductPresentedScreenViewEffect
import dev.opencode.mobile.domain.preferences.SessionChatTimelineStyle
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.BottomSheetEntryDecorator
import dev.opencode.mobile.ui.components.OpenCodeBottomSheet
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetEntry
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import dev.opencode.mobile.ui.components.OpenCodeSkeletonIllustrationTopic
import dev.opencode.mobile.ui.components.openCodeBottomSheetContentInset
import dev.opencode.mobile.ui.sessionchat.SessionChatTimelineStyleOption

internal const val TIMELINE_STYLE_PICKER_TITLE = "New: choose your timeline"
internal const val TIMELINE_STYLE_PICKER_EXPLAINER = "This update adds new ways to follow what the agent is doing."
internal const val TIMELINE_STYLE_PICKER_FOOTNOTE = "You can change this any time in Settings → Timeline Style."

/** Skeleton illustration that previews each Timeline Style. */
internal val SessionChatTimelineStyle.illustrationTopic: OpenCodeSkeletonIllustrationTopic
    get() =
        when (this) {
            SessionChatTimelineStyle.Standard -> OpenCodeSkeletonIllustrationTopic.TimelineStandard
            SessionChatTimelineStyle.Chips -> OpenCodeSkeletonIllustrationTopic.TimelineChips
            SessionChatTimelineStyle.StepRail -> OpenCodeSkeletonIllustrationTopic.TimelineStepRail
            SessionChatTimelineStyle.ActivityCard -> OpenCodeSkeletonIllustrationTopic.TimelineActivityCard
        }

/**
 * One-time Timeline Style picker (#805). Tapping an entry only selects it; the pinned button
 * applies the selection. Swipe, scrim, back, and close call [onDismiss].
 */
@Composable
internal fun SessionChatTimelineStylePickerSheet(
    visible: Boolean,
    options: List<SessionChatTimelineStyleOption>,
    onUse: (SessionChatTimelineStyle) -> Unit,
    onDismiss: () -> Unit,
) {
    ProductPresentedScreenViewEffect(
        screenId = ProductAnalytics.Screen.SESSION_TIMELINE_STYLE_PICKER,
        presentation = ProductAnalytics.Presentation.SHEET,
        visible = visible,
    )
    var selectedWire by rememberSaveable { mutableStateOf(SessionChatTimelineStyleOption.INITIAL_SELECTION.wireValue) }
    LaunchedEffect(visible) {
        if (visible) selectedWire = SessionChatTimelineStyleOption.INITIAL_SELECTION.wireValue
    }
    val selected = SessionChatTimelineStyle.fromWire(selectedWire) ?: SessionChatTimelineStyleOption.INITIAL_SELECTION

    OpenCodeBottomSheet(
        visible = visible,
        title = TIMELINE_STYLE_PICKER_TITLE,
        onDismissRequest = onDismiss,
        pinnedBottom = {
            OpenCodeButton(
                variant = OpenCodeButtonVariant.FullWidthText("Use ${selected.title}"),
                onClick = { onUse(selected) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = TIMELINE_STYLE_PICKER_FOOTNOTE,
                style = MaterialTheme.typography.bodySmall,
                color = openCodeColor("text-weak"),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        },
    ) {
        Text(
            text = TIMELINE_STYLE_PICKER_EXPLAINER,
            style = MaterialTheme.typography.bodySmall,
            color = openCodeColor("text-base"),
            modifier = Modifier.openCodeBottomSheetContentInset().padding(bottom = 8.dp),
        )
        options.forEach { option ->
            OpenCodeBottomSheetEntry(
                title = option.style.title,
                subtitle = option.style.subtitle,
                tag = option.tag,
                selected = option.style == selected,
                startDecorator = BottomSheetEntryDecorator.Illustration(option.style.illustrationTopic),
                onClick = { selectedWire = option.style.wireValue },
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SessionChatTimelineStylePickerSheetPreview() {
    OpenCodeTheme(darkTheme = true) {
        SessionChatTimelineStylePickerSheet(
            visible = true,
            options =
                SessionChatTimelineStyleOption.pickerOptions(
                    available = SessionChatTimelineStyle.available(chipTimelineAvailable = true),
                    current = SessionChatTimelineStyle.Standard,
                ),
            onUse = {},
            onDismiss = {},
        )
    }
}
