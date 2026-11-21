import SwiftUI

/// The monochrome floating pill spinner: one round-capped arc in the current content color.
/// Clock-driven, so a re-created view does not restart it. A still arc under Reduce Motion.
struct OpenCodeArcSpinner: View {
    let color: Color
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        Group {
            if reduceMotion {
                arc(OpenCodeSpinnerGeometry.stillPose)
            } else {
                TimelineView(.animation) { context in
                    arc(OpenCodeSpinnerGeometry.pose(atSeconds: context.date.timeIntervalSinceReferenceDate))
                }
            }
        }
        .frame(width: OpenCodeSpinnerGeometry.boxSize, height: OpenCodeSpinnerGeometry.boxSize)
        .accessibilityHidden(true)
    }

    private func arc(_ pose: OpenCodeSpinnerPose) -> some View {
        Canvas { context, _ in
            context.stroke(
                OpenCodeMorphStroke.path(OpenCodeSpinnerGeometry.arcPoints(pose)),
                with: .color(color),
                style: OpenCodeMorphStroke.style(width: OpenCodeSpinnerGeometry.strokeWidth)
            )
        }
    }
}

private enum OpenCodeMorphStroke {
    static func path(_ points: [CGPoint]) -> Path {
        var path = Path()
        path.addLines(points)
        return path
    }

    static func style(width: CGFloat) -> StrokeStyle {
        StrokeStyle(lineWidth: width, lineCap: .round, lineJoin: .round)
    }
}

/// What the turn status pill says.
enum OpenCodeTurnStatusPillPhase: Equatable {
    /// The turn is running; `label` is "Working", "Compacting…" and so on.
    case working(label: String)
    /// The turn just ended.
    case done

    var isDone: Bool { self == .done }
}

/// The turn's status on floating pill chrome. Working to Done is one pill: the spinner bends
/// into the check while the width follows and the label rolls. `compact` folds it to a round
/// mark. `leaving` fades it out. Reduce Motion replaces all of that with a short crossfade.
struct OpenCodeTurnStatusPill: View {
    let phase: OpenCodeTurnStatusPillPhase
    let compact: Bool
    let leaving: Bool

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var morph: MorphRun?
    @State private var workingLabel: String

    init(phase: OpenCodeTurnStatusPillPhase, compact: Bool = false, leaving: Bool = false) {
        self.phase = phase
        self.compact = compact
        self.leaving = leaving
        if case let .working(label) = phase {
            _workingLabel = State(initialValue: label)
        } else {
            _workingLabel = State(initialValue: OpenCodeActivityText.working)
        }
    }

    private struct MorphRun: Equatable {
        let plan: OpenCodeCheckMorph
        let startedAt: Date
    }

    private var doneLabel: String {
        String(localized: "common.done", defaultValue: "Done")
    }

    private var spokenLabel: String {
        if case let .working(label) = phase { return label }
        return doneLabel
    }

    var body: some View {
        HStack(spacing: 0) {
            mark
                .frame(width: Self.iconSize, height: Self.iconSize)
            OpenCodeWidthRevealLayout(progress: compact ? 0 : 1, gap: Self.iconSpacing, gapEdge: .leading) {
                labels
            }
            .opacity(compact ? 0 : 1)
            // Clip sideways only, so the rolling label can travel past its own line.
            .mask { Rectangle().padding(.vertical, -Self.rollDistance) }
        }
        .padding(.horizontal, compact ? Self.compactPaddingHorizontal : Self.paddingHorizontal)
        .padding(.vertical, Self.paddingVertical)
        .frame(minHeight: OpenCodeFloatingPill.minHeight)
        .clipShape(Capsule())
        .animation(reduceMotion ? nil : OpenCodeFloatingPill.widthAnimation, value: compact)
        .openCodeFloatingPillChrome()
        .opacity(leaving ? 0 : 1)
        .scaleEffect(leaving && !reduceMotion ? Self.leavingScale : 1)
        .animation(.easeInOut(duration: Self.leaveSeconds), value: leaving)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(spokenLabel)
        .accessibilityAddTraits(phase.isDone ? [] : .updatesFrequently)
        .onChange(of: phase) { previous, next in
            if case let .working(label) = next { workingLabel = label }
            guard !previous.isDone, next.isDone, !reduceMotion else {
                if !next.isDone { morph = nil }
                return
            }
            let now = Date()
            let pose = OpenCodeSpinnerGeometry.pose(atSeconds: now.timeIntervalSinceReferenceDate)
            morph = MorphRun(plan: OpenCodeCheckMorph(pose: pose), startedAt: now)
        }
        .task(id: morph?.startedAt) {
            guard morph != nil else { return }
            try? await Task.sleep(for: .milliseconds(Int(OpenCodeCheckMorph.totalMs)))
            guard !Task.isCancelled else { return }
            morph = nil
        }
    }

    @ViewBuilder
    private var mark: some View {
        if let morph {
            TimelineView(.animation) { context in
                morphFrame(morph, ms: context.date.timeIntervalSince(morph.startedAt) * 1000)
            }
        } else if phase.isDone {
            OpenCodeIconImage(icon: .check, size: Self.iconSize)
                .foregroundStyle(OpenCodeColors.textStrong)
                .transition(.opacity)
        } else {
            OpenCodeArcSpinner(color: OpenCodeColors.textBase)
                .transition(.opacity)
        }
    }

    private func morphFrame(_ run: MorphRun, ms: Double) -> some View {
        let strong = OpenCodeColors.textStrong
        let base = OpenCodeColors.textBase
        return Canvas { context, _ in
            let path = OpenCodeMorphStroke.path(run.plan.points(atMs: ms))
            let style = OpenCodeMorphStroke.style(width: OpenCodeCheckMorph.strokeWidth(atMs: ms))
            let progress = OpenCodeCheckMorph.colorProgress(atMs: ms)
            context.stroke(path, with: .color(base.opacity(1 - progress)), style: style)
            context.stroke(path, with: .color(strong.opacity(progress)), style: style)
        }
        .scaleEffect(OpenCodeCheckMorph.scale(atMs: ms))
    }

    /// Both labels stay laid out, so the width can travel between them.
    private var labels: some View {
        OpenCodeTurnStatusLabelLayout(progress: phase.isDone ? 1 : 0) {
            label(workingLabel, color: OpenCodeColors.textBase)
                .opacity(phase.isDone ? 0 : 1)
                .offset(y: phase.isDone && !reduceMotion ? -Self.rollDistance : 0)
                .animation(rollOutAnimation, value: phase.isDone)
            label(doneLabel, color: OpenCodeColors.textStrong)
                .opacity(phase.isDone ? 1 : 0)
                .offset(y: phase.isDone || reduceMotion ? 0 : Self.rollDistance)
                .animation(rollInAnimation, value: phase.isDone)
        }
        .animation(reduceMotion ? nil : Self.widthAnimation, value: phase.isDone)
    }

    private func label(_ text: String, color: Color) -> some View {
        Text(text)
            .font(OpenCodeTypography.button)
            .foregroundStyle(color)
            .lineLimit(1)
            .fixedSize()
    }

    private var rollOutAnimation: Animation {
        reduceMotion ? .easeInOut(duration: Self.crossfadeSeconds) : .timingCurve(0.3, 0, 0.2, 1, duration: 0.22)
    }

    private var rollInAnimation: Animation {
        reduceMotion
            ? .easeInOut(duration: Self.crossfadeSeconds)
            : .timingCurve(0.2, 0.8, 0.2, 1, duration: 0.3).delay(0.11)
    }

    /// The pill's width between Working and Done, with a slight overshoot.
    private static let widthAnimation: Animation = .timingCurve(0.3, 1.18, 0.5, 1, duration: 0.42)
    private static let crossfadeSeconds = 0.16
    private static let leaveSeconds = 0.24
    private static let leavingScale: CGFloat = 0.96
    private static let rollDistance: CGFloat = 9
    private static let iconSize: CGFloat = 16
    private static let iconSpacing: CGFloat = 6
    private static let paddingHorizontal: CGFloat = 14
    private static let compactPaddingHorizontal: CGFloat = 10
    private static let paddingVertical: CGFloat = 8
}

/// Stacks two labels at the leading edge and reports a width between theirs.
private struct OpenCodeTurnStatusLabelLayout: Layout {
    var progress: CGFloat

    var animatableData: CGFloat {
        get { progress }
        set { progress = newValue }
    }

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let sizes = subviews.map { $0.sizeThatFits(.unspecified) }
        guard let first = sizes.first, let last = sizes.last else { return .zero }
        return CGSize(
            width: max(0, first.width + (last.width - first.width) * progress),
            height: sizes.map(\.height).max() ?? 0
        )
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        for subview in subviews {
            subview.place(at: CGPoint(x: bounds.minX, y: bounds.midY), anchor: .leading, proposal: .unspecified)
        }
    }
}

#if DEBUG
#Preview("Turn status") {
    VStack(alignment: .leading, spacing: 12) {
        OpenCodeTurnStatusPill(phase: .working(label: "Working"))
        OpenCodeTurnStatusPill(phase: .done)
        OpenCodeTurnStatusPill(phase: .working(label: "Working"), compact: true)
        OpenCodeTurnStatusPill(phase: .done, compact: true)
    }
    .padding()
    .openCodeTheme()
}
#endif
