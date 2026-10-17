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
            uniqueKeysWithValues: (previous?.rows ?? []).map { ($0.id, $0) }
        )

        let serverRows = serverRows(from: frame, previousByID: previousByID, metadataLabel: resolveLabel)

        let merged: (rows: [TimelineRow], echoes: [String: String]) =
            if optimistic.mergePending {
                mergeWithPending(
                    serverRows,
                    pendingRows: optimistic.pendingRows,
                    baselineSignatureCounts: optimistic.baselineSignatureCounts
                )
            } else {
                (serverRows, [:])
            }
        let rows = merged.rows.filter { !isHiddenPendingPrompt($0) }

        let previousIDs = Set(previousByID.keys)
        let nextIDs = Set(rows.map(\.id))
        // Alias only an echo that replaces its optimistic row in this frame. A server
        // row the host already shows under its own id keeps that identity.
        var listIDAliases = (previous?.listIDAliases ?? [:]).filter { nextIDs.contains($0.key) }
        for (serverID, pendingID) in merged.echoes
        where previousIDs.contains(pendingID) && !previousIDs.contains(serverID) && nextIDs.contains(serverID) {
            listIDAliases[serverID] = pendingID
        }
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
            uniqueKeysWithValues: (previous?.rows ?? []).map { ($0.id, $0) }
        )
        return serverRows(from: frame, previousByID: previousByID, metadataLabel: resolveLabel)
    }

    static func signatureCounts(
        fromDisplayMessages messages: [DisplayMessage],
        sessionKey: String,
        serverCommands: [ServerCommand] = [],
        collapseConsecutiveToolCalls: Bool = false,
        chipTimeline: Bool = false
    ) -> [String: Int] {
        let frame = DisplayFrameBuilder.build(
            sessionKey: sessionKey,
            frameRevision: 0,
            sourceRevision: 0,
            displayMessages: messages,
            serverCommands: serverCommands,
            collapseConsecutiveToolCalls: collapseConsecutiveToolCalls,
            chipTimeline: chipTimeline
        )
        return signatureCounts(serverRows(from: frame))
    }

    static func isHiddenPendingPrompt(_ row: SessionChatRow) -> Bool {
        row.role == .user && row.pendingDelivery != nil
    }

    static func displayedOptimisticSendRows(_ rows: [SessionChatRow]) -> [SessionChatRow] {
        rows.filter { !isHiddenPendingPrompt($0) }
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
                        layoutRevision: previous.layoutRevision
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

    /// Pending-prompt echoes stay in `mergeWithPending` so they can drop a matching
    /// optimistic row, then leave the displayed set. After merge, hide any user
    /// row whose `pendingDelivery` is set (steer or queue), including optimistic
    /// `isPending` rows — the strip is the only surface for inbox items.
    static func isHiddenPendingPrompt(_ row: TimelineRow) -> Bool {
        row.role == .user && row.pendingDelivery != nil
    }

    /// Merged rows plus, for each dropped optimistic row matched to its server
    /// echo, `serverID → pendingID`.
    private static func mergeWithPending(
        _ serverRows: [TimelineRow],
        pendingRows: [TimelineRow],
        baselineSignatureCounts: [String: Int]
    ) -> (rows: [TimelineRow], echoes: [String: String]) {
        guard !pendingRows.isEmpty else { return (serverRows, [:]) }
        let pendingToKeep = unmatchedPending(
            pendingRows,
            serverRows: serverRows,
            baselineSignatureCounts: baselineSignatureCounts
        )
        let keptIDs = Set(pendingToKeep.map(\.id))
        let dropped = pendingRows.filter { !keptIDs.contains($0.id) }
        let chromed = applyPendingChrome(serverRows, dropped: dropped)
        return (splicePending(chromed.rows, pendingToKeep: pendingToKeep), chromed.echoes)
    }

    /// Pending optimistic rows that no new server echo accounts for yet. A row is
    /// matched by exact signature when the server count exceeds the send-time
    /// baseline; attachment rows fall back to a text-only match. Shared with
    /// `SessionChatTimeline` so reconciliation and merge agree on "acknowledged".
    ///
    /// A shown optimistic row is not retired by a waiting steer echo. v2 enqueues every
    /// prompt, idle sends included, as a steer delivered at the next step boundary;
    /// retiring on that hidden copy left a frame with neither bubble. A queued echo
    /// still retires it, since the pending strip owns a real queue.
    static func unmatchedPending(
        _ pendingRows: [TimelineRow],
        serverRows: [TimelineRow],
        baselineSignatureCounts: [String: Int]
    ) -> [TimelineRow] {
        let shownPending = pendingRows.filter { !isHiddenPendingPrompt($0) }
        guard !shownPending.isEmpty else {
            return unmatched(pendingRows, serverRows: serverRows, baselineSignatureCounts: baselineSignatureCounts)
        }
        let keptIDs = Set(
            unmatched(
                shownPending,
                serverRows: serverRows.filter { $0.pendingDelivery != .steer },
                baselineSignatureCounts: baselineSignatureCounts
            ).map(\.id)
        ).union(
            unmatched(
                pendingRows.filter(isHiddenPendingPrompt),
                serverRows: serverRows,
                baselineSignatureCounts: baselineSignatureCounts
            ).map(\.id)
        )
        return pendingRows.filter { keptIDs.contains($0.id) }
    }

    private static func unmatched(
        _ pendingRows: [TimelineRow],
        serverRows: [TimelineRow],
        baselineSignatureCounts: [String: Int]
    ) -> [TimelineRow] {
        var serverExact = signatureCounts(serverRows)
        var serverTextOnly = textOnlySignatureCounts(serverRows)
        let baselineTextOnly = textOnlySignatureCounts(fromExact: baselineSignatureCounts)
        return pendingRows.filter { pending in
            let exact = optimisticSignature(pending)
            if serverExact[exact, default: 0] > baselineSignatureCounts[exact, default: 0] {
                serverExact[exact, default: 0] -= 1
                return false
            }
            let text = textSignature(pending)
            if pending.hasAttachments,
               !text.isEmpty,
               serverTextOnly[text, default: 0] > baselineTextOnly[text, default: 0]
            {
                serverTextOnly[text, default: 0] -= 1
                return false
            }
            if pending.hasAttachments,
               text.isEmpty,
               consumeNewAttachmentEcho(
                   pending,
                   serverRows: serverRows,
                   serverExact: &serverExact,
                   baselineSignatureCounts: baselineSignatureCounts
               )
            {
                return false
            }
            return true
        }
    }

    /// Blank-text attachment send: match a new blank-text server row by file
    /// identity (count + id/filename). A captioned reuse of the same files is
    /// not an echo.
    private static func consumeNewAttachmentEcho(
        _ pending: TimelineRow,
        serverRows: [TimelineRow],
        serverExact: inout [String: Int],
        baselineSignatureCounts: [String: Int]
    ) -> Bool {
        for server in serverRows where server.role == .user {
            guard textSignature(server).isEmpty, attachmentsCorrespond(server, pending) else { continue }
            let exact = optimisticSignature(server)
            guard serverExact[exact, default: 0] > baselineSignatureCounts[exact, default: 0] else {
                continue
            }
            serverExact[exact, default: 0] -= 1
            return true
        }
        return false
    }

    private static func attachments(of row: TimelineRow) -> [FilePart] {
        row.blocks.compactMap { block in
            if case .attachment(_, let file) = block { return file }
            return nil
        }
    }

    private static func attachmentsMatch(_ left: FilePart, _ right: FilePart) -> Bool {
        if left.id == right.id { return true }
        let leftName = left.filename.trimmingCharacters(in: .whitespacesAndNewlines)
        let rightName = right.filename.trimmingCharacters(in: .whitespacesAndNewlines)
        if !leftName.isEmpty, leftName == rightName { return true }
        return leftName.isEmpty || rightName.isEmpty
    }

    static func attachmentsCorrespond(_ left: TimelineRow, _ right: TimelineRow) -> Bool {
        let leftFiles = attachments(of: left)
        let rightFiles = attachments(of: right)
        guard leftFiles.count == rightFiles.count, !leftFiles.isEmpty else { return false }
        return zip(leftFiles, rightFiles).allSatisfy { attachmentsMatch($0, $1) }
    }

    private static func splicePending(
        _ serverRows: [TimelineRow],
        pendingToKeep: [TimelineRow]
    ) -> [TimelineRow] {
        guard !pendingToKeep.isEmpty else { return serverRows }
        if let index = serverRows.firstIndex(where: { $0.role == .assistant && $0.isStreaming }) {
            let precededByUser = index > 0 && serverRows[index - 1].role == .user
            if !precededByUser {
                return Array(serverRows[..<index]) + pendingToKeep + Array(serverRows[index...])
            }
        }
        return serverRows + pendingToKeep
    }

    private static func applyPendingChrome(
        _ serverRows: [TimelineRow],
        dropped: [TimelineRow]
    ) -> (rows: [TimelineRow], echoes: [String: String]) {
        guard !dropped.isEmpty else { return (serverRows, [:]) }
        var remaining = dropped
        var pendingByServerID: [String: TimelineRow] = [:]
        for row in serverRows where row.role == .user {
            let exact = optimisticSignature(row)
            guard let index = remaining.firstIndex(where: { optimisticSignature($0) == exact }) else {
                continue
            }
            pendingByServerID[row.id] = remaining.remove(at: index)
        }
        for pending in remaining where pending.hasAttachments {
            let text = textSignature(pending)
            if !text.isEmpty {
                guard let target = serverRows.last(where: { server in
                    server.role == .user
                        && !server.hasAttachments
                        && pendingByServerID[server.id] == nil
                        && textSignature(server) == text
                }) else { continue }
                pendingByServerID[target.id] = pending
                continue
            }
            guard let target = serverRows.last(where: { server in
                server.role == .user
                    && pendingByServerID[server.id] == nil
                    && textSignature(server).isEmpty
                    && attachmentsCorrespond(server, pending)
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

    static func signatureCounts(_ rows: [TimelineRow]) -> [String: Int] {
        rows.reduce(into: [:]) { counts, row in
            guard row.role == .user else { return }
            counts[optimisticSignature(row), default: 0] += 1
        }
    }

    static func optimisticSignature(_ row: TimelineRow) -> String {
        let content = row.blocks.compactMap { block -> String? in
            switch block {
            case .text(_, let text, _):
                return "text:\(text)"
            case .attachment(_, let file):
                // Kind, not mime: a server may re-encode an image (v2 stores a large PNG as JPEG).
                let name = file.filename.trimmingCharacters(in: .whitespacesAndNewlines)
                return name.isEmpty ? "file:\(file.kind.rawValue)" : "file:\(name):\(file.kind.rawValue)"
            case .toolCard, .toolGroup, .childSessionRow, .reasoning, .stepParagraph, .stopNote, .failureLine, .notice:
                return nil
            }
        }
        return content.isEmpty ? "text:\(row.text)" : content.joined(separator: "|")
    }

    static func textSignature(_ row: TimelineRow) -> String {
        let texts = row.blocks.compactMap { block -> String? in
            if case .text(_, let text, _) = block { return "text:\(text)" }
            return nil
        }
        if !texts.isEmpty { return texts.joined(separator: "|") }
        let trimmed = row.text.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? "" : "text:\(trimmed)"
    }

    static func textOnlySignatureCounts(_ rows: [TimelineRow]) -> [String: Int] {
        rows.reduce(into: [:]) { counts, row in
            guard row.role == .user, !row.hasAttachments else { return }
            let text = textSignature(row)
            guard !text.isEmpty else { return }
            counts[text, default: 0] += 1
        }
    }

    static func textOnlySignatureCounts(fromExact counts: [String: Int]) -> [String: Int] {
        counts.reduce(into: [:]) { aggregated, pair in
            guard !pair.key.split(separator: "|").contains(where: { $0.hasPrefix("file:") }) else {
                return
            }
            let text = textKey(fromExact: pair.key)
            guard !text.isEmpty else { return }
            aggregated[text, default: 0] += pair.value
        }
    }

    static func textKey(fromExact exact: String) -> String {
        let parts = exact.split(separator: "|", omittingEmptySubsequences: false)
            .map(String.init)
            .filter { $0.hasPrefix("text:") }
        return parts.joined(separator: "|")
    }

    static func signatureCounts(fromSessionChatRows rows: [SessionChatRow]) -> [String: Int] {
        signatureCounts(rows.map { TimelineRow.fromSessionChatRow($0) })
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
            case .stopNote(let id, let reason):
                "x:\(id):\(reason.rawValue)"
            case .failureLine(let id, _, let body, let neutral, let action, _, _):
                "f:\(id):\(body != nil):\(neutral):\(action != nil)"
            case .notice(let id, let notice):
                "o:\(id):\(String(describing: notice.kind).hashValue)"
            }
        }.joined(separator: "|")
        return "\(row.isStreaming):\(markdownPresent):\(row.blocks.count):\(row.metadataLabel.isEmpty ? "0" : "1"):\(row.pendingDelivery == nil ? "0" : "1"):\(blockKey)"
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
            case .stopNote(let id, let reason):
                "x:\(id):\(reason.rawValue)"
            case .failureLine(let id, _, let body, _, _, _, _):
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
        layoutRevision: UInt64
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
            contentRevision: contentRevision,
            layoutRevision: layoutRevision
        )
    }
}
