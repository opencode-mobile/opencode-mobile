import SwiftUI

private let runSessionInCardMaxWidth: CGFloat = 340
private let runSessionInTileSize: CGFloat = 40
private let runSessionInTileCompactSize: CGFloat = 34
private let runSessionInTileCornerRadius: CGFloat = 12
private let runSessionInTileCompactCornerRadius: CGFloat = 10
private let runSessionInTileIconSize: CGFloat = 20
private let runSessionInTileCompactIconSize: CGFloat = 17
private let runSessionInRowHorizontalPadding: CGFloat = 14
private let runSessionInRowVerticalPadding: CGFloat = 12
private let runSessionInRowCompactVerticalPadding: CGFloat = 7
private let runSessionInRowSpacing: CGFloat = 12
private let runSessionInChevronSize: CGFloat = 16
private let runSessionInCaptionTracking: CGFloat = 0.5

/// Leading tile of a Run session in row.
enum RunSessionInTile: Equatable {
    /// A project's letter avatar.
    case letter(Character, LetterAccent)
    case icon(OpenCodeIcon)
}

/// One row: a tile, a small label above a bold value, and a chevron while it can be tapped.
struct RunSessionInRow: Equatable {
    let tile: RunSessionInTile
    let label: OpenCodeText
    let value: OpenCodeText
    /// Branch names read as code.
    let monospacedValue: Bool
    /// The tile takes the selected treatment (a place other than the main checkout).
    let highlighted: Bool
    /// Disabled rows are greyed, show no chevron and take no tap.
    let enabled: Bool
    let accessibilityIdentifier: String

    init(
        tile: RunSessionInTile,
        label: OpenCodeText,
        value: OpenCodeText,
        monospacedValue: Bool = false,
        highlighted: Bool = false,
        enabled: Bool = true,
        accessibilityIdentifier: String
    ) {
        self.tile = tile
        self.label = label
        self.value = value
        self.monospacedValue = monospacedValue
        self.highlighted = highlighted
        self.enabled = enabled
        self.accessibilityIdentifier = accessibilityIdentifier
    }
}

/// Where a New session will run (#1004): a caption, one card of tappable rows separated by
/// inset dividers, and a muted checkout path under it. Keeps its phone width on wide screens.
/// `compact` (keyboard up) hides the caption and tightens the rows.
struct RunSessionInCard<ID: Hashable>: View {
    struct Entry: Identifiable {
        let id: ID
        let row: RunSessionInRow
        let action: () -> Void
    }

    let caption: OpenCodeText
    let entries: [Entry]
    /// Small monospaced line under the card, cut in the middle.
    let footnote: OpenCodeText?
    let compact: Bool

    init(caption: OpenCodeText, entries: [Entry], footnote: OpenCodeText? = nil, compact: Bool = false) {
        self.caption = caption
        self.entries = entries
        self.footnote = footnote
        self.compact = compact
    }

    var body: some View {
        VStack(alignment: .leading, spacing: compact ? 6 : 8) {
            if !compact {
                Text(openCode: caption)
                    .textCase(.uppercase)
                    .font(OpenCodeTypography.caption.weight(.semibold))
                    .tracking(runSessionInCaptionTracking)
                    .foregroundStyle(OpenCodeColors.textWeak)
                    .padding(.horizontal, 4)
                    .accessibilityAddTraits(.isHeader)
                    .transition(.opacity)
            }
            VStack(spacing: 0) {
                ForEach(entries) { entry in
                    RunSessionInRowView(row: entry.row, compact: compact, action: entry.action)
                    if entry.id != entries.last?.id {
                        Divider()
                            .overlay(OpenCodeColors.borderWeakerBase)
                            .padding(.leading, runSessionInRowHorizontalPadding + tileSize + runSessionInRowSpacing)
                    }
                }
            }
            .openCodeFocusGroup(.vertical)
            .background(OpenCodeColors.surfaceBase)
            .clipShape(RoundedRectangle(cornerRadius: OpenCodeRadius.large))
            .overlay {
                RoundedRectangle(cornerRadius: OpenCodeRadius.large)
                    .stroke(OpenCodeColors.borderWeakBase, lineWidth: 1)
            }
            if let footnote {
                Text(openCode: footnote)
                    .font(.system(size: 12, weight: .regular, design: .monospaced))
                    .foregroundStyle(OpenCodeColors.textWeaker)
                    .lineLimit(1)
                    .truncationMode(.middle)
                    .padding(.horizontal, 4)
                    .accessibilityIdentifier("runSessionInFootnote")
            }
        }
        .frame(maxWidth: runSessionInCardMaxWidth)
        .animation(.easeInOut(duration: 0.2), value: compact)
    }

    private var tileSize: CGFloat {
        compact ? runSessionInTileCompactSize : runSessionInTileSize
    }
}

private struct RunSessionInRowView: View {
    let row: RunSessionInRow
    let compact: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: runSessionInRowSpacing) {
                tile
                VStack(alignment: .leading, spacing: 1) {
                    Text(openCode: row.label)
                        .font(OpenCodeTypography.caption)
                        .foregroundStyle(OpenCodeColors.textWeak)
                        .lineLimit(1)
                    Text(openCode: row.value)
                        .font(valueFont)
                        .foregroundStyle(row.enabled ? OpenCodeColors.textStrong : OpenCodeColors.textWeaker)
                        .lineLimit(1)
                        .truncationMode(.middle)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                if row.enabled {
                    OpenCodeIconImage(icon: .chevronRight, size: runSessionInChevronSize)
                        .foregroundStyle(OpenCodeColors.iconWeakBase)
                        .accessibilityHidden(true)
                }
            }
            .padding(.horizontal, runSessionInRowHorizontalPadding)
            .padding(.vertical, compact ? runSessionInRowCompactVerticalPadding : runSessionInRowVerticalPadding)
            .contentShape(Rectangle())
        }
        .buttonStyle(RunSessionInRowPressStyle())
        .disabled(!row.enabled)
        .openCodeKeyboardFocusable(.insetRoundedRectangle(cornerRadius: OpenCodeRadius.medium, horizontalInset: 4, verticalInset: 2), enabled: row.enabled, activate: action)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier(row.accessibilityIdentifier)
    }

    private var valueFont: Font {
        row.monospacedValue
            ? .system(size: 15, weight: .semibold, design: .monospaced)
            : .system(size: 15, weight: .semibold)
    }

    private var tileSize: CGFloat {
        compact ? runSessionInTileCompactSize : runSessionInTileSize
    }

    @ViewBuilder
    private var tile: some View {
        let shape = RoundedRectangle(
            cornerRadius: compact ? runSessionInTileCompactCornerRadius : runSessionInTileCornerRadius,
            style: .continuous
        )
        switch row.tile {
        case let .letter(character, accent):
            Text(String(character).uppercased())
                .font(.system(size: compact ? 15 : 17, weight: .bold, design: .rounded))
                .foregroundStyle(accent.badgeTint)
                .frame(width: tileSize, height: tileSize)
                .background(accent.badgeFill, in: shape)
                .opacity(row.enabled ? 1 : 0.5)
                .accessibilityHidden(true)
        case let .icon(icon):
            OpenCodeIconImage(icon: icon, size: compact ? runSessionInTileCompactIconSize : runSessionInTileIconSize)
                .foregroundStyle(iconTint)
                .frame(width: tileSize, height: tileSize)
                .background(iconFill, in: shape)
                .accessibilityHidden(true)
        }
    }

    private var iconTint: Color {
        if !row.enabled { return OpenCodeColors.textWeaker }
        return row.highlighted ? OpenCodeColors.textInteractiveBase : OpenCodeColors.iconBase
    }

    private var iconFill: Color {
        row.highlighted && row.enabled ? OpenCodeColors.surfaceInteractiveWeak : OpenCodeColors.surfaceRaisedBase
    }
}

private struct RunSessionInRowPressStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background(configuration.isPressed ? OpenCodeColors.surfaceRaisedBase : Color.clear)
    }
}

#Preview("Run Session In Card") {
    VStack(spacing: 32) {
        RunSessionInCard(
            caption: "Run session in",
            entries: [
                .init(id: "project", row: RunSessionInRow(
                    tile: .letter("O", .violet), label: "Project", value: .verbatim("opencode-mobile"),
                    accessibilityIdentifier: "project"
                ), action: {}),
                .init(id: "worktree", row: RunSessionInRow(
                    tile: .icon(.add), label: "Worktree", value: "New worktree", highlighted: true,
                    accessibilityIdentifier: "worktree"
                ), action: {}),
                .init(id: "base", row: RunSessionInRow(
                    tile: .icon(.gitBranch), label: "Starts from", value: .verbatim("main"), monospacedValue: true,
                    accessibilityIdentifier: "base"
                ), action: {}),
            ],
            footnote: "Created when you send"
        )
        RunSessionInCard(
            caption: "Run session in",
            entries: [
                .init(id: "project", row: RunSessionInRow(
                    tile: .letter("N", .teal), label: "Project", value: .verbatim("notes"),
                    accessibilityIdentifier: "project"
                ), action: {}),
                .init(id: "worktree", row: RunSessionInRow(
                    tile: .icon(.folder), label: "Worktree", value: "None · not a Git repo", enabled: false,
                    accessibilityIdentifier: "worktree"
                ), action: {}),
            ],
            footnote: .verbatim("/Users/example/notes"),
            compact: true
        )
    }
    .padding()
    .openCodeTheme()
}
