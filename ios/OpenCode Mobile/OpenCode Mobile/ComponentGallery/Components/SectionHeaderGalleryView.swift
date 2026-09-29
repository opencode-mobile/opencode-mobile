import SwiftUI

struct SectionHeaderGalleryView: View {
    @State private var clearCount = 0

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            galleryHeading("Label", detail: "Uppercase group label above rows.")
            VStack(spacing: 0) {
                OpenCodeSectionHeader(title: "Server")
                OpenCodeListItem(title: "Servers", subtitle: "Manage connection profiles")
                OpenCodeListItem(title: "Reconnect on launch", subtitle: "Restore the last live session")
            }

            galleryHeading("Plain with count", detail: "Free text kept as written, with a quiet item count.")
            VStack(spacing: 0) {
                OpenCodeSectionHeader(
                    title: "Sources/App/Features/SessionChat",
                    variant: .plain,
                    trailing: .count(3)
                )
                OpenCodeListGroup(["SessionChatView.swift", "SessionChatViewModel.swift", "Timeline.swift"], id: \.self) { path in
                    OpenCodeListItem(title: path)
                }
                .padding(.horizontal, 16)
            }

            galleryHeading("Plain with action", detail: "Compact text action scoped to the group below.")
            VStack(spacing: 0) {
                OpenCodeSectionHeader(
                    title: "\(max(3 - clearCount, 0)) waiting",
                    variant: .plain,
                    trailing: .action("Clear all", { clearCount += 1 })
                )
                OpenCodeListItem(title: "Add unit tests for the parser")
                OpenCodeListItem(title: "Update the README")
            }

            galleryHeading("Flush", detail: "No horizontal inset when the container already positions it.")
            VStack(alignment: .leading, spacing: 0) {
                OpenCodeSectionHeader(title: "Context breakdown", inset: .flush)
                RoundedRectangle(cornerRadius: 4)
                    .fill(OpenCodeColors.borderWeakBase)
                    .frame(height: 8)
            }
            .padding(.horizontal, 16)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func galleryHeading(_ title: String, detail: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title)
                .font(OpenCodeTypography.heading)
                .foregroundStyle(OpenCodeColors.textStrong)
            Text(detail)
                .font(OpenCodeTypography.bodySecondary)
                .foregroundStyle(OpenCodeColors.textBase)
        }
    }
}

#Preview {
    SectionHeaderGalleryView()
        .padding(.vertical)
        .openCodeTheme()
}
