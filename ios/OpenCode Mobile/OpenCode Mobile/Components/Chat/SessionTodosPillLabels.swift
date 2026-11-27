import Foundation

/// Accessibility label for the todos progress pill in Session Chat's top-left corner.
func sessionTodosPillAccessibilityLabel(completedCount: Int, totalCount: Int, activePreview: String?) -> String {
    if let activePreview, !activePreview.isEmpty {
        return String(
            localized: "component.todos_pill.accessibility_label_active",
            defaultValue: "Session todos, \(completedCount) of \(totalCount) completed, \(activePreview)"
        )
    }
    return String(
        localized: "component.todos_pill.accessibility_label",
        defaultValue: "Session todos, \(completedCount) of \(totalCount) completed"
    )
}
