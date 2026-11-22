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

/// The mark of a turn pill that waits on the user.
enum OpenCodeTurnStatusAttentionMark: Equatable {
    /// A collapsed panel: a tap brings it back up.
    case arrowUp
    case pulsingDot
}

/// What the turn pill says.
enum OpenCodeTurnStatusPillPhase: Equatable {
    /// The turn is running; `label` is "Working", "Compacting…" and so on.
    case working(label: String)
    /// The turn waits on the user.
    case attention(label: String, mark: OpenCodeTurnStatusAttentionMark)
    /// The turn failed.
    case failed(label: String)
    /// The turn just ended.
    case done

    var isDone: Bool { self == .done }

    var label: String {
        switch self {
        case let .working(label), let .attention(label, _), let .failed(label): label
        case .done: String(localized: "common.done", defaultValue: "Done")
        }
    }

    /// Working and Done fold to a round mark. Attention and failed must stay readable.
    var folds: Bool {
        switch self {
        case .working, .done: true
        case .attention, .failed: false
        }
    }

    var hasMark: Bool {
        if case .failed = self { return false }
        return true
    }

    fileprivate var isWorking: Bool {
        if case .working = self { return true }
        return false
    }

    fileprivate var color: Color {
        switch self {
        case .working: OpenCodeColors.textBase
        case .attention: OpenCodeColors.warning
        case .failed: OpenCodeColors.error
        case .done: OpenCodeColors.textStrong
        }
    }
}

/// The turn's one pill. Every phase is the same view, so the label and width change in place,
/// and Working to Done bends the spinner into the check while the label rolls. `compact` folds
/// it to a round mark. `leaving` fades it out. Reduce Motion replaces all of that with a short
/// crossfade. With `action` it is a button; with `onClose` it also has a trailing close.
struct OpenCodeTurnStatusPill: View {
    let phase: OpenCodeTurnStatusPillPhase
    let compact: Bool
    let leaving: Bool
    /// Caps the pill's width; a longer label truncates.
    let maxWidth: CGFloat?
    let accessibilityLabelOverride: String?
    let action: (() -> Void)?
    let onClose: (() -> Void)?

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var morph: MorphRun?
    /// The last phase before Done, whose label rolls out as Done rolls in.
    @State private var statusPhase: OpenCodeTurnStatusPillPhase

    init(
        phase: OpenCodeTurnStatusPillPhase,
        compact: Bool = false,
        leaving: Bool = false,
        maxWidth: CGFloat? = nil,
        accessibilityLabel: String? = nil,
        action: (() -> Void)? = nil,
        onClose: (() -> Void)? = nil
    ) {
        self.phase = phase
        self.compact = compact && phase.folds
        self.leaving = leaving
        self.maxWidth = maxWidth
        self.accessibilityLabelOverride = accessibilityLabel
        self.action = action
        self.onClose = onClose
        _statusPhase = State(initialValue: phase.isDone ? .working(label: OpenCodeActivityText.working) : phase)
    }

    private struct MorphRun: Equatable {
        let plan: OpenCodeCheckMorph
        let startedAt: Date
    }

    private var spokenLabel: String {
        accessibilityLabelOverride ?? phase.label
    }

    private var showsClose: Bool {
        onClose != nil && !compact
    }

    /// Room the label may take inside `maxWidth`, after the padding, the mark and the close.
    private var labelMaxWidth: CGFloat? {
        maxWidth.map {
            max(0, $0 - OpenCodeFloatingPill.fullWidth(textWidth: 0, hasMark: phase.hasMark, hasClose: onClose != nil))
        }
    }

    var body: some View {
        HStack(spacing: 0) {
            // Always a Button, so gaining or losing a tap keeps the view's identity.
            Button { action?() } label: { content }
                .buttonStyle(OpenCodeFloatingPillPressStyle())
                .allowsHitTesting(action != nil)
                // One element: the two morphing labels must not be read separately.
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(spokenLabel)
                .accessibilityAddTraits(action == nil ? [] : .isButton)
                .accessibilityAddTraits(phase.isDone ? [] : .updatesFrequently)
                .accessibilityAction { action?() }
                .openCodeKeyboardFocusable(.capsule, enabled: action != nil, activate: action)
            // Only a closable phase has this button, so VoiceOver cannot reach it in any other.
            if onClose != nil {
                OpenCodeWidthRevealLayout(progress: showsClose ? 1 : 0) { closeButton }
                    .opacity(showsClose ? 1 : 0)
                    // Clip sideways only, so the 44 pt hit area keeps its height.
                    .mask { Rectangle().padding(.vertical, -Self.closeTouchTarget) }
                    .allowsHitTesting(showsClose)
                    .accessibilityHidden(!showsClose)
            }
        }
        .animation(reduceMotion ? nil : OpenCodeFloatingPill.widthAnimation, value: compact)
        .openCodeFloatingPillChrome()
        .overlay {
            if case .attention = phase {
                Capsule()
                    .strokeBorder(OpenCodeColors.warning, lineWidth: Self.ringWidth)
                    .allowsHitTesting(false)
            }
        }
        .opacity(leaving ? 0 : 1)
        .scaleEffect(leaving && !reduceMotion ? Self.leavingScale : 1)
        .animation(.easeInOut(duration: Self.leaveSeconds), value: leaving)
        .onChange(of: phase) { previous, next in
            if !next.isDone { statusPhase = next }
            guard previous.isWorking, next.isDone, !reduceMotion else {
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

    private var content: some View {
        HStack(spacing: 0) {
            mark
                .frame(width: phase.hasMark ? Self.iconSize : 0, height: Self.iconSize)
            OpenCodeWidthRevealLayout(
                progress: compact ? 0 : 1,
                gap: phase.hasMark ? Self.iconSpacing : 0,
                gapEdge: .leading
            ) {
                labels
            }
            .opacity(compact ? 0 : 1)
            // Clip sideways only, so the rolling label can travel past its own line.
            .mask { Rectangle().padding(.vertical, -Self.rollDistance) }
        }
        .padding(.horizontal, compact ? Self.compactPaddingHorizontal : Self.paddingHorizontal)
        .padding(.vertical, Self.paddingVertical)
        .frame(minHeight: OpenCodeFloatingPill.minHeight)
        .contentShape(Capsule())
    }

    private var closeButton: some View {
        Button { onClose?() } label: {
            OpenCodeIconImage(icon: .close, size: Self.iconSize)
                .foregroundStyle(OpenCodeColors.iconBase)
                .frame(width: Self.closeTouchTarget, height: Self.closeTouchTarget)
                .contentShape(Rectangle())
        }
        .buttonStyle(OpenCodeFloatingPillPressStyle())
        // 44 pt hit area that lays out as the 36 pt visual square.
        .padding(-(Self.closeTouchTarget - OpenCodeFloatingPill.minHeight) / 2)
        .padding(.leading, -Self.paddingHorizontal / 2)
        .accessibilityLabel(OpenCodeFloatingPillStyle.closeAccessibilityLabel(layout: .label(phase.label)))
        .openCodeKeyboardFocusable(.circle, enabled: showsClose, activate: onClose)
    }

    @ViewBuilder
    private var mark: some View {
        if let morph {
            TimelineView(.animation) { context in
                morphFrame(morph, ms: context.date.timeIntervalSince(morph.startedAt) * 1000)
            }
        } else {
            switch phase {
            case .done:
                OpenCodeIconImage(icon: .check, size: Self.iconSize)
                    .foregroundStyle(OpenCodeColors.textStrong)
                    .transition(.opacity)
            case .working:
                OpenCodeArcSpinner(color: OpenCodeColors.textBase)
                    .transition(.opacity)
            case .attention(_, .arrowUp):
                OpenCodeIconImage(icon: .arrowUpward, size: Self.iconSize)
                    .foregroundStyle(OpenCodeColors.warning)
                    .transition(.opacity)
            case .attention(_, .pulsingDot):
                OpenCodeFloatingPillPulsingDot(color: OpenCodeColors.warning)
                    .transition(.opacity)
            case .failed:
                EmptyView()
            }
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
        OpenCodeTurnStatusLabelLayout(progress: phase.isDone ? 1 : 0, maxWidth: labelMaxWidth) {
            label(statusPhase.label, color: statusPhase.color)
                .contentTransition(.opacity)
                .opacity(phase.isDone ? 0 : 1)
                .accessibilityHidden(phase.isDone)
                .offset(y: phase.isDone && !reduceMotion ? -Self.rollDistance : 0)
                .animation(rollOutAnimation, value: phase.isDone)
            label(OpenCodeTurnStatusPillPhase.done.label, color: OpenCodeColors.textStrong)
                .opacity(phase.isDone ? 1 : 0)
                .accessibilityHidden(!phase.isDone)
                .offset(y: phase.isDone || reduceMotion ? 0 : Self.rollDistance)
                .animation(rollInAnimation, value: phase.isDone)
        }
        .animation(reduceMotion ? nil : Self.widthAnimation, value: phase.isDone)
        .animation(reduceMotion ? .easeInOut(duration: Self.crossfadeSeconds) : OpenCodeFloatingPill.widthAnimation, value: statusPhase)
    }

    private func label(_ text: String, color: Color) -> some View {
        Text(text)
            .font(OpenCodeTypography.button)
            .foregroundStyle(color)
            .lineLimit(1)
            .truncationMode(.tail)
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
    private static let ringWidth: CGFloat = 1
    private static let closeTouchTarget: CGFloat = 44
}

/// Stacks two labels at the leading edge and reports a width between theirs, capped at `maxWidth`.
private struct OpenCodeTurnStatusLabelLayout: Layout {
    var progress: CGFloat
    var maxWidth: CGFloat?

    var animatableData: CGFloat {
        get { progress }
        set { progress = newValue }
    }

    private func capped(_ width: CGFloat) -> CGFloat {
        maxWidth.map { min(width, $0) } ?? width
    }

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let sizes = subviews.map { $0.sizeThatFits(.unspecified) }
        guard let first = sizes.first, let last = sizes.last else { return .zero }
        let firstWidth = capped(first.width)
        let lastWidth = capped(last.width)
        return CGSize(
            width: max(0, firstWidth + (lastWidth - firstWidth) * progress),
            height: sizes.map(\.height).max() ?? 0
        )
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        for subview in subviews {
            let ideal = subview.sizeThatFits(.unspecified)
            subview.place(
                at: CGPoint(x: bounds.minX, y: bounds.midY),
                anchor: .leading,
                proposal: ProposedViewSize(width: capped(ideal.width), height: ideal.height)
            )
        }
    }
}

#if DEBUG
#Preview("Turn status") {
    VStack(alignment: .leading, spacing: 12) {
        OpenCodeTurnStatusPill(phase: .working(label: "Working"))
        OpenCodeTurnStatusPill(phase: .working(label: "Working"), compact: true)
        OpenCodeTurnStatusPill(phase: .attention(label: "Review permission", mark: .arrowUp), action: {})
        OpenCodeTurnStatusPill(phase: .attention(label: "Waiting for you", mark: .pulsingDot))
        OpenCodeTurnStatusPill(phase: .failed(label: "Request failed"), action: {}, onClose: {})
        OpenCodeTurnStatusPill(phase: .done)
        OpenCodeTurnStatusPill(phase: .done, compact: true)
    }
    .padding()
    .openCodeTheme()
}
#endif
