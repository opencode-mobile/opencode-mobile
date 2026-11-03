import SwiftUI

/// Where a background task (a subagent) is; drives the leading glyph and the detail tone.
enum OpenCodeTaskRowState: CaseIterable, Equatable {
    /// Spinner.
    case running
    /// Amber dot and amber detail: the task waits on a permission or question.
    case waitingForInput
    /// Check mark.
    case done
    /// `!` and the detail (the reason) in the error tone.
    case failed
    /// `!` and the detail (the reason) in the error tone; the run was stopped first.
    case stopped

    var label: String {
        switch self {
        case .running: "running"
        case .waitingForInput: "waiting for input"
        case .done: "done"
        case .failed: "failed"
        case .stopped: "stopped"
        }
    }

    var detailColor: Color {
        switch self {
        case .failed, .stopped: OpenCodeColors.error
        case .waitingForInput: OpenCodeColors.warning
        case .running, .done: OpenCodeColors.textBase
        }
    }
}

/// A background task in a list, such as a subagent in the background work panel. The title
/// leads, an optional `tag` marks a non-default agent, `detail` is one line under it (the
/// current step, or the result), and the trailing column holds `time` (a live timer or the
/// run time) over `detailCaption` ("12 tools", "4m ago"). Lines up with `OpenCodeShellRow`.
struct OpenCodeTaskRow: View {
    let title: String
    let state: OpenCodeTaskRowState
    let tag: String?
    let detail: String?
    let detailCaption: String?
    let time: String?
    let action: (() -> Void)?

    init(
        title: String,
        state: OpenCodeTaskRowState,
        tag: String? = nil,
        detail: String? = nil,
        detailCaption: String? = nil,
        time: String? = nil,
        action: (() -> Void)? = nil
    ) {
        self.title = title
        self.state = state
        self.tag = tag
        self.detail = detail
        self.detailCaption = detailCaption
        self.time = time
        self.action = action
    }

    var body: some View {
        Group {
            if let action {
                Button(action: action) {
                    rowContent
                }
                .buttonStyle(.plain)
            } else {
                rowContent
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(
            buildTaskRowAccessibilityLabel(
                title: title,
                tag: tag,
                state: state,
                detail: detail,
                detailCaption: detailCaption,
                time: time
            )
        )
        .accessibilityAddTraits(action == nil ? [] : .isButton)
    }

    private var rowContent: some View {
        HStack(alignment: .center, spacing: Self.contentSpacing) {
            OpenCodeTaskRowGlyph(state: state)
            VStack(alignment: .leading, spacing: Self.textStackSpacing) {
                HStack(alignment: .center, spacing: Self.titleRowSpacing) {
                    Text(title)
                        .font(OpenCodeTypography.heading)
                        .foregroundStyle(OpenCodeColors.textStrong)
                        .lineLimit(1)
                    if let tag, !tag.trimmingCharacters(in: .whitespaces).isEmpty {
                        OpenCodeTag(tag, size: .small)
                            .fixedSize()
                    }
                }
                if let detail, !detail.isEmpty {
                    Text(detail)
                        .font(OpenCodeTypography.caption)
                        .foregroundStyle(state.detailColor)
                        .lineLimit(1)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            HStack(spacing: Self.trailingSpacing) {
                if hasTrailingText {
                    VStack(alignment: .trailing, spacing: Self.textStackSpacing) {
                        if let time, !time.isEmpty {
                            Text(time)
                                .font(OpenCodeTypography.caption.monospacedDigit())
                                .foregroundStyle(OpenCodeColors.textWeaker)
                        }
                        if let detailCaption, !detailCaption.isEmpty {
                            Text(detailCaption)
                                .font(OpenCodeTypography.caption)
                                .foregroundStyle(OpenCodeColors.textWeaker)
                        }
                    }
                }
                if action != nil {
                    OpenCodeIconImage(icon: .chevronRight, size: Self.chevronSize)
                        .foregroundStyle(OpenCodeColors.iconStrongBase)
                        .accessibilityHidden(true)
                }
            }
            // Time and caption stay whole; the title and detail truncate first.
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

    private var hasTrailingText: Bool {
        !(time ?? "").isEmpty || !(detailCaption ?? "").isEmpty
    }

    // Matches `OpenCodeShellRow` so both kinds of row line up in one panel.
    private static let cornerRadius: CGFloat = 8
    private static let paddingHorizontal: CGFloat = 12
    private static let paddingVertical: CGFloat = 10
    private static let contentSpacing: CGFloat = 12
    private static let titleRowSpacing: CGFloat = 6
    private static let textStackSpacing: CGFloat = 2
    private static let trailingSpacing: CGFloat = 6
    private static let chevronSize: CGFloat = 16
}

/// The leading status glyph in a raised circle the size of the shell row's terminal glyph.
private struct OpenCodeTaskRowGlyph: View {
    let state: OpenCodeTaskRowState
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var dim = false

    var body: some View {
        glyph
            .frame(width: Self.frameSize, height: Self.frameSize)
            .background(OpenCodeColors.surfaceRaisedBase, in: Circle())
            .accessibilityHidden(true)
    }

    @ViewBuilder
    private var glyph: some View {
        switch state {
        case .running:
            if reduceMotion {
                Circle()
                    .trim(from: 0, to: 0.75)
                    .stroke(OpenCodeColors.textStrong, style: StrokeStyle(lineWidth: 2, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                    .frame(width: 14, height: 14)
            } else {
                ProgressView()
                    .controlSize(.small)
                    .tint(OpenCodeColors.textStrong)
            }
        case .waitingForInput:
            Circle()
                .fill(OpenCodeColors.warning)
                .frame(width: Self.dotSize, height: Self.dotSize)
                .opacity(reduceMotion ? 1 : (dim ? 0.35 : 1))
                .task(id: reduceMotion) {
                    guard !reduceMotion else { return }
                    withAnimation(.easeInOut(duration: 0.9).repeatForever(autoreverses: true)) { dim = true }
                }
        case .done:
            OpenCodeIconImage(icon: .check, size: Self.iconSize)
                .foregroundStyle(OpenCodeColors.success)
        case .failed, .stopped:
            Text("!")
                .font(.system(size: 11, weight: .bold))
                .foregroundStyle(OpenCodeColors.error)
                .frame(width: Self.iconSize, height: Self.iconSize)
                .overlay(Circle().strokeBorder(OpenCodeColors.error, lineWidth: 1.5))
        }
    }

    private static let frameSize: CGFloat = 32
    private static let iconSize: CGFloat = 16
    private static let dotSize: CGFloat = 8
}

/// "Find float money math, explore, running, Reading format.ts, 12 tools, 1:04".
func buildTaskRowAccessibilityLabel(
    title: String,
    tag: String?,
    state: OpenCodeTaskRowState,
    detail: String?,
    detailCaption: String?,
    time: String?
) -> String {
    [title, tag, state.label, detail, detailCaption, time]
        .compactMap { $0 }
        .filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
        .joined(separator: ", ")
}

#if DEBUG
#Preview("Task Row") {
    ScrollView {
        VStack(alignment: .leading, spacing: 8) {
            OpenCodeTaskRow(
                title: "Find float money math",
                state: .running,
                detail: "Searching toFixed",
                detailCaption: "12 tools",
                time: "1:04",
                action: {}
            )
            OpenCodeTaskRow(
                title: "Run the tests",
                state: .waitingForInput,
                tag: "build",
                detail: "Waiting for input",
                time: "0:41",
                action: {}
            )
            OpenCodeTaskRow(
                title: "Map invoice flow",
                state: .done,
                tag: "explore",
                detail: "parse → validate → totalCents → render",
                detailCaption: "4m ago",
                time: "2:31",
                action: {}
            )
            OpenCodeTaskRow(title: "Benchmark formatMoney", state: .failed, detail: "Rate limited", detailCaption: "now")
        }
        .padding()
    }
    .openCodeTheme()
}
#endif
