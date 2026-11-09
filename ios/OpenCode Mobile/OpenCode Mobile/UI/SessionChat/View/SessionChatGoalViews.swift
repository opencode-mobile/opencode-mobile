import SwiftUI

/// What the goal's timeline rows and sheet need from the screen.
struct SessionChatGoalTimelineContext {
    var state: SessionChatGoalUiState?
    var onOpen: () -> Void = {}
    var onAction: (SessionGoalAction) -> Void = { _ in }
    var onNewGoal: () -> Void = {}

    static let none = SessionChatGoalTimelineContext(state: nil)
}

/// The status row for a paused or met goal, at its place in the timeline. Built from the
/// goal state, so it goes away on resume, clear or replace.
struct SessionChatGoalStatusRow: View {
    let state: SessionChatGoalUiState
    let onAction: (SessionGoalAction) -> Void
    let onNewGoal: () -> Void

    private var isMet: Bool { state.goal.status == .complete }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                OpenCodeIconImage(icon: isMet ? .check : .pause, size: 16)
                    .foregroundStyle(isMet ? OpenCodeColors.success : OpenCodeColors.warning)
                    .alignmentGuide(.firstTextBaseline) { $0[.bottom] * 0.8 }
                headline
                    .font(OpenCodeTypography.body)
                    .foregroundStyle(OpenCodeColors.textStrong)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .accessibilityElement(children: .combine)
            HStack(spacing: 8) {
                if isMet {
                    OpenCodeButton(
                        variant: .compactText(title: .localized(SessionChatGoalCopy.newGoal)),
                        enabled: state.actionsEnabled,
                        contrast: .high,
                        action: onNewGoal
                    )
                } else {
                    OpenCodeButton(
                        variant: .compactText(
                            title: .localized(SessionChatGoalCopy.actionLabel(.resume, pending: state.pending == .resume))
                        ),
                        enabled: state.actionsEnabled,
                        contrast: .high,
                        action: { onAction(.resume) }
                    )
                }
                OpenCodeButton(
                    variant: .compactText(
                        title: .localized(SessionChatGoalCopy.actionLabel(.clear, pending: state.pending == .clear))
                    ),
                    enabled: state.actionsEnabled,
                    contrast: .low,
                    action: { onAction(.clear) }
                )
            }
            .padding(.leading, 24)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            (isMet ? OpenCodeColors.success : OpenCodeColors.warning).opacity(0.12),
            in: RoundedRectangle(cornerRadius: OpenCodeRadius.medium, style: .continuous)
        )
        .opacity(state.isStale ? 0.55 : 1)
        .padding(.vertical, 4)
    }

    private var headline: Text {
        if isMet {
            let title = Text(SessionChatGoalCopy.metTitle).fontWeight(.semibold)
            guard let reason = state.goal.reason, !reason.isEmpty else { return title }
            return title + Text(verbatim: " · " + reason).foregroundStyle(OpenCodeColors.textBase)
        }
        let title = Text(SessionChatGoalCopy.pauseTitle(state.goal)).fontWeight(.semibold)
        guard let reason = SessionChatGoalCopy.pauseReason(state.goal) else { return title }
        return title + Text(verbatim: " · " + reason).foregroundStyle(OpenCodeColors.textBase)
    }
}

/// The goal sheet: objective, progress, the reviewer's last word and the goal's actions.
struct SessionChatGoalSheetModifier: ViewModifier {
    let state: SessionChatGoalUiState?
    let onDismiss: () -> Void
    let onAction: (SessionGoalAction) -> Void
    let onNewGoal: () -> Void

    func body(content: Content) -> some View {
        content
            .openCodeBottomSheet(
                isPresented: Binding(
                    get: { state?.sheetOpen == true },
                    set: { presented in if !presented { onDismiss() } }
                ),
                title: .localized(SessionChatGoalCopy.sheetTitle),
                content: {
                    if let state {
                        SessionChatGoalSheetContent(state: state, onAction: onAction, onNewGoal: onNewGoal)
                            .openCodeBottomSheetContentInset()
                    }
                }
            )
    }
}

private struct SessionChatGoalSheetContent: View {
    let state: SessionChatGoalUiState
    let onAction: (SessionGoalAction) -> Void
    let onNewGoal: () -> Void

    @State private var showsAll = false

    private var goal: SessionGoal { state.goal }
    private var isLong: Bool { goal.objective.count > SessionChatGoalCopy.objectiveClampCharacters }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            OpenCodeTag(.localized(SessionChatGoalCopy.tag(state.activity)), size: .small, tone: tagTone)
            if state.isStale {
                Text(SessionChatGoalCopy.reconnecting)
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(OpenCodeColors.warning)
            }
            VStack(alignment: .leading, spacing: 4) {
                Text(verbatim: goal.objective)
                    .font(OpenCodeTypography.body)
                    .foregroundStyle(OpenCodeColors.textStrong)
                    .lineLimit(isLong && !showsAll ? SessionChatGoalCopy.objectiveClampLines : nil)
                    .fixedSize(horizontal: false, vertical: true)
                    .textSelection(.enabled)
                if isLong {
                    OpenCodeButton(
                        variant: .link(title: .localized(showsAll ? SessionChatGoalCopy.showLess : SessionChatGoalCopy.showAll)),
                        action: { showsAll.toggle() }
                    )
                }
            }
            VStack(alignment: .leading, spacing: 6) {
                if goal.status == .active {
                    ProgressView(value: SessionChatGoalCopy.progressFraction(goal))
                        .tint(OpenCodeColors.interactive)
                        .accessibilityHidden(true)
                }
                Text(SessionChatGoalCopy.progress(goal))
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(OpenCodeColors.textWeak)
                if let stalls = SessionChatGoalCopy.stalls(goal) {
                    Text(stalls)
                        .font(OpenCodeTypography.caption)
                        .foregroundStyle(OpenCodeColors.warning)
                }
            }
            reviewerBox
            HStack(spacing: 8) {
                OpenCodeButton(
                    variant: .compactText(
                        title: .localized(SessionChatGoalCopy.actionLabel(.clear, pending: state.pending == .clear))
                    ),
                    enabled: state.actionsEnabled,
                    contrast: .destructive,
                    action: { onAction(.clear) }
                )
                Spacer(minLength: 0)
                mainAction
            }
            Text(SessionChatGoalCopy.footnote(goal))
                .font(OpenCodeTypography.caption)
                .foregroundStyle(OpenCodeColors.textWeak)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.bottom, 8)
    }

    private var tagTone: OpenCodeTagTone {
        switch state.activity {
        case .working: .accent
        case .waiting: .neutral
        case .paused: .warning
        case .met: .success
        }
    }

    @ViewBuilder
    private var mainAction: some View {
        switch goal.status {
        case .active:
            OpenCodeButton(
                variant: .compactText(title: .localized(SessionChatGoalCopy.actionLabel(.pause, pending: state.pending == .pause))),
                enabled: state.actionsEnabled,
                contrast: .high,
                action: { onAction(.pause) }
            )
        case .paused:
            OpenCodeButton(
                variant: .compactText(
                    title: .localized(SessionChatGoalCopy.actionLabel(.resume, pending: state.pending == .resume))
                ),
                enabled: state.actionsEnabled,
                contrast: .high,
                action: { onAction(.resume) }
            )
        case .complete:
            OpenCodeButton(
                variant: .compactText(title: .localized(SessionChatGoalCopy.newGoal)),
                enabled: state.actionsEnabled,
                contrast: .high,
                action: onNewGoal
            )
        }
    }

    @ViewBuilder
    private var reviewerBox: some View {
        switch goal.status {
        case .active:
            let note = SessionChatGoalCopy.reviewerNote(state)
            box(caption: note.caption, text: note.text, tone: note.needsUser ? OpenCodeColors.warning : nil)
        case .paused:
            box(caption: SessionChatGoalCopy.pauseTitle(goal), text: SessionChatGoalCopy.pauseReason(goal), tone: OpenCodeColors.warning)
        case .complete:
            box(caption: SessionChatGoalCopy.evidence, text: goal.reason, tone: OpenCodeColors.success)
        }
    }

    private func box(caption: String, text: String?, tone: Color?) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(caption)
                .font(OpenCodeTypography.caption)
                .fontWeight(.semibold)
                .foregroundStyle(tone ?? OpenCodeColors.textWeak)
            if let text, !text.isEmpty {
                Text(verbatim: text)
                    .font(OpenCodeTypography.body)
                    .foregroundStyle(OpenCodeColors.textStrong)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            (tone ?? OpenCodeColors.textWeak).opacity(tone == nil ? 0.08 : 0.12),
            in: RoundedRectangle(cornerRadius: OpenCodeRadius.medium, style: .continuous)
        )
        .accessibilityElement(children: .combine)
    }
}

extension View {
    func sessionChatGoalSheet(
        state: SessionChatGoalUiState?,
        onDismiss: @escaping () -> Void,
        onAction: @escaping (SessionGoalAction) -> Void,
        onNewGoal: @escaping () -> Void
    ) -> some View {
        modifier(SessionChatGoalSheetModifier(state: state, onDismiss: onDismiss, onAction: onAction, onNewGoal: onNewGoal))
    }
}
