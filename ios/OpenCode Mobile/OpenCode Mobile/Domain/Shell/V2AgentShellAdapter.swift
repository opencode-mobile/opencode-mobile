import Foundation

/// v2 (`/api/shell`): maps shell infos and `shell.*` events on `/api/event` to product types.
/// Only shells whose `metadata.sessionID` is the open session belong to it.
nonisolated enum V2AgentShellAdapter {
    static let createdEvent = "shell.created"
    static let exitedEvent = "shell.exited"
    static let deletedEvent = "shell.deleted"

    static func shell(from dto: ShellInfoDTO) -> AgentShell? {
        guard let sessionID = string(dto.metadata?["sessionID"]) ?? string(dto.metadata?["sessionId"]) else {
            return nil
        }
        let status = status(wire: dto.status, exit: dto.exit.map { Int($0) })
        return AgentShell(
            id: dto.id,
            sessionID: sessionID,
            command: dto.command ?? "",
            description: nil,
            status: status,
            startedAtMs: dto.time?.started.map { Int64($0) },
            finishedAtMs: status.isRunning ? nil : dto.time?.completed.map { Int64($0) },
            canStop: status.isRunning
        )
    }

    /// `exited` → Exited(exit), `timeout` → TimedOut, `killed` → Stopped; unknown ends read as Failed.
    static func status(wire: String?, exit: Int?) -> AgentShellStatus {
        switch wire {
        case "running": .running
        case "exited": .exited(code: exit)
        case "timeout": .timedOut
        case "killed": .stopped
        default: .failed
        }
    }

    /// The change a `shell.*` event makes; nil for other events.
    static func change(from event: OpencodeEvent) -> AgentShellChange? {
        let properties = event.rawProperties ?? [:]
        switch event.type {
        case createdEvent:
            guard let info = properties["info"]?.dictionaryValue,
                  let dto = shellInfo(from: info),
                  let shell = shell(from: dto)
            else { return nil }
            return .created(shell)
        case exitedEvent:
            guard let id = nonEmpty(properties["id"]?.stringValue) else { return nil }
            let exit = properties["exit"]?.int64Value.map { Int($0) }
            return .ended(
                id: id,
                status: status(wire: properties["status"]?.stringValue ?? "exited", exit: exit),
                atMs: properties["created"]?.int64Value
            )
        case deletedEvent:
            guard let id = nonEmpty(properties["id"]?.stringValue) else { return nil }
            return .removed(id: id)
        default:
            return nil
        }
    }

    private static func shellInfo(from info: [String: AnySendable]) -> ShellInfoDTO? {
        guard let id = nonEmpty(info["id"]?.stringValue) else { return nil }
        let time = info["time"]?.dictionaryValue
        return ShellInfoDTO(
            id: id,
            status: info["status"]?.stringValue ?? "running",
            command: info["command"]?.stringValue,
            cwd: info["cwd"]?.stringValue,
            exit: info["exit"]?.int64Value.map { Double($0) },
            metadata: info["metadata"]?.dictionaryValue?.compactMapValues { value in
                value.stringValue.map { AnyCodable.string($0) }
            },
            time: ShellTimeDTO(
                started: time?["started"]?.int64Value.map { Double($0) },
                completed: time?["completed"]?.int64Value.map { Double($0) }
            )
        )
    }

    private static func string(_ value: AnyCodable?) -> String? {
        guard case let .string(text)? = value else { return nil }
        return nonEmpty(text)
    }

    private static func nonEmpty(_ value: String?) -> String? {
        guard let value, !value.isEmpty else { return nil }
        return value
    }
}

/// The running shells of one session on a v2 server, kept from the list and `shell.*`
/// events. A shell that ends stays (with its end state) until the server removes it,
/// so an open output sheet can show how it ended.
nonisolated struct V2AgentShellList: Equatable, Sendable {
    let sessionID: String
    private(set) var shells: [AgentShell] = []

    init(sessionID: String) {
        self.sessionID = sessionID
    }

    /// A fresh list from `GET /api/shell`: the running shells of this session. Shells
    /// known as running that the list no longer has ended while the app was away;
    /// `missing` returns them so their end state can be looked up.
    mutating func replace(with listed: [AgentShell]) -> [AgentShell] {
        let running = listed.filter { $0.sessionID == sessionID && $0.status.isRunning }
        let listedIDs = Set(running.map(\.id))
        let missing = shells.filter { $0.status.isRunning && !listedIDs.contains($0.id) }
        shells = running.sorted(by: Self.startOrder)
        return missing
    }

    /// Puts back a shell that ended while the app was away, with its end state.
    mutating func restoreEnded(_ shell: AgentShell) {
        guard !shell.status.isRunning, !shells.contains(where: { $0.id == shell.id }) else { return }
        shells.append(shell)
        shells.sort(by: Self.startOrder)
    }

    /// Applies one event; returns whether the list changed.
    @discardableResult
    mutating func apply(_ change: AgentShellChange) -> Bool {
        switch change {
        case .created(let shell):
            guard shell.sessionID == sessionID, !shells.contains(where: { $0.id == shell.id }) else { return false }
            shells.append(shell)
            shells.sort(by: Self.startOrder)
            return true
        case .ended(let id, let status, let atMs):
            guard let index = shells.firstIndex(where: { $0.id == id }) else { return false }
            let ended = shells[index].ended(status, atMs: atMs)
            guard ended != shells[index] else { return false }
            shells[index] = ended
            return true
        case .removed(let id):
            let before = shells.count
            shells.removeAll { $0.id == id }
            return shells.count != before
        }
    }

    private static func startOrder(_ lhs: AgentShell, _ rhs: AgentShell) -> Bool {
        (lhs.startedAtMs ?? .max) < (rhs.startedAtMs ?? .max)
    }
}

/// Reads a v2 shell's output newest first, then follows it: one probe past the end
/// learns the size, reading starts 32 KiB before it (dropping the cut first line), and
/// each poll continues from the last cursor until caught up. Keeps the last 30,000 characters.
nonisolated struct V2AgentShellOutputTail: Equatable, Sendable {
    /// A cursor past any real output: the server answers an empty page with the size.
    static let probeCursor: Int64 = 999_999_999_999
    static let tailBytes: Int64 = 32_768

    private(set) var output = AgentShellOutput.empty
    /// The next byte to read; nil until the probe answered.
    private(set) var cursor: Int64?
    private var dropsPartialLine = false

    /// The cursor the next request reads from.
    var requestCursor: Int64 { cursor ?? Self.probeCursor }

    /// Applies one page; returns whether more output is waiting to be read now.
    mutating func apply(_ page: ShellOutputPageDTO) -> Bool {
        guard let current = cursor else {
            let start = max(0, page.size - Self.tailBytes)
            cursor = start
            if start > 0 {
                dropsPartialLine = true
                output = AgentShellOutput(text: output.text, isTruncated: true)
            }
            return start < page.size
        }
        var chunk = page.output
        if dropsPartialLine, !chunk.isEmpty {
            if let newline = chunk.firstIndex(of: "\n") {
                chunk = String(chunk[chunk.index(after: newline)...])
                dropsPartialLine = false
            } else {
                chunk = ""
            }
        }
        let truncated = output.isTruncated || page.truncated == true
        output = AgentShellOutput.capped(output.text + chunk, truncated: truncated)
        let next = max(current, page.cursor)
        cursor = next
        // An empty page cannot move on; wait for the next poll.
        return next > current && next < page.size
    }
}
