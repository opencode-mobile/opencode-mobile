import SwiftUI

extension View {
    /// Activity card (#779): the live activity sheet for `cardID`. The card is
    /// resolved from `rows` on every render, so the sheet keeps updating while
    /// open and closes once the card is gone. With `detailStepID` the same
    /// sheet shows that step's detail under a back arrow instead of the list.
    ///
    /// Steps stay oldest first. A live list opens at its newest step and keeps
    /// new steps in view while it is parked at the bottom (#879); a settled one
    /// opens at its top. Back from a detail returns to that step, and settling
    /// while open leaves the list where it is.
    func sessionChatActivitySheet(
        cardID: String?,
        detailStepID: String?,
        rows: [SessionChatRow],
        liveCardID: String?,
        childActivities: [String: SessionChatChildActivity],
        awaitingUser: Bool,
        supportsSessionMessageDiff: Bool,
        onChildSessionTap: @escaping (String, String) -> Void,
        onChangesReviewTap: @escaping (String, String?) -> Void,
        onOpenStep: @escaping (String) -> Void,
        onCloseStep: @escaping () -> Void,
        onDismiss: @escaping () -> Void
    ) -> some View {
        modifier(
            SessionChatActivitySheetModifier(
                cardID: cardID,
                detailStepID: detailStepID,
                rows: rows,
                liveCardID: liveCardID,
                childActivities: childActivities,
                supportsSessionMessageDiff: supportsSessionMessageDiff,
                onChildSessionTap: onChildSessionTap,
                onChangesReviewTap: onChangesReviewTap,
                onOpenStep: onOpenStep,
                onCloseStep: onCloseStep,
                onDismiss: onDismiss
            )
        )
    }
}

private struct SessionChatActivitySheetModifier: ViewModifier {
    let cardID: String?
    let detailStepID: String?
    let rows: [SessionChatRow]
    let liveCardID: String?
    let childActivities: [String: SessionChatChildActivity]
    let supportsSessionMessageDiff: Bool
    let onChildSessionTap: (String, String) -> Void
    let onChangesReviewTap: (String, String?) -> Void
    let onOpenStep: (String) -> Void
    let onCloseStep: () -> Void
    let onDismiss: () -> Void
    /// The step whose detail was last shown, so back returns the list to it.
    @State private var returnStepID: String?

    func body(content: Content) -> some View {
        let resolved = cardID.flatMap { SessionChatActivityCardPresentation.card(id: $0, in: rows) }
        let card = resolved.map { $0.card.withLive($0.card.id == liveCardID) }
        let detail = card.flatMap {
            SessionChatActivityCardPresentation.detail(
                stepID: detailStepID,
                in: $0,
                supportsSessionMessageDiff: supportsSessionMessageDiff
            )
        }
        let count = card?.steps.count ?? 0
        let isLive = card?.isLive == true
        let inspector = detail?.target.inspectorPresentation
        let title = inspector?.title ?? detail?.target.title ?? (isLive ? "Working \(count)" : OpenCodeActivityCard.stepsLabel(count))
        let titleState: OpenCodeBottomSheetTitleState = detail == nil && isLive
            ? .running(startedAtMs: ToolStartTime.normalizeEpochMs(card?.startedAtMs))
            : .plain
        return content.openCodeBottomSheet(
            isPresented: Binding(
                get: { resolved != nil },
                set: { presented in if !presented { onDismiss() } }
            ),
            title: .unlocalized(title),
            titleState: titleState,
            leadingAction: detail == nil ? nil : OpenCodeBottomSheetHeaderAction(
                icon: .arrowBack,
                accessibilityLabel: "Back to steps",
                action: onCloseStep
            ),
            scrollTargetID: detail == nil ? returnStepID : nil,
            followsBottom: isLive && detail == nil,
            height: .half,
            pinnedTop: {
                if let inspector {
                    ToolResultPinnedHeader(presentation: inspector)
                        .openCodeBottomSheetContentInset()
                }
            },
            content: {
                if let detail {
                    SessionChatToolDetailSheetBody(target: detail.target)
                        .id(detail.step.id)
                } else if let card, let rowID = resolved?.rowID {
                    SessionChatActivitySheetContent(
                        card: card,
                        childActivities: childActivities,
                        actions: SessionChatStepTapActions(
                            messageID: rowID,
                            supportsSessionMessageDiff: supportsSessionMessageDiff,
                            onToolDetailTap: { _ in },
                            onChildSessionTap: onChildSessionTap,
                            onChangesReviewTap: onChangesReviewTap
                        ),
                        onOpenStep: onOpenStep,
                        onDismiss: onDismiss
                    )
                }
            },
            pinnedBottom: {
                if let inspector, inspector.copiesOriginal {
                    ToolResultCopyFooter(raw: inspector.raw)
                }
            }
        )
        .onChange(of: detailStepID) { _, next in
            // Kept from when the detail opens, so the render that closes it
            // already scrolls back to the step instead of following the bottom.
            if let next { returnStepID = next }
        }
        .onChange(of: cardID) { _, _ in
            // A sheet opened again starts fresh, not on the step it last showed.
            returnStepID = nil
        }
    }
}

/// Feature-local sheet body composed from the Activity Card's shared pieces:
/// an Agent section (each child session with its latest steps while it runs)
/// and a Steps section with every other step in order.
struct SessionChatActivitySheetContent: View {
    let card: SessionChatActivityCardBlock
    let childActivities: [String: SessionChatChildActivity]
    let actions: SessionChatStepTapActions
    let onOpenStep: (String) -> Void
    let onDismiss: () -> Void

    var body: some View {
        let children = card.childSteps
        let tools = card.toolSteps
        VStack(alignment: .leading, spacing: 0) {
            if !children.isEmpty {
                OpenCodeActivitySectionLabel(count: children.count)
                    .modifier(SheetSectionLabelPadding())
                ForEach(children) { step in
                    childRow(step)
                        .id(step.id)
                    OpenCodeHairline()
                }
            }
            if !tools.isEmpty {
                OpenCodeActivitySectionLabel(text: "Steps")
                    .modifier(SheetSectionLabelPadding())
                ForEach(tools) { step in
                    stepRow(step)
                        .id(step.id)
                    if step.id != tools.last?.id {
                        OpenCodeHairline()
                    }
                }
            }
        }
        .padding(.bottom, 8)
    }

    // MARK: - Agent rows

    private func childRow(_ step: SessionChatStep) -> some View {
        let childID = step.childSession?.childSessionId
        let activity = childID.flatMap { childActivities[$0] }
        let running = step.status.isInFlight
        return VStack(alignment: .leading, spacing: 0) {
            tappable(step) {
                HStack(spacing: 10) {
                    OpenCodeActivityStatusDot(status: step.activityStatus, pulsing: true)
                    VStack(alignment: .leading, spacing: 2) {
                        childTitle(step)
                            .font(OpenCodeTypography.bodySecondary)
                            .lineLimit(1)
                            .truncationMode(.tail)
                        if let caption = Self.childCaption(step, activity: activity) {
                            Text(caption)
                                .font(OpenCodeTypography.caption)
                                .foregroundStyle(step.activityStatus == .failed ? OpenCodeColors.error : OpenCodeColors.textWeak)
                                .lineLimit(1)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    if running, let start = ToolStartTime.normalizeEpochMs(step.startedAtMs) {
                        OpenCodeLiveElapsedText(startedAtMs: start)
                    }
                    if actions.action(for: step) != nil {
                        OpenCodeActivityChevron()
                    }
                }
                .padding(.vertical, 14)
                .frame(minHeight: 48)
            }
            if running, let recent = activity?.recentSteps, !recent.isEmpty {
                VStack(alignment: .leading, spacing: 0) {
                    ForEach(recent) { sub in
                        HStack(spacing: 8) {
                            OpenCodeActivityStatusDot(
                                status: sub.isRunning ? .running : .completed,
                                size: 6,
                                pulsing: true
                            )
                            Text(sub.title)
                                .font(OpenCodeTypography.caption)
                                .foregroundStyle(OpenCodeColors.textWeak)
                                .lineLimit(1)
                                .truncationMode(.tail)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                        .padding(.vertical, 3)
                        .accessibilityElement(children: .combine)
                    }
                }
                .padding(.leading, 18)
                .padding(.bottom, 8)
                .openCodeBottomSheetContentInset()
            }
        }
    }

    private func childTitle(_ step: SessionChatStep) -> Text {
        let name = Text(step.agentDisplayName).fontWeight(.semibold).foregroundColor(OpenCodeColors.info)
        guard let description = step.childSession?.description, !description.isEmpty else { return name }
        return name + Text(" \(description)").foregroundColor(OpenCodeColors.textWeak)
    }

    /// "4 tools so far" while running; the failure, "Stopped" or the tool
    /// count once finished; nil when nothing is known.
    static func childCaption(_ step: SessionChatStep, activity: SessionChatChildActivity?) -> String? {
        let tools = step.childSession?.toolsSoFar ?? activity.map(\.toolCount)
        switch step.activityStatus {
        case .pending, .running:
            guard let tools, tools > 0 else { return nil }
            return tools == 1 ? "1 tool so far" : "\(tools) tools so far"
        case .failed:
            return step.detail
        case .interrupted:
            return "Stopped"
        case .completed:
            guard let tools, tools > 0 else { return nil }
            return tools == 1 ? "1 tool" : "\(tools) tools"
        }
    }

    // MARK: - Step rows

    private func stepRow(_ step: SessionChatStep) -> some View {
        tappable(step) {
            HStack(spacing: 10) {
                OpenCodeActivityStatusDot(status: step.activityStatus, pulsing: true)
                OpenCodeIconImage(icon: step.activityIcon.icon, size: 16)
                    .foregroundStyle(OpenCodeColors.iconWeakBase)
                    .frame(width: 16)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text(step.listTitle)
                        .font(OpenCodeTypography.bodySecondary)
                        .foregroundStyle(OpenCodeColors.textStrong)
                        .lineLimit(1)
                        .truncationMode(.tail)
                    if let detail = step.detail, !detail.isEmpty {
                        Text(detail)
                            .font(OpenCodeTypography.caption)
                            .foregroundStyle(step.activityStatus == .failed ? OpenCodeColors.error : OpenCodeColors.textWeak)
                            .lineLimit(1)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                if (step.additions ?? 0) > 0 || (step.deletions ?? 0) > 0 {
                    OpenCodeDiffStat(
                        additions: (step.additions ?? 0) > 0 ? step.additions : nil,
                        deletions: (step.deletions ?? 0) > 0 ? step.deletions : nil
                    )
                    .fixedSize()
                }
                if step.status.isInFlight, step.kind != .thought,
                   let start = ToolStartTime.normalizeEpochMs(step.startedAtMs)
                {
                    OpenCodeLiveElapsedText(startedAtMs: start)
                }
            }
            .padding(.vertical, 14)
            .frame(minHeight: 48)
        }
    }

    /// A push (child session, Changes) closes this sheet first and navigates
    /// as the timeline does; any other tap shows the step's detail in place.
    private func tapAction(_ step: SessionChatStep) -> (() -> Void)? {
        guard let action = actions.action(for: step) else { return nil }
        guard actions.navigates(step) else { return { onOpenStep(step.id) } }
        return {
            onDismiss()
            action()
        }
    }

    @ViewBuilder
    private func tappable(_ step: SessionChatStep, @ViewBuilder label: () -> some View) -> some View {
        if let action = tapAction(step) {
            Button {
                action()
            } label: {
                label()
                    .openCodeBottomSheetContentInset()
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(.isButton)
        } else {
            label()
                .openCodeBottomSheetContentInset()
                .accessibilityElement(children: .combine)
        }
    }
}

private struct SheetSectionLabelPadding: ViewModifier {
    func body(content: Content) -> some View {
        content
            .padding(.top, 12)
            .padding(.bottom, 4)
            .openCodeBottomSheetContentInset()
    }
}
