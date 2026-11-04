import OpenCodeKit
import SwiftUI

// Hex Code's first-run building blocks, on OpenCodeKit's theme tokens. OpenCode Mobile's
// OpenCodeButton and OpenCodeListItem are wired to its hardware-keyboard focus system, which
// has not moved to OpenCodeKit yet, so these stay local to Hex Code until it does.

/// Every first-run screen sits on one edge, as in the prototype.
enum HexLayout {
    static let edge: CGFloat = 18
}

/// The one full-width action at the bottom of a screen.
struct HexPrimaryButton: View {
    let title: OpenCodeText
    var enabled = true
    var busy = false
    let action: () -> Void

    init(title: LocalizedStringResource, enabled: Bool = true, busy: Bool = false, action: @escaping () -> Void) {
        self.init(text: OpenCodeText(title), enabled: enabled, busy: busy, action: action)
    }

    init(text: OpenCodeText, enabled: Bool = true, busy: Bool = false, action: @escaping () -> Void) {
        self.title = text
        self.enabled = enabled
        self.busy = busy
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                if busy {
                    ProgressView()
                        .tint(OpenCodeColors.textOnBrandBase)
                }
                Text(openCode: title)
                    .font(.system(size: 15, weight: .semibold))
            }
            .frame(maxWidth: .infinity, minHeight: 50)
            .foregroundStyle(OpenCodeColors.textOnBrandBase)
            .background(OpenCodeColors.buttonPrimaryBase, in: RoundedRectangle(cornerRadius: OpenCodeRadius.medium, style: .continuous))
            .opacity(enabled ? 1 : 0.25)
        }
        .buttonStyle(HexPressStyle())
        .disabled(!enabled || busy)
    }
}

/// Small scale on press, like the prototype's rows and buttons.
struct HexPressStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.98 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

/// A tappable list row: icon, title with an optional tag, subtitle, and a trailing mark.
struct HexRow: View {
    enum Trailing {
        case none
        case chevron
        case check
        case caption(String)
    }

    var icon: OpenCodeIcon?
    let title: String
    var titleWeight: Font.Weight = .semibold
    var tag: OpenCodeText?
    var subtitle: String?
    var trailing: Trailing = .none
    var minHeight: CGFloat = 62
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                if let icon {
                    OpenCodeIconImage(icon: icon, size: 20)
                        .foregroundStyle(OpenCodeColors.textStrong)
                        .frame(width: 24)
                }
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 7) {
                        Text(verbatim: title)
                            .font(.system(size: 15.5, weight: titleWeight))
                            .foregroundStyle(OpenCodeColors.textStrong)
                            .lineLimit(1)
                        if let tag {
                            OpenCodeTag(tag, size: .small)
                        }
                    }
                    if let subtitle {
                        Text(verbatim: subtitle)
                            .font(.system(size: 13))
                            .foregroundStyle(OpenCodeColors.textWeak)
                            .lineLimit(1)
                            .truncationMode(.middle)
                    }
                }
                Spacer(minLength: 8)
                trailingView
            }
            .padding(.horizontal, HexLayout.edge)
            .frame(maxWidth: .infinity, minHeight: minHeight, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(HexRowStyle())
    }

    @ViewBuilder
    private var trailingView: some View {
        switch trailing {
        case .none:
            EmptyView()
        case .chevron:
            OpenCodeIconImage(icon: .chevronRight, size: 16)
                .foregroundStyle(OpenCodeColors.textWeak)
        case .check:
            OpenCodeIconImage(icon: .check, size: 18)
                .foregroundStyle(OpenCodeColors.textStrong)
        case let .caption(text):
            Text(verbatim: text)
                .font(.system(size: 12.5))
                .foregroundStyle(OpenCodeColors.textWeak)
        }
    }
}

struct HexRowStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background(configuration.isPressed ? OpenCodeColors.surfaceBaseHover : .clear)
    }
}

/// Uppercase section label over a group of rows.
struct HexSectionLabel: View {
    let title: OpenCodeText

    var body: some View {
        Text(openCode: title)
            .textCase(.uppercase)
            .font(.system(size: 12, weight: .semibold))
            .tracking(0.7)
            .foregroundStyle(OpenCodeColors.textWeaker)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, HexLayout.edge)
            .padding(.top, 18)
            .padding(.bottom, 6)
            .accessibilityAddTraits(.isHeader)
    }
}

/// Back button, title, and a quiet line under it naming the server.
struct HexTopBar: View {
    let title: LocalizedStringResource
    var subtitle: String?
    var onBack: (() -> Void)?

    var body: some View {
        HStack(spacing: 10) {
            if let onBack {
                Button(action: onBack) {
                    OpenCodeIconImage(icon: .chevronLeft, size: 18)
                        .foregroundStyle(OpenCodeColors.textStrong)
                        .frame(width: 36, height: 36)
                        .background(OpenCodeColors.surfaceRaisedBase, in: Circle())
                }
                .buttonStyle(HexPressStyle())
                .accessibilityLabel(Text("common.back", comment: "Back button"))
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.system(size: 20, weight: .semibold))
                    .foregroundStyle(OpenCodeColors.textStrong)
                if let subtitle {
                    HStack(spacing: 6) {
                        Circle().fill(OpenCodeColors.success).frame(width: 6, height: 6)
                        Text(verbatim: subtitle)
                            .font(.system(size: 12.5))
                            .foregroundStyle(OpenCodeColors.textWeak)
                    }
                }
            }
            Spacer()
        }
        .padding(.horizontal, HexLayout.edge)
        .frame(height: 56)
        .padding(.top, 4)
    }
}

/// Inline monospaced command, as in "run `opencode pair`".
struct HexCommand: View {
    let command: String

    var body: some View {
        Text(verbatim: command)
            .font(.system(size: 13, design: .monospaced))
            .foregroundStyle(OpenCodeColors.textStrong)
            .padding(.horizontal, 9)
            .padding(.vertical, 5)
            .background(OpenCodeColors.surfaceRaisedBase, in: RoundedRectangle(cornerRadius: 7, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 7, style: .continuous)
                    .stroke(OpenCodeColors.borderWeakBase, lineWidth: 1)
            )
            .textSelection(.enabled)
    }
}
