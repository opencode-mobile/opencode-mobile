import Foundation

/// Projected timeline row with per-row revision metadata.
/// Adapted to `SessionChatRow` for Screen Model / hosts in Phase 5.
struct TimelineRow: Equatable, Identifiable, Sendable {
    let id: String
    let role: MessageRole
    let text: String
    let metadataLabel: String
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
              lhs.renderedMarkdown?.blocks.count == rhs.renderedMarkdown?.blocks.count,
              lhs.text.count == rhs.text.count,
              lhs.metadataLabel == rhs.metadataLabel,
              lhs.blocks.count == rhs.blocks.count
        else { return false }
        return lhs.text == rhs.text && lhs.blocks == rhs.blocks
    }
}

/// Internal projected timeline frame with declared change/layout ID sets.
struct TimelineFrame: Equatable, Sendable {
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

    var reconfigureRowIDs: Set<String> {
        changedRowIDs.union(layoutChangedRowIDs)
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

/// Explicit optimistic merge inputs supplied by `SessionChatTimeline`.
struct OptimisticInputs: Sendable {
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
