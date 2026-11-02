package dev.opencode.mobile.ui.sessionchat.view

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ProductAnalytics
import dev.opencode.mobile.ProductPresentedScreenViewEffect
import dev.opencode.mobile.R
import dev.opencode.mobile.domain.preferences.SessionChatTimelineStyle
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.OpenCodeBottomSheet
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetHeight
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import dev.opencode.mobile.ui.components.OpenCodeOptionCard
import dev.opencode.mobile.ui.components.OpenCodeOptionCardPreview
import dev.opencode.mobile.ui.components.OpenCodeSkeletonIllustrationTopic
import dev.opencode.mobile.ui.components.OpenCodeTagTone
import dev.opencode.mobile.ui.components.openCodeBottomSheetContentInset
import dev.opencode.mobile.ui.sessionchat.SessionChatTimelineStyleOption

/** Skeleton illustration that previews each Timeline Style. */
internal val SessionChatTimelineStyle.illustrationTopic: OpenCodeSkeletonIllustrationTopic
    get() =
        when (this) {
            SessionChatTimelineStyle.Standard -> OpenCodeSkeletonIllustrationTopic.TimelineStandard
            SessionChatTimelineStyle.Chips -> OpenCodeSkeletonIllustrationTopic.TimelineChips
            SessionChatTimelineStyle.StepRail -> OpenCodeSkeletonIllustrationTopic.TimelineStepRail
            SessionChatTimelineStyle.ActivityCard -> OpenCodeSkeletonIllustrationTopic.TimelineActivityCard
        }

/** Picker name of each Timeline Style. */
@get:StringRes
internal val SessionChatTimelineStyle.pickerTitle: Int
    get() =
        when (this) {
            SessionChatTimelineStyle.Standard -> R.string.session_chat_sheet_timeline_style_picker_standard_title
            SessionChatTimelineStyle.Chips -> R.string.session_chat_sheet_timeline_style_picker_chips_title
            SessionChatTimelineStyle.StepRail -> R.string.session_chat_sheet_timeline_style_picker_step_rail_title
            SessionChatTimelineStyle.ActivityCard -> R.string.session_chat_sheet_timeline_style_picker_activity_card_title
        }

/** Picker description of each Timeline Style. */
@get:StringRes
internal val SessionChatTimelineStyle.pickerSubtitle: Int
    get() =
        when (this) {
            SessionChatTimelineStyle.Standard -> R.string.session_chat_sheet_timeline_style_picker_standard_subtitle
            SessionChatTimelineStyle.Chips -> R.string.session_chat_sheet_timeline_style_picker_chips_subtitle
            SessionChatTimelineStyle.StepRail -> R.string.session_chat_sheet_timeline_style_picker_step_rail_subtitle
            SessionChatTimelineStyle.ActivityCard -> R.string.session_chat_sheet_timeline_style_picker_activity_card_subtitle
        }

/** Tag text for the option's [SessionChatTimelineStyleOption.tag] marker; null when untagged. */
@get:StringRes
internal val SessionChatTimelineStyleOption.tagText: Int?
    get() =
        when (tag) {
            SessionChatTimelineStyleOption.RECOMMENDED_TAG -> R.string.session_chat_sheet_timeline_style_picker_recommended
            SessionChatTimelineStyleOption.CURRENT_TAG -> R.string.session_chat_sheet_timeline_style_picker_current
            else -> null
        }

/** "Recommended" draws the eye with the accent tone; "Current" stays neutral. */
internal val SessionChatTimelineStyleOption.tagTone: OpenCodeTagTone
    get() = if (tag == SessionChatTimelineStyleOption.RECOMMENDED_TAG) OpenCodeTagTone.Accent else OpenCodeTagTone.Neutral

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
        title = stringResource(R.string.session_chat_sheet_timeline_style_picker_title),
        onDismissRequest = onDismiss,
        // Every style stays on screen; only a window too short for all of them scrolls.
        height = OpenCodeBottomSheetHeight.FitAllContent,
        pinnedBottom = {
            OpenCodeButton(
                variant =
                    OpenCodeButtonVariant.FullWidthText(
                        stringResource(
                            R.string.session_chat_sheet_timeline_style_picker_use,
                            stringResource(selected.pickerTitle),
                        ),
                    ),
                onClick = { onUse(selected) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(R.string.session_chat_sheet_timeline_style_picker_footnote),
                style = MaterialTheme.typography.bodySmall,
                color = openCodeColor("text-weak"),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        },
    ) {
        Text(
            text = stringResource(R.string.session_chat_sheet_timeline_style_picker_explainer),
            style = MaterialTheme.typography.bodySmall,
            color = openCodeColor("text-base"),
            modifier = Modifier.openCodeBottomSheetContentInset().padding(bottom = 8.dp),
        )
        Column(
            modifier = Modifier.openCodeBottomSheetContentInset(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { option ->
                OpenCodeOptionCard(
                    title = stringResource(option.style.pickerTitle),
                    subtitle = stringResource(option.style.pickerSubtitle),
                    tag = option.tagText?.let { stringResource(it) },
                    tagTone = option.tagTone,
                    preview = OpenCodeOptionCardPreview.Illustration(option.style.illustrationTopic),
                    selected = option.style == selected,
                    onClick = { selectedWire = option.style.wireValue },
                )
            }
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
