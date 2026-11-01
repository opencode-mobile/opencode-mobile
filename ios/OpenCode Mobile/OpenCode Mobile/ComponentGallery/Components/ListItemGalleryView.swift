import SwiftUI

private enum GalleryDecoratorKind: String, CaseIterable, Identifiable {
    case none
    case letter
    case letterWorktree
    case icon
    case iconCircle

    var id: String { rawValue }

    var label: String {
        switch self {
        case .none: "None"
        case .letter: "Letter"
        case .letterWorktree: "Letter + worktree"
        case .icon: "Icon"
        case .iconCircle: "Icon circle"
        }
    }
}

private enum GallerySubtitleKind: String, CaseIterable, Identifiable {
    case plain
    case diff
    case branch
    case off

    var id: String { rawValue }
    var label: String { rawValue.capitalized }
}

private enum GalleryFooterKind: String, CaseIterable, Identifiable {
    case off
    case statusAndCaption
    case statusOnly
    case captionOnly

    var id: String { rawValue }

    var label: String {
        switch self {
        case .off: "Off"
        case .statusAndCaption: "Status+Caption"
        case .statusOnly: "Status only"
        case .captionOnly: "Caption only"
        }
    }
}

private enum GalleryStatusTone: String, CaseIterable, Identifiable {
    case interactive
    case success
    case warning
    case error
    case info
    case stale

    var id: String { rawValue }

    var label: String { rawValue.capitalized }

    var tone: OpenCodeListItemStatusTone {
        switch self {
        case .interactive: .interactive
        case .success: .success
        case .warning: .warning
        case .error: .error
        case .info: .info
        case .stale: .stale
        }
    }
}

struct ListItemGalleryView: View {
    @State private var selected = false
    @State private var enabled = true
    @State private var cardStyle = false
    @State private var subtitleKind: GallerySubtitleKind = .plain
    @State private var footerKind: GalleryFooterKind = .statusAndCaption
    @State private var statusTone: GalleryStatusTone = .interactive
    @State private var decoratorKind: GalleryDecoratorKind = .none
    @State private var letterAccent: LetterAccent = .violet
    @State private var toggleOn = true

    private var subtitle: String? {
        subtitleKind == .plain ? "http://127.0.0.1:4096/" : nil
    }

    private var subtitleSegments: [OpenCodeListItemSubtitleSegment]? {
        if subtitleKind == .branch {
            return [.init(text: "opencode-mobile", tone: .secondary, icon: .folder)]
        }
        guard subtitleKind == .diff else { return nil }
        return [
            .init(text: "Modified · ", tone: .secondary),
            .init(text: "+51", tone: .addition),
            .init(text: " ", tone: .secondary),
            .init(text: "−7", tone: .deletion)
        ]
    }

    private var detailSegments: [OpenCodeListItemSubtitleSegment]? {
        guard subtitleKind == .branch else { return nil }
        return [
            .init(text: "feature/723-iphone-duo", tone: .tertiary, icon: .callSplit),
            .init(text: " \u{00B7} opencode-mobile-723", tone: .tertiary)
        ]
    }

    private var status: OpenCodeListItemStatus? {
        switch footerKind {
        case .off, .captionOnly:
            nil
        case .statusAndCaption, .statusOnly:
            OpenCodeListItemStatus(text: "Working · 14m", tone: statusTone.tone)
        }
    }

    private var caption: String? {
        switch footerKind {
        case .off, .statusOnly:
            nil
        case .statusAndCaption, .captionOnly:
            "sidebar-v2-concepts"
        }
    }

    private var startDecorator: StartDecorator {
        switch decoratorKind {
        case .none:
            .none
        case .letter:
            .letter(Character("A"), letterAccent)
        case .letterWorktree:
            .letter(Character("A"), letterAccent, statusDot: statusTone.tone, worktree: true)
        case .icon:
            .icon(.folder, accessibilityLabel: "Folder")
        case .iconCircle:
            .iconCircle(.widgets, accessibilityLabel: "All projects")
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            VStack(alignment: .leading, spacing: 8) {
                Text("List item")
                    .font(OpenCodeTypography.button)
                    .foregroundStyle(OpenCodeColors.textStrong)
                HStack(spacing: 8) {
                    OpenCodeChoiceBadge(label: "Selected", selected: selected, action: { selected.toggle() })
                    OpenCodeChoiceBadge(label: "Enabled", selected: enabled, action: { enabled.toggle() })
                }
            }

            VStack(alignment: .leading, spacing: 8) {
                Text("Subtitle")
                    .font(OpenCodeTypography.button)
                    .foregroundStyle(OpenCodeColors.textStrong)
                HStack(spacing: 8) {
                    ForEach(GallerySubtitleKind.allCases) { kind in
                        OpenCodeChoiceBadge(
                            label: .verbatim(kind.label),
                            selected: subtitleKind == kind,
                            action: { subtitleKind = kind }
                        )
                    }
                }
            }

            VStack(alignment: .leading, spacing: 8) {
                Text("Footer")
                    .font(OpenCodeTypography.button)
                    .foregroundStyle(OpenCodeColors.textStrong)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(GalleryFooterKind.allCases) { kind in
                            OpenCodeChoiceBadge(
                                label: .verbatim(kind.label),
                                selected: footerKind == kind,
                                action: { footerKind = kind }
                            )
                        }
                    }
                }
            }

            if footerKind == .statusAndCaption || footerKind == .statusOnly {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Status tone")
                        .font(OpenCodeTypography.button)
                        .foregroundStyle(OpenCodeColors.textStrong)
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(GalleryStatusTone.allCases) { tone in
                                OpenCodeChoiceBadge(
                                    label: .verbatim(tone.label),
                                    selected: statusTone == tone,
                                    action: { statusTone = tone }
                                )
                            }
                        }
                    }
                }
            }

            VStack(alignment: .leading, spacing: 8) {
                Text("Style")
                    .font(OpenCodeTypography.button)
                    .foregroundStyle(OpenCodeColors.textStrong)
                HStack(spacing: 8) {
                    OpenCodeChoiceBadge(label: "Plain", selected: !cardStyle, action: { cardStyle = false })
                    OpenCodeChoiceBadge(label: "Card", selected: cardStyle, action: { cardStyle = true })
                }
            }

            VStack(alignment: .leading, spacing: 8) {
                Text("Start decorator")
                    .font(OpenCodeTypography.button)
                    .foregroundStyle(OpenCodeColors.textStrong)
                HStack(spacing: 8) {
                    ForEach(GalleryDecoratorKind.allCases) { kind in
                        OpenCodeChoiceBadge(
                            label: .verbatim(kind.label),
                            selected: decoratorKind == kind,
                            action: { decoratorKind = kind }
                        )
                    }
                }
            }

            if decoratorKind == .letter || decoratorKind == .letterWorktree {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Letter accent")
                        .font(OpenCodeTypography.button)
                        .foregroundStyle(OpenCodeColors.textStrong)
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(LetterAccent.allCases, id: \.self) { accent in
                                OpenCodeChoiceBadge(
                                    label: .verbatim(String(describing: accent).capitalized),
                                    selected: letterAccent == accent,
                                    action: { letterAccent = accent }
                                )
                            }
                        }
                    }
                }
            }

            OpenCodeListItem(
                title: "Material Design Icons integration plan",
                subtitle: .verbatim(ifPresent: subtitle),
                subtitleSegments: subtitleSegments,
                detailSegments: detailSegments,
                status: status,
                caption: .verbatim(ifPresent: caption),
                selected: selected,
                enabled: enabled,
                style: cardStyle ? .card : .plain,
                startDecorator: startDecorator
            )

            Text("Plain selection")
                .font(OpenCodeTypography.button)
                .foregroundStyle(OpenCodeColors.textStrong)

            VStack(spacing: 0) {
                OpenCodeListItem(
                    title: "Unselected session",
                    subtitle: "Plain row",
                    style: .plain,
                    action: {}
                )
                OpenCodeListItem(
                    title: "Selected session",
                    subtitle: "Inset highlight",
                    selected: true,
                    style: .plain,
                    action: {}
                )
                OpenCodeListItem(
                    title: "Unselected session",
                    subtitle: "Plain row",
                    style: .plain,
                    action: {}
                )
            }

            Text("Detail line")
                .font(OpenCodeTypography.button)
                .foregroundStyle(OpenCodeColors.textStrong)

            VStack(spacing: 0) {
                OpenCodeListItem(
                    title: "Code samples request",
                    subtitleSegments: [.init(text: "opencode-mobile", tone: .secondary, icon: .folder)],
                    detailSegments: [.init(text: "main", tone: .tertiary, icon: .callSplit)],
                    status: OpenCodeListItemStatus(text: "Done", tone: .success),
                    caption: "1h ago",
                    startDecorator: .letter(Character("O"), .red),
                    action: {}
                )
                OpenCodeListItem(
                    title: "Keep split pane in step with the sidebar",
                    subtitleSegments: [.init(text: "opencode-mobile-enterprise-edition", tone: .secondary, icon: .folder)],
                    detailSegments: [
                        .init(text: "feature/ope-1042-show-worktree-and-project-on-home-rows", tone: .tertiary, icon: .callSplit),
                        .init(text: " \u{00B7} opencode-mobile-1042", tone: .tertiary)
                    ],
                    status: OpenCodeListItemStatus(text: "Running", tone: .interactive),
                    caption: "13m ago",
                    startDecorator: .letter(Character("O"), .red, worktree: true),
                    action: {}
                )
            }

            Text("Compact")
                .font(OpenCodeTypography.button)
                .foregroundStyle(OpenCodeColors.textStrong)

            VStack(spacing: 0) {
                OpenCodeListItem(
                    variant: .compact,
                    title: "Keep split pane in step with the sidebar",
                    path: OpenCodeListItemPath(primary: "opencode-mobile", secondary: "803-compact-home-rows"),
                    status: OpenCodeListItemStatus(text: "Running", tone: .interactive),
                    caption: "now",
                    startDecorator: .compactLetter(Character("O"), .red, statusDot: .interactive, pulsing: true),
                    action: {}
                )
                OpenCodeListItem(
                    variant: .compact,
                    title: "Bump Android to 1.2.0 and iOS to 1.1.0",
                    emphasized: true,
                    path: OpenCodeListItemPath(primary: "opencode-mobile"),
                    status: OpenCodeListItemStatus(text: "Done", tone: .success),
                    caption: "3m ago",
                    selected: selected,
                    startDecorator: .compactLetter(Character("O"), .red, statusDot: .success),
                    action: {}
                )
                OpenCodeListItem(
                    variant: .compact,
                    title: "Retry the flaky upload",
                    path: OpenCodeListItemPath(
                        primary: "api-client-for-the-enterprise-billing-platform",
                        secondary: "ope-1042-show-worktree-and-project-on-home-rows"
                    ),
                    status: OpenCodeListItemStatus(text: "Retrying \u{00B7} 2", tone: .warning),
                    caption: "12m ago",
                    startDecorator: .compactLetter(Character("A"), .amber, statusDot: .warning),
                    action: {}
                )
                OpenCodeListItem(
                    variant: .compact,
                    title: "Brewfile cleanup and remove casks that are no longer used",
                    path: OpenCodeListItemPath(primary: "dotfiles"),
                    caption: "4d ago",
                    startDecorator: .compactLetter(Character("D"), .teal),
                    action: {}
                )
            }

            Text("Title tag")
                .font(OpenCodeTypography.button)
                .foregroundStyle(OpenCodeColors.textStrong)

            VStack(spacing: 0) {
                OpenCodeListItem(
                    title: "opencode-mobile",
                    subtitle: "On Home \u{00B7} 5 folders",
                    titleTag: "git",
                    startDecorator: .icon(.folder),
                    action: {}
                )
                OpenCodeListItem(
                    title: "opencode-mobile-357-split-view-chat-lifecycle-cache-android",
                    subtitle: "Empty",
                    titleTag: "git",
                    startDecorator: .icon(.folder),
                    action: {}
                )
            }

            Text("Trailing")
                .font(OpenCodeTypography.button)
                .foregroundStyle(OpenCodeColors.textStrong)

            OpenCodeListItem(
                title: "Disclosure",
                subtitle: "Enabled",
                style: .card,
                accessory: .disclosure,
                action: {}
            )
            OpenCodeListItem(
                title: "Disclosure",
                subtitle: "Disabled",
                enabled: false,
                style: .card,
                accessory: .disclosure,
                action: {}
            )
            OpenCodeListItem(
                title: "Toggle",
                subtitle: "Enabled",
                style: .card,
                accessory: .toggle(isOn: $toggleOn, accessibilityLabel: "Toggle")
            )
            OpenCodeListItem(
                title: "Toggle",
                subtitle: "Disabled",
                enabled: false,
                style: .card,
                accessory: .toggle(
                    isOn: .constant(false),
                    accessibilityLabel: "Toggle disabled"
                )
            )
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

#Preview {
    ListItemGalleryView()
        .padding(16)
        .openCodeTheme()
}
