import Foundation

/// Projected timeline row with per-row revision metadata.
/// Adapted to `SessionChatRow` for Screen Model / hosts in Phase 5.
nonisolated struct TimelineRow: Equatable, Identifiable, Sendable {
    let id: String
    let role: MessageRole
    let text: String
    let metadataLabel: String
    let pendingDelivery: FollowUpDelivery?
    let isPending: Bool
    let isStreaming: Bool
    let renderedMarkdown: NativeMarkdownDocument?
    let blocks: [SessionChatTimelineBlock]
    let modelProviderID: String?
    let modelID: String?
    let contextInputTokens: Int64?
    /// Aggregate content revision of plain text (hash + length).
    let contentRevision: ContentRevision
    /// Advances on height-affecting changes (text, blocks, markdown presence, streaming).
    let layoutRevision: UInt64

    init(
        id: String,
        role: MessageRole,
        text: String,
        metadataLabel: String,
        pendingDelivery: FollowUpDelivery? = nil,
        isPending: Bool = false,
        isStreaming: Bool = false,
        renderedMarkdown: NativeMarkdownDocument? = nil,
        blocks: [SessionChatTimelineBlock] = [],
        modelProviderID: String? = nil,
        modelID: String? = nil,
        contextInputTokens: Int64? = nil,
        contentRevision: ContentRevision,
        layoutRevision: UInt64
    ) {
        self.id = id
        self.role = role
        self.text = text
        self.metadataLabel = metadataLabel
        self.pendingDelivery = pendingDelivery
        self.isPending = isPending
        self.isStreaming = isStreaming
        self.renderedMarkdown = renderedMarkdown
        self.blocks = blocks
        self.modelProviderID = modelProviderID
        self.modelID = modelID
        self.contextInputTokens = contextInputTokens
        self.contentRevision = contentRevision
        self.layoutRevision = layoutRevision
    }

    func withMetadataLabel(_ metadataLabel: String) -> TimelineRow {
        let layoutRevision =
            self.metadataLabel.isEmpty == metadataLabel.isEmpty
                ? self.layoutRevision
                : self.layoutRevision &+ 1
        return TimelineRow(
            id: id,
            role: role,
            text: text,
            metadataLabel: metadataLabel,
            pendingDelivery: pendingDelivery,
            isPending: isPending,
            isStreaming: isStreaming,
            renderedMarkdown: renderedMarkdown,
            blocks: blocks,
            modelProviderID: modelProviderID,
            modelID: modelID,
            contextInputTokens: contextInputTokens,
            contentRevision: contentRevision,
            layoutRevision: layoutRevision
        )
    }

    var hasAttachments: Bool {
        blocks.contains { block in
            if case .attachment = block { return true }
            return false
        }
    }

    func withBlocks(_ blocks: [SessionChatTimelineBlock]) -> TimelineRow {
        TimelineRow(
            id: id,
            role: role,
            text: text,
            metadataLabel: metadataLabel,
            pendingDelivery: pendingDelivery,
            isPending: isPending,
            isStreaming: isStreaming,
            renderedMarkdown: renderedMarkdown,
            blocks: blocks,
            modelProviderID: modelProviderID,
            modelID: modelID,
            contextInputTokens: contextInputTokens,
            contentRevision: contentRevision,
            layoutRevision: layoutRevision &+ 1
        )
    }

    static func == (lhs: TimelineRow, rhs: TimelineRow) -> Bool {
        guard lhs.id == rhs.id,
              lhs.role == rhs.role,
              lhs.isPending == rhs.isPending,
              lhs.isStreaming == rhs.isStreaming,
              lhs.modelProviderID == rhs.modelProviderID,
              lhs.modelID == rhs.modelID,
              lhs.contextInputTokens == rhs.contextInputTokens,
              lhs.contentRevision == rhs.contentRevision,
              lhs.layoutRevision == rhs.layoutRevision,
              lhs.renderedMarkdown?.sourceHash == rhs.renderedMarkdown?.sourceHash,
              lhs.renderedMarkdown?.renderTreeVersion == rhs.renderedMarkdown?.renderTreeVersion,
              lhs.renderedMarkdown?.blocks.count == rhs.renderedMarkdown?.blocks.count,
              lhs.text.count == rhs.text.count,
              lhs.metadataLabel == rhs.metadataLabel,
              lhs.pendingDelivery == rhs.pendingDelivery,
              lhs.blocks.count == rhs.blocks.count
        else { return false }
        return lhs.text == rhs.text && lhs.blocks == rhs.blocks
    }
}

/// Internal projected timeline frame with declared change/layout ID sets.
nonisolated struct TimelineFrame: Equatable, Sendable {
    let frameRevision: UInt64
    let rows: [TimelineRow]
    let insertedRowIDs: Set<String>
    let changedRowIDs: Set<String>
    let removedRowIDs: Set<String>
    let layoutChangedRowIDs: Set<String>
    /// Newest streaming row grew in text length without a structural layout key change.
    /// Armed follow may pin via content-size growth; unarmed restore ignores this set.
    let grewBottomRowIDs: Set<String>
    /// Markdown-only subset of `layoutChangedRowIDs`. Still layout-affecting in Phase 2.
    let decorateRowIDs: Set<String>
    /// Region-owned load / refresh / post-send applies that must not be coalesced away.
    let isRequired: Bool

    static let empty = TimelineFrame(
        frameRevision: 0,
        rows: [],
        insertedRowIDs: [],
        changedRowIDs: [],
        removedRowIDs: [],
        layoutChangedRowIDs: [],
        grewBottomRowIDs: [],
        decorateRowIDs: [],
        isRequired: false
    )

    /// Hosts treat inserts and layout changes as layout-affecting for scroll.
    var layoutAffectingRowIDs: Set<String> {
        insertedRowIDs.union(layoutChangedRowIDs)
    }

    /// Armed follow targets: inserts and non-decorate layout changes.
    /// Decorate-only (TextMate / slash collapse) should not re-pin.
    var structureAffectingRowIDs: Set<String> {
        insertedRowIDs.union(layoutChangedRowIDs.subtracting(decorateRowIDs))
    }

    /// Diffable reconfigure targets: content and/or layout changes on existing rows.
    var reconfigureRowIDs: Set<String> {
        changedRowIDs.union(layoutChangedRowIDs)
    }

    func withRequired(_ isRequired: Bool) -> TimelineFrame {
        TimelineFrame(
            frameRevision: frameRevision,
            rows: rows,
            insertedRowIDs: insertedRowIDs,
            changedRowIDs: changedRowIDs,
            removedRowIDs: removedRowIDs,
            layoutChangedRowIDs: layoutChangedRowIDs,
            grewBottomRowIDs: grewBottomRowIDs,
            decorateRowIDs: decorateRowIDs,
            isRequired: isRequired
        )
    }
}

/// Declared-ID apply packet for `MessageListTimelineHost` (Phase 6).
struct MessageListTimelineApplyPacket: Equatable, Sendable {
    let frameRevision: UInt64
    let insertedRowIDs: Set<String>
    let changedRowIDs: Set<String>
    let removedRowIDs: Set<String>
    let layoutChangedRowIDs: Set<String>
    let grewBottomRowIDs: Set<String>
    let decorateRowIDs: Set<String>
    let isRequired: Bool
    let kinds: Set<TimelineApplyKind>

    var layoutAffectingRowIDs: Set<String> {
        insertedRowIDs.union(layoutChangedRowIDs)
    }

    var structureAffectingRowIDs: Set<String> {
        insertedRowIDs.union(layoutChangedRowIDs.subtracting(decorateRowIDs))
    }

    var reconfigureRowIDs: Set<String> {
        changedRowIDs.union(layoutChangedRowIDs)
    }

    /// Latest-wins items still replace the pending snapshot; declared ID sets
    /// union so a later no-delta chrome packet cannot drop a collapse or
    /// label change that was pended under user scroll.
    static func coalescing(
        _ prior: MessageListTimelineApplyPacket?,
        with next: MessageListTimelineApplyPacket?
    ) -> MessageListTimelineApplyPacket? {
        guard let prior else { return next }
        guard let next else { return prior }
        return MessageListTimelineApplyPacket(
            frameRevision: max(prior.frameRevision, next.frameRevision),
            insertedRowIDs: prior.insertedRowIDs.union(next.insertedRowIDs),
            changedRowIDs: prior.changedRowIDs.union(next.changedRowIDs),
            removedRowIDs: prior.removedRowIDs.union(next.removedRowIDs),
            layoutChangedRowIDs: prior.layoutChangedRowIDs.union(next.layoutChangedRowIDs),
            grewBottomRowIDs: prior.grewBottomRowIDs.union(next.grewBottomRowIDs),
            decorateRowIDs: prior.decorateRowIDs.union(next.decorateRowIDs),
            isRequired: prior.isRequired || next.isRequired,
            kinds: prior.kinds.union(next.kinds)
        )
    }

    static func from(_ frame: TimelineFrame) -> MessageListTimelineApplyPacket {
        let apply = TimelineApply.classify(frame)
        return MessageListTimelineApplyPacket(
            frameRevision: frame.frameRevision,
            insertedRowIDs: frame.insertedRowIDs,
            changedRowIDs: frame.changedRowIDs,
            removedRowIDs: frame.removedRowIDs,
            layoutChangedRowIDs: frame.layoutChangedRowIDs,
            grewBottomRowIDs: frame.grewBottomRowIDs,
            decorateRowIDs: frame.decorateRowIDs,
            isRequired: frame.isRequired,
            kinds: apply.kinds
        )
    }

    /// Session Chat path: keep declared timeline sets, and fold Working status
    /// chrome into revision. Label or fade *changes* land in `changedRowIDs` so
    /// that stable row reconfigures without clearing the packet (OPE-136).
    /// Collapse toggles land in `layoutChangedRowIDs` and `decorateRowIDs` so
    /// height 0 ↔ painted does not insert, remove, or structure-pin the reserved
    /// identity. An unchanged label+collapse+fade while the timeline publishes
    /// must not reconfigure it.
    /// Syncing uses a top overlay pill and is not a timeline row.
    static func from(
        _ frame: TimelineFrame,
        workingIndicatorLabel: String?,
        previousWorkingIndicatorLabel: String?,
        workingIndicatorCollapsed: Bool = false,
        previousWorkingIndicatorCollapsed: Bool = false,
        workingIndicatorFading: Bool = false,
        previousWorkingIndicatorFading: Bool = false
    ) -> MessageListTimelineApplyPacket {
        let base = from(frame)
        let delta = SessionChatStatusChrome.applyDelta(
            current: workingIndicatorLabel,
            previous: previousWorkingIndicatorLabel,
            currentCollapsed: workingIndicatorCollapsed,
            previousCollapsed: previousWorkingIndicatorCollapsed,
            currentFading: workingIndicatorFading,
            previousFading: previousWorkingIndicatorFading
        )
        let chrome = SessionChatStatusChrome.fingerprint(
            workingIndicatorLabel: workingIndicatorLabel,
            collapsed: workingIndicatorCollapsed,
            fading: workingIndicatorFading
        )
        var kinds = base.kinds
        if !delta.inserted.isEmpty || !delta.removed.isEmpty {
            kinds.insert(.structure)
        }
        if !delta.layoutChanged.isEmpty {
            kinds.insert(.decorate)
        }
        if !delta.changed.isEmpty {
            kinds.insert(.content)
        }
        return MessageListTimelineApplyPacket(
            frameRevision: base.frameRevision &+ (chrome &* 1_000_003),
            insertedRowIDs: base.insertedRowIDs.union(delta.inserted),
            changedRowIDs: base.changedRowIDs.union(delta.changed),
            removedRowIDs: base.removedRowIDs.union(delta.removed),
            layoutChangedRowIDs: base.layoutChangedRowIDs.union(delta.layoutChanged),
            grewBottomRowIDs: base.grewBottomRowIDs,
            decorateRowIDs: base.decorateRowIDs.union(delta.layoutChanged),
            isRequired: base.isRequired,
            kinds: kinds
        )
    }

    /// Gallery / non-projector path: treat every surviving ID as needing reconfigure.
    static func reconfigureAllSurviving(
        frameRevision: UInt64,
        previousIDs: Set<String>,
        nextIDs: [String]
    ) -> MessageListTimelineApplyPacket {
        let nextSet = Set(nextIDs)
        let inserted = nextSet.subtracting(previousIDs)
        let surviving = nextSet.intersection(previousIDs)
        return MessageListTimelineApplyPacket(
            frameRevision: frameRevision,
            insertedRowIDs: inserted,
            changedRowIDs: surviving,
            removedRowIDs: previousIDs.subtracting(nextSet),
            layoutChangedRowIDs: inserted,
            grewBottomRowIDs: [],
            decorateRowIDs: [],
            isRequired: false,
            kinds: surviving.isEmpty && inserted.isEmpty
                ? []
                : [.structure, .content]
        )
    }
}

/// Stable IDs for Session Chat working rows appended outside the projector.
enum SessionChatStatusChrome {
    static let workingRowID = "session-chat-working-indicator"

    static func fingerprint(
        workingIndicatorLabel: String?,
        collapsed: Bool = false,
        fading: Bool = false
    ) -> UInt64 {
        var value: UInt64 = 0
        guard let workingIndicatorLabel else { return value }
        value = value &* 1_000_003 &+ 2
        if collapsed {
            value = value &* 1_000_003 &+ 3
        }
        if fading {
            value = value &* 1_000_003 &+ 5
        }
        for scalar in workingIndicatorLabel.unicodeScalars {
            value = value &* 31 &+ UInt64(scalar.value)
        }
        return value
    }

    static func applyDelta(
        current: String?,
        previous: String?,
        currentCollapsed: Bool = false,
        previousCollapsed: Bool = false,
        currentFading: Bool = false,
        previousFading: Bool = false
    ) -> (
        inserted: Set<String>,
        changed: Set<String>,
        removed: Set<String>,
        layoutChanged: Set<String>
    ) {
        switch (previous, current) {
        case (nil, .some):
            return ([workingRowID], [], [], currentCollapsed ? [workingRowID] : [])
        case (.some, nil):
            return ([], [], [workingRowID], [])
        case let (previous?, current?):
            let labelChanged = previous != current
            let collapseChanged = previousCollapsed != currentCollapsed
            let fadeChanged = previousFading != currentFading
            return (
                [],
                (labelChanged || collapseChanged || fadeChanged) ? [workingRowID] : [],
                [],
                collapseChanged ? [workingRowID] : []
            )
        default:
            return ([], [], [], [])
        }
    }
}

/// Explicit optimistic merge inputs supplied by `SessionChatTimeline`.
nonisolated struct OptimisticInputs: Sendable {
    let pendingRows: [TimelineRow]
    let baselineSignatureCounts: [String: Int]
    let mergePending: Bool

    static let none = OptimisticInputs(
        pendingRows: [],
        baselineSignatureCounts: [:],
        mergePending: false
    )
}

extension TimelineRow {
    func toSessionChatRow() -> SessionChatRow {
        SessionChatRow(
            id: id,
            role: role,
            text: text,
            metadataLabel: metadataLabel,
            pendingDelivery: pendingDelivery,
            isPending: isPending,
            isStreaming: isStreaming,
            renderedMarkdown: renderedMarkdown,
            blocks: blocks,
            modelProviderID: modelProviderID,
            modelID: modelID,
            contextInputTokens: contextInputTokens
        )
    }

    static func fromSessionChatRow(_ row: SessionChatRow, layoutRevision: UInt64 = 1) -> TimelineRow {
        TimelineRow(
            id: row.id,
            role: row.role,
            text: row.text,
            metadataLabel: row.metadataLabel,
            pendingDelivery: row.pendingDelivery,
            isPending: row.isPending,
            isStreaming: row.isStreaming,
            renderedMarkdown: row.renderedMarkdown,
            blocks: row.blocks,
            modelProviderID: row.modelProviderID,
            modelID: row.modelID,
            contextInputTokens: row.contextInputTokens,
            contentRevision: ContentRevision.of(row.text),
            layoutRevision: layoutRevision
        )
    }
}
