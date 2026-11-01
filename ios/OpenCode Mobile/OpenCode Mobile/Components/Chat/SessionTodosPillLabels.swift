import Foundation

/// Accessibility label for the todos progress pill in Session Chat's top-left corner.
func sessionTodosPillAccessibilityLabel(completedCount: Int, totalCount: Int, activePreview: String?) -> String {
    var parts = [
        "Session todos",
        "\(completedCount) of \(totalCount) completed",
    ]
    if let activePreview, !activePreview.isEmpty {
        parts.append(activePreview)
    }
    return parts.joined(separator: ", ")
}
