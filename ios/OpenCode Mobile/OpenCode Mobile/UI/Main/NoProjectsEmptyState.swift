import SwiftUI

struct NoProjectsEmptyState: View {
    var body: some View {
        OpenCodeEmptyState(
            title: "home.empty.title",
            message: "home.empty.message",
            icon: .createNewFolder
        )
    }
}
