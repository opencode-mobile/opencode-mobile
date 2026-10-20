import SwiftUI

private let actionBarVerticalPadding: CGFloat = 12
private let actionBarTargetSpacing: CGFloat = 10
private let actionBarTargetIconSize: CGFloat = 22

/// What the action bar's button acts on: an icon, a name with an optional tag, and a detail
/// line (a path) that truncates at its start so the end stays readable.
struct OpenCodeActionBarTarget: Equatable {
    let icon: OpenCodeIcon
    let title: String
    var tag: String? = nil
    let subtitle: String
}

/// Bottom-pinned bar with one full-width primary action and, above it, the thing it acts on.
/// When the action is unavailable the button stays visible, disabled, and its title says why.
/// `compact` hides the target row (for example while the keyboard is up); the button stays.
struct OpenCodeActionBar: View {
    let target: OpenCodeActionBarTarget?
    let actionTitle: String
    let enabled: Bool
    let compact: Bool
    let action: () -> Void

    init(
        target: OpenCodeActionBarTarget?,
        actionTitle: String,
        enabled: Bool = true,
        compact: Bool = false,
        action: @escaping () -> Void
    ) {
        self.target = target
        self.actionTitle = actionTitle
        self.enabled = enabled
        self.compact = compact
        self.action = action
    }

    var body: some View {
        VStack(alignment: .leading, spacing: actionBarTargetSpacing) {
            if !compact, let target {
                targetRow(target)
            }
            OpenCodeButton(
                variant: .fullWidthText(title: actionTitle),
                size: .large,
                enabled: enabled,
                action: action
            )
        }
        .padding(.horizontal, OpenCodeLayout.screenGutter)
        .padding(.vertical, actionBarVerticalPadding)
        .frame(maxWidth: .infinity)
        .background(OpenCodeColors.backgroundBase.ignoresSafeArea(edges: .bottom))
        .overlay(alignment: .top) {
            Rectangle()
                .fill(OpenCodeColors.borderWeakerBase)
                .frame(height: 1)
        }
    }

    private func targetRow(_ target: OpenCodeActionBarTarget) -> some View {
        HStack(spacing: actionBarTargetSpacing) {
            OpenCodeIconImage(icon: target.icon, size: actionBarTargetIconSize)
                .foregroundStyle(OpenCodeColors.textWeak)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(target.title)
                        .font(OpenCodeTypography.bodySecondary.weight(.semibold))
                        .foregroundStyle(OpenCodeColors.textStrong)
                        .lineLimit(1)
                    if let tag = target.tag, !tag.isEmpty {
                        OpenCodeTag(tag, size: .small)
                    }
                }
                Text(target.subtitle)
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(OpenCodeColors.textWeak)
                    .lineLimit(1)
                    .truncationMode(.head)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel([target.title, target.tag, target.subtitle].compactMap { $0 }.joined(separator: ", "))
    }
}

#if DEBUG
#Preview("Action Bar") {
    VStack(spacing: 24) {
        OpenCodeActionBar(
            target: OpenCodeActionBarTarget(
                icon: .folder,
                title: "opencode-mobile",
                tag: "git",
                subtitle: "/Users/me/Projects/opencode-mobile"
            ),
            actionTitle: "Add this folder",
            action: {}
        )
        OpenCodeActionBar(
            target: OpenCodeActionBarTarget(icon: .folder, title: "Projects", subtitle: "/Users/me/Projects"),
            actionTitle: "Already on Home",
            enabled: false,
            action: {}
        )
        OpenCodeActionBar(target: nil, actionTitle: "Add this folder", compact: true, action: {})
    }
    .openCodeTheme()
}
#endif
