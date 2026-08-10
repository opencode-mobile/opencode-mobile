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
        return [mode, model, variant, timeLabel].compactMap { $0 }.joined(separator: " · ")
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

        var serverRows: [TimelineRow] = []
        serverRows.reserveCapacity(frame.messages.count)
        for body in frame.messages {
            let built = makeRow(from: body, previous: previousByID[body.id], metadataLabel: resolveLabel)
            serverRows.append(built)
        }

        let rows: [TimelineRow] =
            if optimistic.mergePending {
                mergeWithPending(
                    serverRows,
                    pendingRows: optimistic.pendingRows,
                    baselineSignatureCounts: optimistic.baselineSignatureCounts
                )
            } else {
                serverRows
            }

        let previousIDs = Set(previousByID.keys)
        let nextIDs = Set(rows.map(\.id))
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
            isRequired: false
        )
    }

    // MARK: - Row build + return-previous

    private static func makeRow(
        from body: DisplayMessageBody,
        previous: TimelineRow?,
        metadataLabel: @Sendable (Message) -> String
    ) -> TimelineRow {
        let message = body.message
        // Prefer sole text-block copy so slash-command collapse is what chat shows
        // and copies, not the server-expanded template still on Message.text.
        let displayText: String = {
            let texts = body.blocks.compactMap { block -> String? in
                if case .text(_, let text, _) = block { return text }
                return nil
            }
            return texts.count == 1 ? texts[0] : message.text
        }()
        let contentRevision = ContentRevision.of(displayText)
        let bridgeMarkdown = bridgeRenderedMarkdown(body: body, previous: previous)

        if let previous,
           !previous.isPending,
           previous.text == displayText,
           previous.isStreaming == body.isStreaming,
           previous.role == message.role,
           previous.blocks == body.blocks,
           previous.renderedMarkdown?.sourceHash == bridgeMarkdown?.sourceHash,
           previous.renderedMarkdown?.renderTreeVersion == bridgeMarkdown?.renderTreeVersion,
           previous.renderedMarkdown?.blocks.count == bridgeMarkdown?.blocks.count,
           previous.contentRevision == contentRevision
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
            metadataLabel: metadataLabel(message),
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
            guard case .text(_, _, let markdown) = block else { continue }
            textCount += 1
            if textCount > 1 { return nil }
            found = markdown
        }
        return found
    }

    // MARK: - Optimistic merge (ported from SessionChatTimeline)

    private static func mergeWithPending(
        _ serverRows: [TimelineRow],
        pendingRows: [TimelineRow],
        baselineSignatureCounts: [String: Int]
    ) -> [TimelineRow] {
        guard !pendingRows.isEmpty else { return serverRows }
        var serverCounts = signatureCounts(serverRows)
        let pendingToKeep = pendingRows.filter { pending in
            let signature = optimisticSignature(pending)
            let baselineCount = baselineSignatureCounts[signature, default: 0]
            guard serverCounts[signature, default: 0] > baselineCount else { return true }
            serverCounts[signature, default: 0] -= 1
            return false
        }
        return serverRows + pendingToKeep
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
                "text:\(text)"
            case .attachment(_, let file):
                "file:\(file.id):\(file.mime):\(file.dataURL)"
            case .toolCard, .toolGroup:
                nil
            }
        }
        return content.isEmpty ? "text:\(row.text)" : content.joined(separator: "|")
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
            || row.blocks.contains { block in
                if case .text(_, _, let doc) = block { return doc != nil }
                return false
            }
        let blockKey = row.blocks.map { block -> String in
            switch block {
            case .text(let id, _, let doc):
                "t:\(id):\(doc != nil):\(doc?.sourceHash ?? 0):\(doc?.renderTreeVersion ?? 0)"
            case .attachment(let id, let file):
                "a:\(id):\(file.id)"
            case .toolCard(let id, let card):
                "c:\(id):\(card.toolName):\(card.status.rawValue):\(card.title)"
            case .toolGroup(let id, let group):
                "g:\(id):\(group.count):\(group.status.rawValue):\(group.title)"
            }
        }.joined(separator: "|")
        return "\(row.isStreaming):\(markdownPresent):\(row.blocks.count):\(blockKey)"
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
            case .toolCard(let id, let card):
                "c:\(id):\(card.toolName):\(card.status.rawValue):\(card.title)"
            case .toolGroup(let id, let group):
                "g:\(id):\(group.count):\(group.status.rawValue):\(group.title)"
            }
        }.joined(separator: "|")
        return "\(row.blocks.count)|\(attachments)|\(blocks)"
    }

    private static func markdownFingerprint(_ row: TimelineRow) -> String {
        row.blocks.compactMap { block -> String? in
            guard case .text(let id, _, let doc) = block else { return nil }
            return "\(id):\(doc != nil):\(doc?.sourceHash ?? 0):\(doc?.renderTreeVersion ?? 0)"
        }.joined(separator: "|")
    }

    private static func provisional(
        id: String,
        role: MessageRole,
        text: String,
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
            metadataLabel: "",
            isPending: false,
            isStreaming: isStreaming,
            renderedMarkdown: renderedMarkdown,
            blocks: blocks,
            contentRevision: contentRevision,
            layoutRevision: layoutRevision
        )
    }
}
