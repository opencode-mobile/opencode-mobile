import SwiftUI

struct ChildSessionRowGalleryView: View {
    @State private var showCaption = false
    @State private var showSubtitle = true
    @State private var showElapsed = true
    @State private var tappable = true

    private var caption: String? {
        showCaption ? "background" : nil
    }

    private var subtitle: String? {
        showSubtitle ? "Standards review of OPE-144" : nil
    }

    private var elapsed: String? {
        showElapsed ? "1:04" : nil
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            VStack(alignment: .leading, spacing: 8) {
                Text("Content")
                    .font(OpenCodeTypography.button)
                    .foregroundStyle(OpenCodeColors.textStrong)
                HStack(spacing: 8) {
                    OpenCodeChoiceBadge(
                        label: "Caption",
                        selected: showCaption,
                        action: { showCaption.toggle() }
                    )
                    OpenCodeChoiceBadge(
                        label: "Subtitle",
                        selected: showSubtitle,
                        action: { showSubtitle.toggle() }
                    )
                    OpenCodeChoiceBadge(
                        label: "Elapsed",
                        selected: showElapsed,
                        action: { showElapsed.toggle() }
                    )
                }
            }

            VStack(alignment: .leading, spacing: 8) {
                Text("Interaction")
                    .font(OpenCodeTypography.button)
                    .foregroundStyle(OpenCodeColors.textStrong)
                OpenCodeChoiceBadge(
                    label: "Tappable",
                    selected: tappable,
                    action: { tappable.toggle() }
                )
            }

            OpenCodeChildSessionRow(
                title: "explore",
                caption: caption,
                subtitle: subtitle,
                elapsed: elapsed,
                action: tappable ? {} : nil
            )
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

#Preview {
    ChildSessionRowGalleryView()
        .padding()
        .openCodeTheme()
}
