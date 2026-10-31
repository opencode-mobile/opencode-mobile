import Foundation

/// Pure `DisplayFrame` → `TimelineFrame` projector.
/// Optimistic reconciliation and return-previous structural sharing live here;
/// `SessionChatTimeline` owns lifecycle and supplies optimistic inputs.
///
/// Must stay `nonisolated` so structural conversion can run off MainActor
/// (Android already projects on a worker dispatcher).
nonisolated enum SessionChatTimelineProjector {
    private static let sentTimeLock = NSLock()
    private static let sentTimeFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "h:mm a"
        return formatter
    }()

    /// Thread-safe metadata label used by off-main projection.
    static func metadataLabel(for message: Message) -> String {
        metadataLabel(
            mode: message.mode,
            model: message.model,
            variant: message.variant,
            sentAtEpochMs: message.sentAtEpochMs
        )
    }

    static func metadataLabel(
        mode: String?,
        model: String?,
        variant: String? = nil,
        sentAtEpochMs: Int64? = nil
    ) -> String {
        let timeLabel: String? = sentAtEpochMs.map { epochMs in
            let date = Date(timeIntervalSince1970: TimeInterval(epochMs) / 1_000)
            sentTimeLock.lock()
            defer { sentTimeLock.unlock() }
            return sentTimeFormatter.string(from: date)
        }
        return [
            mode.map { TitleCaseIdentifier.displayName(for: $0) },
            model.map { TitleCaseIdentifier.displayName(for: $0) },
            variant.map { TitleCaseIdentifier.displayName(for: $0) },
            timeLabel,
        ].compactMap { $0 }.joined(separator: " · ")
    }

    static func project(
        frame: DisplayFrame,
        optimistic: OptimisticInputs = .none,
        previous: TimelineFrame? = nil,
        metadataLabel: (@Sendable (Message) -> String)? = nil
    ) -> TimelineFrame {
        let resolveLabel = metadataLabel ?? { message in Self.metadataLabel(for: message) }
        let previousByID = Dictionary(
            (previous?.rows ?? []).map { ($0.id, $0) },
            uniquingKeysWith: { _, latest in latest }
        )

        let serverRows = serverRows(from: frame, previousByID: previousByID, metadataLabel: resolveLabel)

        let merged: (rows: [TimelineRow], echoes: [String: String], hiddenEchoIDs: Set<String>) =
            if optimistic.mergePending {
                mergeWithPending(
                    serverRows,
                    pendingRows: optimistic.pendingRows,
                    baselineIDs: optimistic.baselineIDs
                )
            } else {
                (serverRows, [:], [])
            }
        let visibleRows = merged.rows.filter { row in
            !isQueuedPrompt(row) && !merged.hiddenEchoIDs.contains(row.id)
        }

        let previousIDs = Set(previousByID.keys)
        let nextIDs = Set(visibleRows.map(\.id))
        // Alias only an echo that replaces its optimistic row in this frame. A server
        // row the host already shows under its own id keeps that identity.
        var listIDAliases = (previous?.listIDAliases ?? [:]).filter { nextIDs.contains($0.key) }
        for (serverID, pendingID) in merged.echoes
        where previousIDs.contains(pendingID) && !previousIDs.contains(serverID) && nextIDs.contains(serverID) {
            listIDAliases[serverID] = pendingID
        }
        let rows = markSteers(
            visibleRows,
            previousByID: previousByID,
            listIDAliases: listIDAliases,
            runIsActive: runIsActive(frame)
        )
        let inserted = nextIDs.subtracting(previousIDs)
        let removed = previousIDs.subtracting(nextIDs)

        var changed: Set<String> = []
        var layoutChanged: Set<String> = []
        var grewBottom: Set<String> = []
        var decorate: Set<String> = []
        let newestID = rows.last?.id
        for row in rows {
            guard let prior = previousByID[row.id] else {
                // Inserted rows are layout-affecting for hosts.
                layoutChanged.insert(row.id)
                continue
            }
            if !contentEqual(prior, row) {
                changed.insert(row.id)
            }
            if row.layoutRevision != prior.layoutRevision || layoutFingerprint(prior) != layoutFingerprint(row) {
                layoutChanged.insert(row.id)
                if isMarkdownOnlyLayoutChange(prior: prior, row: row) {
                    decorate.insert(row.id)
                }
            } else if row.id == newestID,
                      row.isStreaming,
                      row.text.count > prior.text.count
            {
                grewBottom.insert(row.id)
            }
        }

        return TimelineFrame(
            frameRevision: frame.frameRevision,
            rows: rows,
            insertedRowIDs: inserted,
            changedRowIDs: changed,
            removedRowIDs: removed,
            layoutChangedRowIDs: layoutChanged,
            grewBottomRowIDs: grewBottom,
            decorateRowIDs: decorate,
            isRequired: false,
            listIDAliases: listIDAliases
        )
    }

    /// Project server bodies without hiding inbox rows. Used for send-time baselines.
    static func serverRows(
        from frame: DisplayFrame,
        previous: TimelineFrame? = nil,
        metadataLabel: (@Sendable (Message) -> String)? = nil
    ) -> [TimelineRow] {
        let resolveLabel = metadataLabel ?? { message in Self.metadataLabel(for: message) }
        let previousByID = Dictionary(
            (previous?.rows ?? []).map { ($0.id, $0) },
            uniquingKeysWith: { _, latest in latest }
        )
        return serverRows(from: frame, previousByID: previousByID, metadataLabel: resolveLabel)
    }

    static func isHiddenPendingPrompt(_ row: SessionChatRow) -> Bool {
        row.role == .user && row.pendingDelivery != nil
    }

    /// Optimistic send rows the timeline shows: everything but a queued prompt,
    /// which only the queued strip shows. A steer shows as a pending bubble.
    static func displayedOptimisticSendRows(_ rows: [SessionChatRow]) -> [SessionChatRow] {
        rows.filter { !($0.role == .user && $0.pendingDelivery == .queue) }
    }

    static func shouldMarkTimelineReadyAfterOptimisticSend(
        displayedRows: [SessionChatRow],
        hadPriorDisplayedRows: Bool
    ) -> Bool {
        !displayedRows.isEmpty || hadPriorDisplayedRows
    }

    private static func serverRows(
        from frame: DisplayFrame,
        previousByID: [String: TimelineRow],
        metadataLabel: @Sendable (Message) -> String
    ) -> [TimelineRow] {
        var serverRows: [TimelineRow] = []
        serverRows.reserveCapacity(frame.messages.count)
        for body in frame.messages {
            serverRows.append(makeRow(from: body, previous: previousByID[body.id], metadataLabel: metadataLabel))
        }
        return serverRows
    }

    // MARK: - Row build + return-previous

    private static func makeRow(
        from body: DisplayMessageBody,
        previous: TimelineRow?,
        metadataLabel: @Sendable (Message) -> String
    ) -> TimelineRow {
        let message = body.message
        let label = metadataLabel(message)
        // Prefer sole text-block copy so slash-command collapse is what chat shows
        // and copies, not the server-expanded template still on Message.text.
        let displayText: String = {
            let texts = body.blocks.compactMap { $0.textContent?.text }
            if texts.count == 1 { return texts[0] }
            // A chip-merged run spans messages; `Message.text` only covers the first.
            let paragraphTexts = body.blocks.compactMap { block -> String? in
                if case .stepParagraph = block { return block.textContent?.text }
                return nil
            }
            return paragraphTexts.count > 1 ? paragraphTexts.joined(separator: "\n\n") : message.text
        }()
        let contentRevision = ContentRevision.of(displayText)
        let bridgeMarkdown = bridgeRenderedMarkdown(body: body, previous: previous)

        if let previous,
           !previous.isPending,
           previous.text == displayText,
           previous.isStreaming == body.isStreaming,
           previous.role == message.role,
           previous.blocks == body.blocks,
           previous.metadataLabel == label,
           previous.modelProviderID == message.modelProviderID,
           previous.modelID == message.modelID,
           previous.contextInputTokens == message.contextInputTokens,
           previous.sentAtEpochMs == message.sentAtEpochMs,
           previous.renderedMarkdown?.sourceHash == bridgeMarkdown?.sourceHash,
           previous.renderedMarkdown?.renderTreeVersion == bridgeMarkdown?.renderTreeVersion,
           previous.renderedMarkdown?.blocks.count == bridgeMarkdown?.blocks.count,
           previous.contentRevision == contentRevision,
           previous.pendingDelivery == (message.isWaitingForPickup ? message.followUpDelivery : nil)
        {
            return previous
        }

        let layoutRevision: UInt64 =
            if let previous {
                layoutFingerprint(previous) == layoutFingerprint(
                    provisional(
                        id: message.id,
                        role: message.role,
                        text: displayText,
                        metadataLabel: label,
                        pendingDelivery: message.isWaitingForPickup ? message.followUpDelivery : nil,
                        isStreaming: body.isStreaming,
                        renderedMarkdown: bridgeMarkdown,
                        blocks: body.blocks,
                        contentRevision: contentRevision,
                        layoutRevision: previous.layoutRevision,
                        steer: previous.steer
                    )
                )
                    ? previous.layoutRevision
                    : previous.layoutRevision &+ 1
            } else {
                1
            }

        return TimelineRow(
            id: message.id,
            role: message.role,
            text: displayText,
            metadataLabel: label,
            pendingDelivery: message.isWaitingForPickup ? message.followUpDelivery : nil,
            isPending: false,
            isStreaming: body.isStreaming,
            renderedMarkdown: bridgeMarkdown,
            blocks: body.blocks,
            modelProviderID: message.modelProviderID,
            modelID: message.modelID,
            contextInputTokens: message.contextInputTokens,
            sentAtEpochMs: message.sentAtEpochMs,
            steer: previous?.steer,
            contentRevision: contentRevision,
            layoutRevision: layoutRevision
        )
    }

    private static func bridgeRenderedMarkdown(
        body: DisplayMessageBody,
        previous: TimelineRow?
    ) -> NativeMarkdownDocument? {
        if let root = body.renderedMarkdown { return root }
        if let fromBlock = soleTextBlockMarkdown(body.blocks) { return fromBlock }
        return previous?.renderedMarkdown.flatMap { document in
            body.isStreaming && (previous?.isStreaming == true || previous?.text == body.message.text)
                ? document
                : nil
        }
    }

    private static func soleTextBlockMarkdown(_ blocks: [SessionChatTimelineBlock]) -> NativeMarkdownDocument? {
        var found: NativeMarkdownDocument?
        var textCount = 0
        for block in blocks {
            guard let markdown = block.textContent?.renderedMarkdown else {
                if block.textContent != nil { textCount += 1 }
                continue
            }
            textCount += 1
            if textCount > 1 { return nil }
            found = markdown
        }
        return found
    }

    // MARK: - Optimistic merge (ported from SessionChatTimeline)

    /// A user row with a pending delivery (steer or queue), including optimistic
    /// `isPending` rows. Any server echo retires such an optimistic row.
    static func isHiddenPendingPrompt(_ row: TimelineRow) -> Bool {
        row.role == .user && row.pendingDelivery != nil
    }

    /// A queued prompt: the queued strip is its only surface until it is delivered.
    static func isQueuedPrompt(_ row: TimelineRow) -> Bool {
        row.role == .user && row.pendingDelivery == .queue
    }

    // MARK: - Steering

    /// The newest assistant message has not finished, so the session is mid-run.
    /// v2 also enqueues an idle prompt as a steer; this keeps that one unlabelled.
    static func runIsActive(_ frame: DisplayFrame) -> Bool {
        guard let assistant = frame.messages.last(where: { $0.message.role == .assistant })?.message else {
            return false
        }
        return assistant.completedAtEpochMs == nil && assistant.failure == nil
    }

    /// Marks prompts steered into a running turn. A waiting steer is pending; a row
    /// that was a steer in the previous frame keeps its mark once delivered, so the
    /// server's copy replaces the pending bubble in place and keeps the label.
    /// Consecutive steers share one label, on the first.
    static func markSteers(
        _ rows: [TimelineRow],
        previousByID: [String: TimelineRow],
        listIDAliases: [String: String],
        runIsActive: Bool
    ) -> [TimelineRow] {
        var marked: [TimelineRow] = []
        marked.reserveCapacity(rows.count)
        var previousWasSteer = false
        for row in rows {
            // A notice line (agent or model change) between steers does not split them.
            if row.role == .notice {
                marked.append(row)
                continue
            }
            let isPending = row.role == .user && row.pendingDelivery == .steer
            let wasSteer = previousByID[row.id]?.steer != nil
                || listIDAliases[row.id].flatMap { previousByID[$0]?.steer } != nil
            // `isPending` optimistic rows only carry a steer delivery for a busy send.
            let isSteer = row.role == .user && (wasSteer || (isPending && (row.isPending || runIsActive)))
            let steer = isSteer ? SessionChatSteer(isPending: isPending, showsLabel: !previousWasSteer) : nil
            previousWasSteer = isSteer
            marked.append(row.steer == steer ? row : row.withSteer(steer))
        }
        return marked
    }

    /// Merged rows; for each dropped optimistic row matched to its server echo,
    /// `serverID → pendingID`; and waiting-steer echoes of a still-shown optimistic
    /// row, which stay hidden so the prompt never shows twice.
    private static func mergeWithPending(
        _ serverRows: [TimelineRow],
        pendingRows: [TimelineRow],
        baselineIDs: Set<String>
    ) -> (rows: [TimelineRow], echoes: [String: String], hiddenEchoIDs: Set<String>) {
        guard !pendingRows.isEmpty else { return (serverRows, [:], []) }
        let pendingToKeep = unmatchedPending(
            pendingRows,
            serverRows: serverRows,
            baselineIDs: baselineIDs
        )
        let keptIDs = Set(pendingToKeep.map(\.id))
        let dropped = pendingRows.filter { !keptIDs.contains($0.id) }
        let chromed = applyPendingChrome(serverRows, dropped: dropped, baselineIDs: baselineIDs)
        let keptShown = pendingToKeep.filter { !isHiddenPendingPrompt($0) }
        let hiddenEchoIDs = Set(
            serverRows
                .filter { server in
                    server.pendingDelivery == .steer
                        && keptShown.contains { isNewServerEcho(server, of: $0, baselineIDs: baselineIDs) }
                }
                .map(\.id)
        )
        // A same-id waiting echo is hidden by id; drop it here so the optimistic row stays.
        let rows = splicePending(
            chromed.rows.filter { !hiddenEchoIDs.contains($0.id) },
            pendingToKeep: pendingToKeep
        )
        return (rows, chromed.echoes, hiddenEchoIDs.subtracting(keptIDs))
    }

    /// Pending optimistic rows that no new server echo accounts for yet. Shared with
    /// `SessionChatTimeline` so reconciliation and merge agree on "acknowledged".
    ///
    /// A shown optimistic row is not retired by a waiting steer echo. v2 enqueues every
    /// prompt, idle sends included, as a steer delivered at the next step boundary;
    /// retiring on that hidden copy left a frame with neither bubble. A queued echo
    /// still retires it, since the pending strip owns a real queue.
    static func unmatchedPending(
        _ pendingRows: [TimelineRow],
        serverRows: [TimelineRow],
        baselineIDs: Set<String>
    ) -> [TimelineRow] {
        let shownPending = pendingRows.filter { !isHiddenPendingPrompt($0) }
        guard !shownPending.isEmpty else {
            return unmatched(pendingRows, serverRows: serverRows, baselineIDs: baselineIDs)
        }
        let keptIDs = Set(
            unmatched(
                shownPending,
                serverRows: serverRows.filter { $0.pendingDelivery != .steer },
                baselineIDs: baselineIDs
            ).map(\.id)
        ).union(
            unmatched(
                pendingRows.filter(isHiddenPendingPrompt),
                serverRows: serverRows,
                baselineIDs: baselineIDs
            ).map(\.id)
        )
        return pendingRows.filter { keptIDs.contains($0.id) }
    }

    private static func unmatched(
        _ pendingRows: [TimelineRow],
        serverRows: [TimelineRow],
        baselineIDs: Set<String>
    ) -> [TimelineRow] {
        pendingRows.filter { pending in
            !serverRows.contains { isNewServerEcho($0, of: pending, baselineIDs: baselineIDs) }
        }
    }

    /// A pending row whose send carried a `ClientMessageID` is echoed only by the user
    /// row the server created under that id, whatever its text or filenames.
    ///
    /// A v2 command takes no id, and its echo is the expanded template, so the first
    /// user row that was not loaded when the send began stands for it.
    static func isNewServerEcho(_ server: TimelineRow, of pending: TimelineRow, baselineIDs: Set<String>) -> Bool {
        guard server.role == .user else { return false }
        if ClientMessageID.isClientID(pending.id) { return server.id == pending.id }
        return !baselineIDs.contains(server.id)
    }

    private static func splicePending(
        _ serverRows: [TimelineRow],
        pendingToKeep: [TimelineRow]
    ) -> [TimelineRow] {
        guard !pendingToKeep.isEmpty else { return serverRows }
        // A steer belongs after the step it steers, so only plain sends move above the reply.
        let steers = pendingToKeep.filter { $0.pendingDelivery != nil }
        let sends = pendingToKeep.filter { $0.pendingDelivery == nil }
        if !sends.isEmpty, let index = serverRows.firstIndex(where: { $0.role == .assistant && $0.isStreaming }) {
            let precededByUser = index > 0 && serverRows[index - 1].role == .user
            if !precededByUser {
                return Array(serverRows[..<index]) + sends + Array(serverRows[index...]) + steers
            }
        }
        return serverRows + sends + steers
    }

    private static func applyPendingChrome(
        _ serverRows: [TimelineRow],
        dropped: [TimelineRow],
        baselineIDs: Set<String>
    ) -> (rows: [TimelineRow], echoes: [String: String]) {
        guard !dropped.isEmpty else { return (serverRows, [:]) }
        var pendingByServerID: [String: TimelineRow] = [:]
        for pending in dropped {
            guard let target = serverRows.first(where: { server in
                pendingByServerID[server.id] == nil && isNewServerEcho(server, of: pending, baselineIDs: baselineIDs)
            }) else { continue }
            pendingByServerID[target.id] = pending
        }
        guard !pendingByServerID.isEmpty else { return (serverRows, [:]) }
        let echoes = pendingByServerID.mapValues(\.id)
        let rows = serverRows.map { row in
            guard let pending = pendingByServerID[row.id] else { return row }
            var next = row
            if row.metadataLabel.isEmpty, !pending.metadataLabel.isEmpty {
                next = next.withMetadataLabel(pending.metadataLabel)
            }
            if !row.hasAttachments, pending.hasAttachments {
                let attachments = pending.blocks.filter { block in
                    if case .attachment = block { return true }
                    return false
                }
                next = next.withBlocks(next.blocks + attachments)
            }
            return next
        }
        return (rows, echoes)
    }

    // MARK: - Change / layout helpers

    private static func contentEqual(_ lhs: TimelineRow, _ rhs: TimelineRow) -> Bool {
        lhs.id == rhs.id
            && lhs.role == rhs.role
            && lhs.isPending == rhs.isPending
            && lhs.isStreaming == rhs.isStreaming
            && lhs.text == rhs.text
            && lhs.blocks == rhs.blocks
            && lhs.renderedMarkdown?.sourceHash == rhs.renderedMarkdown?.sourceHash
            && lhs.renderedMarkdown?.renderTreeVersion == rhs.renderedMarkdown?.renderTreeVersion
            && lhs.renderedMarkdown?.blocks.count == rhs.renderedMarkdown?.blocks.count
            && lhs.contentRevision == rhs.contentRevision
            && lhs.metadataLabel == rhs.metadataLabel
            && lhs.modelProviderID == rhs.modelProviderID
            && lhs.modelID == rhs.modelID
            && lhs.contextInputTokens == rhs.contextInputTokens
            && lhs.steer == rhs.steer
    }

    /// Height-affecting fingerprint for layoutRevision advances.
    /// Text length is content-only while streaming; GrowBottom declares height growth.
    private static func layoutFingerprint(_ row: TimelineRow) -> String {
        let markdownPresent = row.renderedMarkdown != nil
            || row.blocks.contains { $0.textContent?.renderedMarkdown != nil }
        let blockKey = row.blocks.map { block -> String in
            switch block {
            case .text(let id, _, let doc):
                "t:\(id):\(doc != nil)"
            case .attachment(let id, let file):
                "a:\(id):\(file.id):\(file.previewData != nil)"
            case .toolCard(let id, let card):
                "c:\(id):\(card.errorText != nil):\(card.additions ?? -1):\(card.deletions ?? -1)"
            case .toolGroup(let id, let group):
                "g:\(id):\(group.count):\(group.errorText != nil)"
            case .childSessionRow(let id, let row):
                "r:\(id):\(row.childSessionId != nil)"
            case .reasoning(let id, _, _, _):
                "n:\(id)"
            case .stepParagraph(let id, let paragraph):
                "s:\(id):\(paragraph.text != nil):\(paragraph.renderedMarkdown != nil):\(paragraph.errorLines.count):"
                    + paragraph.chips.map {
                        "\($0.status.rawValue)/\($0.additions ?? -1)/\($0.deletions ?? -1)"
                    }.joined(separator: ",")
            case .stepRail(let id, let rail):
                "sr:\(id):"
                    + rail.steps.map {
                        "\($0.id)/\($0.status.rawValue)/\($0.isBranch)/\($0.detail != nil)/\($0.additions ?? -1)/\($0.deletions ?? -1)"
                    }.joined(separator: ",")
            case .activityCard(let id, let card):
                "ac:\(id):"
                    + card.steps.map {
                        "\($0.id)/\($0.status.rawValue)/\($0.additions ?? -1)/\($0.deletions ?? -1)"
                    }.joined(separator: ",")
            case .stopNote(let id, let reason):
                "x:\(id):\(reason.rawValue)"
            case .failureLine(let id, _, let body, let neutral, let action, _, _, let retryAt):
                "f:\(id):\(body != nil):\(neutral):\(action != nil):\(retryAt != nil)"
            case .notice(let id, let notice):
                "o:\(id):\(String(describing: notice.kind).hashValue)"
            }
        }.joined(separator: "|")
        return "\(row.isStreaming):\(markdownPresent):\(row.blocks.count):\(row.metadataLabel.isEmpty ? "0" : "1"):\(row.pendingDelivery == nil ? "0" : "1"):\(steerLayoutKey(row.steer)):\(blockKey)"
    }

    /// The label and the pending caption each add height.
    private static func steerLayoutKey(_ steer: SessionChatSteer?) -> String {
        guard let steer else { return "-" }
        return "\(steer.isPending ? "p" : "d")\(steer.showsLabel ? "l" : "")"
    }

    /// Layout bump caused only by markdown presence/sourceHash while streaming flag and
    /// non-markdown structure stay the same. Streaming end is Structure, not Decorate.
    static func isMarkdownOnlyLayoutChange(prior: TimelineRow, row: TimelineRow) -> Bool {
        guard prior.isStreaming == row.isStreaming else { return false }
        guard structureFingerprint(prior) == structureFingerprint(row) else { return false }
        return markdownFingerprint(prior) != markdownFingerprint(row)
    }

    private static func structureFingerprint(_ row: TimelineRow) -> String {
        let attachments = row.blocks.compactMap { block -> String? in
            if case .attachment(let id, let file) = block { return "a:\(id):\(file.id)" }
            return nil
        }.joined(separator: "|")
        let blocks = row.blocks.map { block -> String in
            switch block {
            case .text(let id, _, _):
                "t:\(id)"
            case .attachment(let id, let file):
                "a:\(id):\(file.id)"
            case .toolCard(let id, _):
                "c:\(id)"
            case .toolGroup(let id, let group):
                "g:\(id):\(group.count)"
            case .childSessionRow(let id, let row):
                "r:\(id):\(row.childSessionId != nil)"
            case .reasoning(let id, _, _, _):
                "n:\(id)"
            case .stepParagraph(let id, let paragraph):
                "s:\(id):\(paragraph.chips.count)"
            case .stepRail(let id, let rail):
                "sr:\(id):\(rail.steps.count)"
            case .activityCard(let id, let card):
                "ac:\(id):\(card.steps.count)"
            case .stopNote(let id, let reason):
                "x:\(id):\(reason.rawValue)"
            case .failureLine(let id, _, let body, _, _, _, _, _):
                "f:\(id):\(body != nil)"
            case .notice(let id, _):
                "o:\(id)"
            }
        }.joined(separator: "|")
        return "\(row.blocks.count)|\(attachments)|\(blocks)"
    }

    private static func markdownFingerprint(_ row: TimelineRow) -> String {
        row.blocks.compactMap { block -> String? in
            guard let content = block.textContent else { return nil }
            let doc = content.renderedMarkdown
            return "\(block.blockID):\(doc != nil):\(doc?.sourceHash ?? 0):\(doc?.renderTreeVersion ?? 0)"
        }.joined(separator: "|")
    }

    private static func provisional(
        id: String,
        role: MessageRole,
        text: String,
        metadataLabel: String,
        pendingDelivery: FollowUpDelivery?,
        isStreaming: Bool,
        renderedMarkdown: NativeMarkdownDocument?,
        blocks: [SessionChatTimelineBlock],
        contentRevision: ContentRevision,
        layoutRevision: UInt64,
        steer: SessionChatSteer?
    ) -> TimelineRow {
        TimelineRow(
            id: id,
            role: role,
            text: text,
            metadataLabel: metadataLabel,
            pendingDelivery: pendingDelivery,
            isPending: false,
            isStreaming: isStreaming,
            renderedMarkdown: renderedMarkdown,
            blocks: blocks,
            steer: steer,
            contentRevision: contentRevision,
            layoutRevision: layoutRevision
        )
    }
}
