package dev.opencode.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class OpenCodeToolCallCardTest {
    @Test
    fun accessibilityLabelIncludesStatusTitleSubtitleCountsAndError() {
        val label =
            buildToolCallCardAccessibilityLabel(
                status = OpenCodeToolCallCardStatus.Completed,
                title = "Called edit",
                subtitle = "OpenCodeListItem.kt",
                additions = 12,
                deletions = 3,
                errorText = null,
            )

        assertEquals("completed, Called edit, OpenCodeListItem.kt, +12, −3", label)
    }

    @Test
    fun accessibilityLabelOmitsZeroCountsAndEmptyOptionalFields() {
        val label =
            buildToolCallCardAccessibilityLabel(
                status = OpenCodeToolCallCardStatus.Error,
                title = "Called webfetch",
                subtitle = null,
                additions = 0,
                deletions = null,
                errorText = "Request failed with status 403",
            )

        assertEquals("error, Called webfetch, Request failed with status 403", label)
    }

    @Test
    fun accessibilityLabelUsesTitleOnlyWhenNoOptionalContent() {
        val label =
            buildToolCallCardAccessibilityLabel(
                status = OpenCodeToolCallCardStatus.Pending,
                title = "Called skill",
                subtitle = "",
                additions = null,
                deletions = null,
                errorText = "",
            )

        assertEquals("pending, Called skill", label)
    }
}
