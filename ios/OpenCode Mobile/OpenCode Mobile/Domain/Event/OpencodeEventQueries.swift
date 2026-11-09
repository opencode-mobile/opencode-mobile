import Foundation

// Typed questions feature code asks of a live event. The server event names and payload keys
// stay here, so Screen Models never match type strings or read raw properties.

/// Server event names the typed queries below answer for.
nonisolated enum OpencodeEventTypes {
    static let worktreeUpdated = "worktree.updated"
    static let branchUpdated = "vcs.branch.updated"
    static let sessionIdle = "session.idle"
    static let sessionError = "session.error"
    static let sessionUpdated = "session.updated"
    static let sessionDeleted = "session.deleted"
    static let sessionArchived = "session.archived"
    static let sessionUnarchived = "session.unarchived"
    static let sessionRevertStaged = "session.revert.staged"
    static let sessionRevertCleared = "session.revert.cleared"
    static let sessionRevertCommitted = "session.revert.committed"
    static let messagePartDelta = "message.part.delta"
    static let messagePartUpdated = "message.part.updated"
    static let messageUpdated = "message.updated"
    static let modelCatalogUpdates: Set<String> = ["model.updated", "provider.updated"]
    static let streamReconnects: Set<String> = ["server.connected", "stream.reconnected"]
}

/// A session's archive state changed on the server.
nonisolated enum SessionArchiveChange: Sendable, Equatable {
    case archived
    case unarchived
}

nonisolated extension OpencodeEvent {
    /// A project's worktrees changed (`worktree.updated`); `namedProjectID` says which project.
    var isWorktreeUpdated: Bool {
        type == OpencodeEventTypes.worktreeUpdated
    }

    /// The checked-out branch changed (`vcs.branch.updated`).
    var isBranchUpdated: Bool {
        type == OpencodeEventTypes.branchUpdated
    }

    /// A run ended (v1 native `session.idle`; v2 projects `session.execution.*` onto it).
    var isSessionRunEnd: Bool {
        type == OpencodeEventTypes.sessionIdle
    }

    /// A streamed text delta: frequent enough that per-event logging skips it.
    var isMessagePartDelta: Bool {
        type == OpencodeEventTypes.messagePartDelta
    }

    var triggersMessagePartUpdate: Bool {
        type == OpencodeEventTypes.messagePartUpdated
    }

    var triggersMessageSlotUpdate: Bool {
        type == OpencodeEventTypes.messageUpdated
    }

    /// The event stream (re)connected, so state read before it may be stale.
    var isStreamReconnect: Bool {
        OpencodeEventTypes.streamReconnects.contains(type)
    }

    /// The model list should be refetched: the server announced a catalog change (a freshly
    /// started server can answer the first `GET model` before its catalog is ready, then
    /// announce it), or the stream (re)connected, since catalog events sent while it was down
    /// are never replayed.
    var triggersModelCatalogRefresh: Bool {
        OpencodeEventTypes.modelCatalogUpdates.contains(type) || isStreamReconnect
    }

    /// The archive state of `sessionID` changed, or nil for any other event.
    var sessionArchiveChange: SessionArchiveChange? {
        switch type {
        case OpencodeEventTypes.sessionArchived: .archived
        case OpencodeEventTypes.sessionUnarchived: .unarchived
        default: nil
        }
    }

    /// A session left or rejoined the archived list (archived, unarchived or deleted).
    var triggersArchivedListRefresh: Bool {
        sessionArchiveChange != nil || type == OpencodeEventTypes.sessionDeleted
    }

    /// The user's own Stop/Deny (`error.type: "aborted"` / `MessageAbortedError`): the
    /// timeline shows it neutrally, so it is not an error toast.
    var isUserAbortSessionError: Bool {
        guard type == OpencodeEventTypes.sessionError else { return false }
        let error = rawProperties?["error"]?.dictionaryValue
        return ToolInterruption.isUserAbortError(
            type: error?["type"]?.stringValue,
            name: error?["name"]?.stringValue
        )
    }

    /// What a `session.error` says went wrong, or nil for any other event.
    var sessionErrorMessage: String? {
        guard type == OpencodeEventTypes.sessionError else { return nil }
        let error = rawProperties?["error"]?.dictionaryValue
        let data = error?["data"]?.dictionaryValue
        let candidates = [
            data?["message"]?.stringValue,
            error?["message"]?.stringValue,
            rawProperties?["message"]?.stringValue,
            error?["name"]?.stringValue,
            error?["type"]?.stringValue,
        ]
        if let message = candidates.compactMap({ $0 }).first(where: { !$0.isEmpty }) {
            return message
        }
        return "Session work failed"
    }

    /// Title change carried by a `session.updated` event for a concrete session id.
    /// Returns nil when the event is not a usable title update.
    var sessionTitleUpdate: SessionTitleUpdate? {
        guard type == OpencodeEventTypes.sessionUpdated else { return nil }
        let info = rawProperties?["info"]?.dictionaryValue
        let sessionID =
            sessionID
            ?? info?["id"]?.stringValue
            ?? info?["sessionID"]?.stringValue
            ?? info?["sessionId"]?.stringValue
        guard let sessionID else { return nil }
        guard let title = info?["title"]?.stringValue else { return nil }
        return SessionTitleUpdate(sessionID: sessionID, title: title)
    }

    /// Pending-revert change. v1 `session.updated` carries the whole session, so an absent
    /// `info.revert` means none; v2 stages, clears and commits with dedicated events. A partial
    /// `info` (the v2 `session.renamed` projection) says nothing about the revert and is ignored.
    var sessionRevertUpdate: SessionRevertUpdate? {
        switch type {
        case OpencodeEventTypes.sessionUpdated:
            guard let info = rawProperties?["info"]?.dictionaryValue, info["time"] != nil else { return nil }
            guard let sessionID = sessionID ?? info["id"]?.stringValue else { return nil }
            let messageID = info["revert"]?.dictionaryValue?["messageID"]?.stringValue
            return SessionRevertUpdate(sessionID: sessionID, messageID: messageID)
        case OpencodeEventTypes.sessionRevertStaged:
            guard let sessionID,
                  let messageID = rawProperties?["revert"]?.dictionaryValue?["messageID"]?.stringValue
            else { return nil }
            return SessionRevertUpdate(sessionID: sessionID, messageID: messageID)
        case OpencodeEventTypes.sessionRevertCleared, OpencodeEventTypes.sessionRevertCommitted:
            guard let sessionID else { return nil }
            return SessionRevertUpdate(sessionID: sessionID, messageID: nil)
        default:
            return nil
        }
    }

    /// New directory of a session the server moved (v2 `session.moved`, projected onto
    /// `session.updated` with the move's `location`). Nil for any other event, including a v1
    /// `session.updated` that carries the whole session under `info`.
    var sessionMoved: SessionMovedUpdate? {
        guard type == OpencodeEventTypes.sessionUpdated, rawProperties?["info"] == nil else { return nil }
        guard let sessionID, !sessionID.isEmpty else { return nil }
        let directory = rawProperties?["location"]?.dictionaryValue?["directory"]?.stringValue?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard let directory, !directory.isEmpty else { return nil }
        return SessionMovedUpdate(
            sessionID: sessionID,
            directory: directory,
            projectID: namedProjectID
        )
    }

    /// Session removed on the server. v1 carries the id only in `info.id`; v2 sends `sessionID`.
    var sessionDeletedID: String? {
        guard type == OpencodeEventTypes.sessionDeleted else { return nil }
        let info = rawProperties?["info"]?.dictionaryValue
        return sessionID
            ?? info?["id"]?.stringValue
            ?? info?["sessionID"]?.stringValue
            ?? info?["sessionId"]?.stringValue
    }
}
