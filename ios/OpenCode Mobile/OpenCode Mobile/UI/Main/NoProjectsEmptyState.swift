import SwiftUI

enum NoProjectsEmptyCopy {
    static let title = "No projects yet"
    static let message = "Tap the Add project icon below to choose a folder on your server."
}

struct NoProjectsEmptyState: View {
    var body: some View {
        OpenCodeEmptyState(
            title: .unlocalized(NoProjectsEmptyCopy.title),
            message: .unlocalized(NoProjectsEmptyCopy.message),
            icon: .createNewFolder
        )
    }
}
