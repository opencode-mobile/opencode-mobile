package dev.opencode.mobile.ui.components

import dev.opencode.mobile.R
import dev.opencode.mobile.domain.text.UiText

/** Accessibility label for the todos progress pill in Session Chat's top-left corner. */
fun sessionTodosPillAccessibilityLabel(
    completedCount: Int,
    totalCount: Int,
    activePreview: String?,
): UiText {
    val preview = activePreview?.takeIf { it.isNotBlank() }
    return if (preview == null) {
        UiText.resource(R.string.component_todos_pill_accessibility_label, completedCount, totalCount)
    } else {
        UiText.resource(R.string.component_todos_pill_accessibility_label_active, completedCount, totalCount, UiText.verbatim(preview))
    }
}
