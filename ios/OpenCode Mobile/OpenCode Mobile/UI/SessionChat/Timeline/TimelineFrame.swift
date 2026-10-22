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
    /// Server row id → list identity for server echoes that replaced an optimistic
    /// send row. Hosts key the echo under the optimistic id, so the cell reconfigures
    /// in place instead of a delete and insert. Carried across frames.
    var listIDAliases: [String: String] = [:]

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
            isRequired: isRequired,
            listIDAliases: listIDAliases
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
    /// Collapse (painted → height 0) is decorate-only so Idle dismiss does not
    /// structure-pin. Expand (height 0 → painted) stays a real layout change —
    /// decorate-only left the reserved row stuck at 0 and hid Working / Idle.
    /// An unchanged label+collapse+fade while the timeline publishes must not
    /// reconfigure it. Syncing uses a top overlay pill and is not a timeline row.
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
        let decorateChrome: Set<String> =
            if workingIndicatorLabel != nil,
               workingIndicatorCollapsed,
               !delta.layoutChanged.isEmpty
            {
                delta.layoutChanged
            } else {
                []
            }
        var kinds = base.kinds
        if !delta.inserted.isEmpty
            || !delta.removed.isEmpty
            || !delta.layoutChanged.subtracting(decorateChrome).isEmpty
        {
            kinds.insert(.structure)
        }
        if !decorateChrome.isEmpty {
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
            decorateRowIDs: base.decorateRowIDs.union(decorateChrome),
            isRequired: base.isRequired,
            kinds: kinds
        )
    }

    /// Chip timeline: a step paragraph expanded past `+N more` lives in the
    /// Screen Model, not the projected frame. Rows holding a paragraph whose
    /// expansion changed reconfigure as a layout change (the chip row grows),
    /// and the expansion set folds into revision like Working chrome so an
    /// unchanged set never re-applies.
    func addingStepParagraphExpansion(
        rows: [SessionChatRow],
        expanded: [String: Int],
        previousExpanded: [String: Int]
    ) -> MessageListTimelineApplyPacket {
        let fingerprint = SessionChatStepParagraphExpansion.fingerprint(expanded)
        let toggled = SessionChatStepParagraphExpansion.changedParagraphIDs(expanded, previousExpanded)
        let rowIDs = SessionChatStepParagraphExpansion.rowIDs(containing: toggled, in: rows)
        guard fingerprint != 0 || !rowIDs.isEmpty else { return self }
        var nextKinds = kinds
        if !rowIDs.isEmpty {
            nextKinds.insert(.structure)
            nextKinds.insert(.content)
        }
        return MessageListTimelineApplyPacket(
            frameRevision: frameRevision &+ (fingerprint &* 998_244_353),
            insertedRowIDs: insertedRowIDs,
            changedRowIDs: changedRowIDs.union(rowIDs),
            removedRowIDs: removedRowIDs,
            layoutChangedRowIDs: layoutChangedRowIDs.union(rowIDs),
            grewBottomRowIDs: grewBottomRowIDs,
            decorateRowIDs: decorateRowIDs,
            isRequired: isRequired,
            kinds: nextKinds
        )
    }

    /// Step rail and activity card: fold / expansion state and the live run
    /// live in the Screen Model and the host, not the projected frame. Rows
    /// holding a rail or card whose fold, expansion or liveness changed
    /// reconfigure as a layout change, and the state folds into revision so an
    /// unchanged state never re-applies.
    func addingStepRailState(
        rows: [SessionChatRow],
        state: SessionChatStepRailViewState,
        previous: SessionChatStepRailViewState
    ) -> MessageListTimelineApplyPacket {
        var keyed = Dictionary(uniqueKeysWithValues: state.unfolded.map { ($0, 0) })
        for (child, action) in state.childActions { keyed["child:" + child + ":" + action] = 2 }
        if let live = state.liveRailID { keyed["live:" + live] = 1 }
        if let live = state.liveCardID { keyed["liveCard:" + live] = 3 }
        if state.awaitingUser { keyed["awaitingUser"] = 4 }
        for (card, footer) in state.activityFooters { keyed["footer:" + card + ":" + footer.revisionKey] = 5 }
        for step in state.debouncedStepIDs { keyed["debounced:" + step] = 6 }
        let fingerprint = SessionChatStepParagraphExpansion.fingerprint(keyed)
        var toggled = state.unfolded.symmetricDifference(previous.unfolded)
        if state.liveRailID != previous.liveRailID {
            toggled.formUnion([state.liveRailID, previous.liveRailID].compactMap { $0 })
        }
        if state.liveCardID != previous.liveCardID {
            toggled.formUnion([state.liveCardID, previous.liveCardID].compactMap { $0 })
        }
        if state.awaitingUser != previous.awaitingUser || state.childActions != previous.childActions,
           let live = state.liveCardID
        {
            toggled.insert(live)
        }
        for card in Set(state.activityFooters.keys).union(previous.activityFooters.keys)
            where state.activityFooters[card] != previous.activityFooters[card]
        {
            toggled.insert(card)
        }
        let debounceChanged = state.debouncedStepIDs.symmetricDifference(previous.debouncedStepIDs)
        if !debounceChanged.isEmpty {
            toggled.formUnion(SessionChatStepParagraphExpansion.stepRunIDs(containingSteps: debounceChanged, in: rows))
        }
        let rowIDs = SessionChatStepParagraphExpansion.rowIDs(containingStepRails: toggled, in: rows)
        guard fingerprint != 0 || !rowIDs.isEmpty else { return self }
        var nextKinds = kinds
        if !rowIDs.isEmpty {
            nextKinds.insert(.structure)
            nextKinds.insert(.content)
        }
        return MessageListTimelineApplyPacket(
            frameRevision: frameRevision &+ (fingerprint &* 1_000_000_009),
            insertedRowIDs: insertedRowIDs,
            changedRowIDs: changedRowIDs.union(rowIDs),
            removedRowIDs: removedRowIDs,
            layoutChangedRowIDs: layoutChangedRowIDs.union(rowIDs),
            grewBottomRowIDs: grewBottomRowIDs,
            decorateRowIDs: decorateRowIDs,
            isRequired: isRequired,
            kinds: nextKinds
        )
    }

    /// Model-switch notices show the catalog name, which can arrive after the row.
    /// Rows whose resolved name changed reconfigure; the names fold into revision
    /// so an unchanged catalog never re-applies.
    func addingNoticeModelNames(
        rows: [SessionChatRow],
        names: TimelineNoticeModelNames,
        previous: TimelineNoticeModelNames
    ) -> MessageListTimelineApplyPacket {
        var fingerprint: UInt64 = 0
        var rowIDs: Set<String> = []
        for row in rows where row.role == .notice {
            for block in row.blocks {
                guard case .notice(let blockID, let notice) = block,
                      case .modelChanged(let model, _) = notice.kind
                else { continue }
                let name = names.name(for: model)
                fingerprint = fingerprint &+ SessionChatStepParagraphExpansion.fingerprint([blockID + (name ?? ""): 0])
                if name != previous.name(for: model) { rowIDs.insert(row.id) }
            }
        }
        guard fingerprint != 0 else { return self }
        var nextKinds = kinds
        if !rowIDs.isEmpty {
            nextKinds.insert(.structure)
            nextKinds.insert(.content)
        }
        return MessageListTimelineApplyPacket(
            frameRevision: frameRevision &+ (fingerprint &* 1_000_000_007),
            insertedRowIDs: insertedRowIDs,
            changedRowIDs: changedRowIDs.union(rowIDs),
            removedRowIDs: removedRowIDs,
            layoutChangedRowIDs: layoutChangedRowIDs.union(rowIDs),
            grewBottomRowIDs: grewBottomRowIDs,
            decorateRowIDs: decorateRowIDs,
            isRequired: isRequired,
            kinds: nextKinds
        )
    }

    /// Rekeys declared row IDs into list identity (`TimelineFrame.listIDAliases`).
    /// An echo that replaced its optimistic row in this frame lands in both the
    /// inserted and removed sets under one identity; it becomes a layout change
    /// so the host reconfigures that cell.
    func aliasingRowIDs(_ aliases: [String: String]) -> MessageListTimelineApplyPacket {
        guard !aliases.isEmpty else { return self }
        func rekey(_ ids: Set<String>) -> Set<String> {
            Set(ids.map { aliases[$0] ?? $0 })
        }
        let inserted = rekey(insertedRowIDs)
        let removed = rekey(removedRowIDs)
        let replaced = inserted.intersection(removed)
        return MessageListTimelineApplyPacket(
            frameRevision: frameRevision,
            insertedRowIDs: inserted.subtracting(replaced),
            changedRowIDs: rekey(changedRowIDs).union(replaced),
            removedRowIDs: removed.subtracting(replaced),
            layoutChangedRowIDs: rekey(layoutChangedRowIDs).union(replaced),
            grewBottomRowIDs: rekey(grewBottomRowIDs),
            decorateRowIDs: rekey(decorateRowIDs),
            isRequired: isRequired,
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

/// Step rail and activity card presentation state the timeline host threads to each row.
struct SessionChatStepRailViewState: Equatable {
    var unfolded: Set<String>
    /// Current action of each running child session on a live activity card,
    /// by child session id (#779).
    var childActions: [String: String] = [:]
    /// The rail or activity card that shows live status and owns the Working footer, or nil.
    var liveRailID: String?
    /// The activity card shown live: `liveRailID`, or the last card while the turn waits on the user.
    var liveCardID: String?
    var awaitingUser = false
    /// What each held activity card footer shows, by card id (#779).
    var activityFooters: [String: SessionChatActivityFooter] = [:]
    /// In-flight steps the entry debounce keeps off rails and footers (#779).
    var debouncedStepIDs: Set<String> = []

    static let empty = SessionChatStepRailViewState(unfolded: [], liveRailID: nil)
}

/// Chip-timeline `+N more` expansion helpers for the timeline host.
enum SessionChatStepParagraphExpansion {
    /// Order-independent, process-stable fingerprint over paragraph id and
    /// chip count; 0 for no expansion.
    static func fingerprint(_ expanded: [String: Int]) -> UInt64 {
        expanded.reduce(UInt64(0)) { sum, entry in
            var value: UInt64 = 1
            for scalar in entry.key.unicodeScalars {
                value = value &* 31 &+ UInt64(scalar.value)
            }
            value = value &* 31 &+ UInt64(max(0, entry.value))
            return sum &+ value
        }
    }

    /// Paragraphs whose expansion appeared, disappeared or changed its chip count.
    static func changedParagraphIDs(_ expanded: [String: Int], _ previous: [String: Int]) -> Set<String> {
        var changed: Set<String> = []
        for key in Set(expanded.keys).union(previous.keys) where expanded[key] != previous[key] {
            changed.insert(key)
        }
        return changed
    }

    /// Rows whose blocks hold a step rail or activity card with one of `railIDs`.
    /// Ids of the step rails and activity cards that hold one of `stepIDs`.
    static func stepRunIDs(containingSteps stepIDs: Set<String>, in rows: [SessionChatRow]) -> Set<String> {
        var result: Set<String> = []
        for row in rows {
            for block in row.blocks {
                switch block {
                case .stepRail(_, let rail) where rail.steps.contains(where: { stepIDs.contains($0.id) }):
                    result.insert(rail.id)
                case .activityCard(_, let card) where card.steps.contains(where: { stepIDs.contains($0.id) }):
                    result.insert(card.id)
                default:
                    break
                }
            }
        }
        return result
    }

    static func rowIDs(containingStepRails railIDs: Set<String>, in rows: [SessionChatRow]) -> Set<String> {
        guard !railIDs.isEmpty else { return [] }
        var result: Set<String> = []
        for row in rows {
            let holds = row.blocks.contains { block in
                switch block {
                case .stepRail(_, let rail): return railIDs.contains(rail.id)
                case .activityCard(_, let card): return railIDs.contains(card.id)
                default: return false
                }
            }
            if holds { result.insert(row.id) }
        }
        return result
    }

    /// Rows whose blocks hold a step paragraph with one of `paragraphIDs`.
    static func rowIDs(containing paragraphIDs: Set<String>, in rows: [SessionChatRow]) -> Set<String> {
        guard !paragraphIDs.isEmpty else { return [] }
        var result: Set<String> = []
        for row in rows {
            let holds = row.blocks.contains { block in
                guard case .stepParagraph(_, let paragraph) = block else { return false }
                return paragraphIDs.contains(paragraph.id)
            }
            if holds { result.insert(row.id) }
        }
        return result
    }
}
