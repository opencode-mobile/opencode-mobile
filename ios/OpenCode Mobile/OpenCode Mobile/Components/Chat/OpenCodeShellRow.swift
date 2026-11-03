import SwiftUI

/// A running shell command in a list, next to `OpenCodeChildSessionRow`: a terminal
/// glyph where the agent letter sits, the command in monospace on one line, what it
/// does below when known, and a live timer. Tappable rows show a chevron.
struct OpenCodeShellRow: View {
    let command: String
    let description: String?
    let elapsed: String?
    let action: (() -> Void)?

    init(
        command: String,
        description: String? = nil,
        elapsed: String? = nil,
        action: (() -> Void)? = nil
    ) {
        self.command = command
        self.description = description
        self.elapsed = elapsed
        self.action = action
    }

    var body: some View {
        Group {
            if let action {
                Button(action: action) {
                    rowContent
                }
                .buttonStyle(.plain)
                .openCodeKeyboardFocusable(.roundedRectangle(cornerRadius: Self.cornerRadius), activate: action)
            } else {
                rowContent
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(buildShellRowAccessibilityLabel(command: command, description: description, elapsed: elapsed))
        .accessibilityAddTraits(action == nil ? [] : .isButton)
    }

    private var rowContent: some View {
        HStack(alignment: .center, spacing: Self.contentSpacing) {
            OpenCodeIconImage(icon: .terminal, size: Self.glyphSize)
                .foregroundStyle(OpenCodeColors.textStrong)
                .frame(width: Self.avatarSize, height: Self.avatarSize)
                .background(OpenCodeColors.surfaceRaisedBase, in: Circle())
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: Self.textStackSpacing) {
                Text(command.isEmpty ? " " : command)
                    .font(OpenCodeTypography.code)
                    .foregroundStyle(OpenCodeColors.textStrong)
                    .lineLimit(1)
                    .truncationMode(.tail)
                if let description, !description.isEmpty {
                    Text(description)
                        .font(OpenCodeTypography.caption)
                        .foregroundStyle(OpenCodeColors.textBase)
                        .lineLimit(1)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            HStack(spacing: Self.trailingSpacing) {
                if let elapsed, !elapsed.isEmpty {
                    Text(elapsed)
                        .font(OpenCodeTypography.caption.monospacedDigit())
                        .foregroundStyle(OpenCodeColors.textWeaker)
                }
                if action != nil {
                    OpenCodeIconImage(icon: .chevronRight, size: Self.chevronSize)
                        .foregroundStyle(OpenCodeColors.iconStrongBase)
                        .accessibilityHidden(true)
                }
            }
            // The timer stays whole; the command truncates first.
            .fixedSize(horizontal: true, vertical: false)
        }
        .padding(.horizontal, Self.paddingHorizontal)
        .padding(.vertical, Self.paddingVertical)
        .background(
            RoundedRectangle(cornerRadius: Self.cornerRadius, style: .continuous)
                .fill(OpenCodeColors.surfaceBase)
                .overlay(
                    RoundedRectangle(cornerRadius: Self.cornerRadius, style: .continuous)
                        .strokeBorder(OpenCodeColors.borderWeakerBase, lineWidth: 1)
                )
        )
    }

    // Matches `OpenCodeChildSessionRow` so both kinds of row line up in one panel.
    private static let cornerRadius: CGFloat = 8
    private static let paddingHorizontal: CGFloat = 12
    private static let paddingVertical: CGFloat = 10
    private static let avatarSize: CGFloat = 32
    private static let glyphSize: CGFloat = 16
    private static let contentSpacing: CGFloat = 12
    private static let textStackSpacing: CGFloat = 2
    private static let trailingSpacing: CGFloat = 6
    private static let chevronSize: CGFloat = 16
}

/// "Shell, npm test, Run the tests, 1:04".
func buildShellRowAccessibilityLabel(command: String, description: String?, elapsed: String?) -> String {
    var parts = ["Shell", command]
    if let description, !description.isEmpty { parts.append(description) }
    if let elapsed, !elapsed.isEmpty { parts.append(elapsed) }
    return parts.joined(separator: ", ")
}

#if DEBUG
#Preview("Shell Row") {
    ScrollView {
        VStack(alignment: .leading, spacing: 12) {
            OpenCodeShellRow(command: "npm test", description: "Run the test suite", elapsed: "1:04", action: {})
            OpenCodeShellRow(
                command: "for i in $(seq 1 40); do echo line $i; sleep 0.5; done && ./scripts/build.sh --release",
                elapsed: "0:12",
                action: {}
            )
            OpenCodeShellRow(command: "git status")
        }
        .padding()
    }
    .openCodeTheme()
}
#endif
