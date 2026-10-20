import SwiftUI

private enum OpenCodeTreeRowMetrics {
    static let indentPerDepth: CGFloat = 16
    static let leadingInset: CGFloat = 12
    static let trailingInset: CGFloat = 12
    static let disclosureSize: CGFloat = 16
    static let iconSize: CGFloat = 18
    static let spacing: CGFloat = 6
    static let minHeight: CGFloat = 40
    static let cornerRadius: CGFloat = 6
    static let dimmedOpacity: Double = 0.55
}

/// What the row represents. Folders get a disclosure chevron and a folder icon.
enum OpenCodeTreeRowKind: Equatable {
    case folder(isExpanded: Bool)
    case file
}

/// Leading icon style. `.generic` draws the catalog folder or document glyph; `.fileType`
/// draws the colour `OpenCodeFileIcon` for `path`, using the open-folder art when expanded.
enum OpenCodeTreeRowIcon: Equatable {
    case generic
    case fileType(path: String)
}

enum OpenCodeTreeRowState: Equatable {
    case normal
    case selected
    /// Children are loading; a small spinner trails the title.
    case loading
}

/// One row of a file tree: depth indent, disclosure, leading icon, title, optional subtitle,
/// and an optional dimmed treatment with a small trailing tag (such as git-ignored entries).
struct OpenCodeTreeRow: View {
    let title: String
    let subtitle: String?
    let kind: OpenCodeTreeRowKind
    let icon: OpenCodeTreeRowIcon
    let depth: Int
    let state: OpenCodeTreeRowState
    let isDimmed: Bool
    let tag: String?
    let action: () -> Void

    init(
        title: String,
        subtitle: String? = nil,
        kind: OpenCodeTreeRowKind,
        icon: OpenCodeTreeRowIcon = .generic,
        depth: Int = 0,
        state: OpenCodeTreeRowState = .normal,
        isDimmed: Bool = false,
        tag: String? = nil,
        action: @escaping () -> Void
    ) {
        self.title = title
        self.subtitle = subtitle?.isEmpty == true ? nil : subtitle
        self.kind = kind
        self.icon = icon
        self.depth = max(0, depth)
        self.state = state
        self.isDimmed = isDimmed
        self.tag = tag?.isEmpty == true ? nil : tag
        self.action = action
    }

    /// Leading inset for a row at `depth`, so inline notes under a folder line up with its children.
    static func contentInset(depth: Int) -> CGFloat {
        OpenCodeTreeRowMetrics.leadingInset
            + CGFloat(max(0, depth)) * OpenCodeTreeRowMetrics.indentPerDepth
            + OpenCodeTreeRowMetrics.disclosureSize
            + OpenCodeTreeRowMetrics.spacing
    }

    var body: some View {
        Button(action: action) {
            HStack(spacing: OpenCodeTreeRowMetrics.spacing) {
                disclosure
                leadingIcon
                VStack(alignment: .leading, spacing: 1) {
                    Text(title)
                        .font(OpenCodeTypography.bodySecondary)
                        .foregroundStyle(OpenCodeColors.textStrong)
                        .lineLimit(1)
                        .truncationMode(.middle)
                    if let subtitle {
                        Text(subtitle)
                            .font(OpenCodeTypography.caption)
                            .foregroundStyle(OpenCodeColors.textWeak)
                            .lineLimit(1)
                            .truncationMode(.head)
                    }
                }
                .opacity(isDimmed ? OpenCodeTreeRowMetrics.dimmedOpacity : 1)
                Spacer(minLength: 0)
                if let tag {
                    Text(tag)
                        .font(OpenCodeTypography.caption)
                        .foregroundStyle(OpenCodeColors.textWeak)
                        .lineLimit(1)
                        .fixedSize()
                        .padding(.horizontal, 6)
                        .padding(.vertical, 1)
                        .background(OpenCodeColors.surfaceRaisedBase, in: Capsule())
                }
                if state == .loading {
                    ProgressView()
                        .controlSize(.small)
                        .accessibilityHidden(true)
                }
            }
            .padding(.leading, OpenCodeTreeRowMetrics.leadingInset + CGFloat(depth) * OpenCodeTreeRowMetrics.indentPerDepth)
            .padding(.trailing, OpenCodeTreeRowMetrics.trailingInset)
            .frame(maxWidth: .infinity, minHeight: OpenCodeTreeRowMetrics.minHeight, alignment: .leading)
            .background {
                if state == .selected {
                    RoundedRectangle(cornerRadius: OpenCodeTreeRowMetrics.cornerRadius)
                        .fill(OpenCodeColors.surfaceRaisedBase)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityLabel)
        .accessibilityValue(accessibilityValue)
        .accessibilityAddTraits(state == .selected ? [.isButton, .isSelected] : .isButton)
    }

    @ViewBuilder
    private var disclosure: some View {
        switch kind {
        case let .folder(isExpanded):
            OpenCodeIconImage(icon: .chevronRight, size: OpenCodeTreeRowMetrics.disclosureSize)
                .foregroundStyle(OpenCodeColors.iconWeakBase)
                .rotationEffect(.degrees(isExpanded ? 90 : 0))
                .animation(.easeOut(duration: 0.15), value: isExpanded)
                .frame(width: OpenCodeTreeRowMetrics.disclosureSize)
        case .file:
            Color.clear.frame(width: OpenCodeTreeRowMetrics.disclosureSize, height: 1)
        }
    }

    @ViewBuilder
    private var leadingIcon: some View {
        switch icon {
        case .generic:
            OpenCodeIconImage(icon: genericGlyph, size: OpenCodeTreeRowMetrics.iconSize)
                .foregroundStyle(iconColor)
        case let .fileType(path):
            switch kind {
            case let .folder(isExpanded):
                OpenCodeFileIcon(path: path, kind: .directory, expanded: isExpanded, size: OpenCodeTreeRowMetrics.iconSize)
                    .opacity(isDimmed ? OpenCodeTreeRowMetrics.dimmedOpacity : 1)
            case .file:
                OpenCodeFileIcon(path: path, kind: .file, size: OpenCodeTreeRowMetrics.iconSize)
                    .opacity(isDimmed ? OpenCodeTreeRowMetrics.dimmedOpacity : 1)
            }
        }
    }

    private var genericGlyph: OpenCodeIcon {
        switch kind {
        case .folder: .folder
        case .file: .description
        }
    }

    private var iconColor: Color {
        let base: Color
        switch kind {
        case .folder: base = OpenCodeColors.iconBase
        case .file: base = OpenCodeColors.iconWeakBase
        }
        return isDimmed ? base.opacity(OpenCodeTreeRowMetrics.dimmedOpacity) : base
    }

    private var accessibilityLabel: String {
        [title, subtitle, tag].compactMap { $0 }.joined(separator: ", ")
    }

    private var accessibilityValue: String {
        switch kind {
        case let .folder(isExpanded):
            if state == .loading { return "Loading" }
            return isExpanded ? "Expanded" : "Collapsed"
        case .file:
            return ""
        }
    }
}

#Preview("Tree Row") {
    VStack(spacing: 0) {
        OpenCodeTreeRow(title: "Sources", kind: .folder(isExpanded: true)) {}
        OpenCodeTreeRow(title: "App", kind: .folder(isExpanded: false), depth: 1, state: .loading) {}
        OpenCodeTreeRow(title: "ContentView.swift", kind: .file, depth: 1, state: .selected) {}
        OpenCodeTreeRow(title: "build", kind: .folder(isExpanded: false), isDimmed: true, tag: "ignored") {}
        OpenCodeTreeRow(title: "FilesView.swift", subtitle: "ios/OpenCode Mobile/UI/Files", kind: .file) {}
    }
    .padding()
    .openCodeTheme()
}

#Preview("File Type Icons") {
    VStack(spacing: 0) {
        OpenCodeTreeRow(title: "src", kind: .folder(isExpanded: true), icon: .fileType(path: "src")) {}
        OpenCodeTreeRow(title: "App.swift", kind: .file, icon: .fileType(path: "src/App.swift"), depth: 1) {}
        OpenCodeTreeRow(title: "tests", kind: .folder(isExpanded: false), icon: .fileType(path: "tests")) {}
        OpenCodeTreeRow(title: "package.json", kind: .file, icon: .fileType(path: "package.json"), state: .selected) {}
        OpenCodeTreeRow(
            title: "node_modules",
            kind: .folder(isExpanded: false),
            icon: .fileType(path: "node_modules"),
            isDimmed: true,
            tag: "ignored"
        ) {}
    }
    .padding()
    .openCodeTheme()
}
