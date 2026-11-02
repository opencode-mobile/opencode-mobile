package dev.opencode.mobile.ui.sessionchat.view

import dev.opencode.mobile.domain.preferences.SessionChatTimelineStyle
import dev.opencode.mobile.ui.components.OpenCodeSkeletonIllustrationTopic
import dev.opencode.mobile.ui.components.OpenCodeTagTone
import dev.opencode.mobile.ui.sessionchat.SessionChatTimelineStyleOption
import dev.opencode.mobile.ui.text.UiText
import dev.opencode.mobile.ui.text.english
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionChatTimelineStylePickerSheetTest {
    @Test
    fun tagTone_recommendedIsAccentCurrentAndUntaggedAreNeutral() {
        val options =
            SessionChatTimelineStyleOption.pickerOptions(
                available = SessionChatTimelineStyle.available(chipTimelineAvailable = true),
                current = SessionChatTimelineStyle.StepRail,
            )

        assertEquals(
            listOf(
                SessionChatTimelineStyle.ActivityCard to OpenCodeTagTone.Accent,
                SessionChatTimelineStyle.Standard to OpenCodeTagTone.Neutral,
                SessionChatTimelineStyle.Chips to OpenCodeTagTone.Neutral,
                SessionChatTimelineStyle.StepRail to OpenCodeTagTone.Neutral,
            ),
            options.map { it.style to it.tagTone },
        )
        assertEquals(SessionChatTimelineStyleOption.CURRENT_TAG, options.last().tag)
    }

    @Test
    fun pickerText_namesEachStyleAndTag() {
        assertEquals(
            listOf("Standard", "Chips", "Step Rail", "Activity Card"),
            SessionChatTimelineStyle.entries.map { UiText.resource(it.pickerTitle).english() },
        )
        assertEquals(
            "Running steps on one live card that folds into a summary",
            UiText.resource(SessionChatTimelineStyle.ActivityCard.pickerSubtitle).english(),
        )
        val options =
            SessionChatTimelineStyleOption.pickerOptions(
                available = SessionChatTimelineStyle.available(chipTimelineAvailable = false),
                current = SessionChatTimelineStyle.StepRail,
            )
        assertEquals(
            listOf("Recommended", null, "Current"),
            options.map { option -> option.tagText?.let { UiText.resource(it).english() } },
        )
    }

    @Test
    fun illustrationTopic_matchesEachStyle() {
        assertEquals(
            listOf(
                OpenCodeSkeletonIllustrationTopic.TimelineStandard,
                OpenCodeSkeletonIllustrationTopic.TimelineChips,
                OpenCodeSkeletonIllustrationTopic.TimelineStepRail,
                OpenCodeSkeletonIllustrationTopic.TimelineActivityCard,
            ),
            SessionChatTimelineStyle.entries.map { it.illustrationTopic },
        )
    }
}
