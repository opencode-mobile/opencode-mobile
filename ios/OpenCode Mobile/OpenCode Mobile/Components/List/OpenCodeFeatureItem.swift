import SwiftUI

private let featureItemTileSize: CGFloat = 40
private let featureItemIconSize: CGFloat = 20
private let featureItemSpacing: CGFloat = 14
private let featureItemVerticalPadding: CGFloat = 12
private let featureItemTitleBodySpacing: CGFloat = 2

/// Treatment of a Feature Item's icon tile.
enum OpenCodeFeatureItemTone: Equatable {
    /// Something added or improved.
    case neutral
    /// Something fixed.
    case success
}

/// Read-only row describing one feature or change: a rounded-square icon
/// tile, a semibold title and a secondary body that wraps. Used by release
/// notes; it carries no tap action and lays out no horizontal inset.
struct OpenCodeFeatureItem: View {
    let icon: OpenCodeIcon
    let title: OpenCodeText
    let bodyText: OpenCodeText
    var tone: OpenCodeFeatureItemTone = .neutral

    init(
        icon: OpenCodeIcon,
        title: OpenCodeText,
        body: OpenCodeText,
        tone: OpenCodeFeatureItemTone = .neutral
    ) {
        self.icon = icon
        self.title = title
        self.bodyText = body
        self.tone = tone
    }

    var body: some View {
        HStack(alignment: .top, spacing: featureItemSpacing) {
            OpenCodeIconImage(icon: icon, size: featureItemIconSize)
                .foregroundStyle(tone == .success ? OpenCodeColors.success : OpenCodeColors.iconStrongBase)
                .frame(width: featureItemTileSize, height: featureItemTileSize)
                .background(
                    tone == .success ? OpenCodeColors.success.opacity(0.14) : OpenCodeColors.surfaceRaisedBase,
                    in: RoundedRectangle(cornerRadius: OpenCodeRadius.medium, style: .continuous)
                )
                .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: featureItemTitleBodySpacing) {
                Text(openCode: title)
                    .font(OpenCodeTypography.body.weight(.semibold))
                    .foregroundStyle(OpenCodeColors.textStrong)
                    .fixedSize(horizontal: false, vertical: true)
                if !bodyText.isEmpty {
                    Text(openCode: bodyText)
                        .font(OpenCodeTypography.bodySecondary)
                        .foregroundStyle(OpenCodeColors.textBase)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.vertical, featureItemVerticalPadding)
        .accessibilityElement(children: .combine)
    }
}

#Preview("Feature Item") {
    VStack(spacing: 0) {
        OpenCodeFeatureItem(
            icon: .edit,
            title: .verbatim("Start a session as a draft"),
            body: .verbatim("Write first, then pick the project and worktree.")
        )
        OpenCodeFeatureItem(
            icon: .check,
            title: .verbatim("Steadier reconnects"),
            body: .verbatim("Session Chat picks up where it left off."),
            tone: .success
        )
    }
    .padding()
    .openCodeTheme()
}
