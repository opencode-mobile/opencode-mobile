package dev.opencode.mobile.ui.components

/** Accessibility label for the todos progress pill in Session Chat's top-left corner. */
fun sessionTodosPillAccessibilityLabel(
    completedCount: Int,
    totalCount: Int,
    activePreview: String?,
): String {
    val parts =
        buildList {
            add("Session todos")
            add("$completedCount of $totalCount completed")
            activePreview?.takeIf { it.isNotBlank() }?.let(::add)
        }
    return parts.joinToString(", ")
}
