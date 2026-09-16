import Foundation

nonisolated final class SseEventIngress: @unchecked Sendable {
    struct Result: Sendable {
        var parsed: [OpencodeEvent]
        var toEmit: [OpencodeEvent]
        var parseFailed: Bool
        var droppedType: String? = nil
    }

    private let adapter: EventDomainAdapter
    private let deduper = SseEventDeduper()
    private let lock = NSLock()
    private var emittedConnected = false

    init(adapter: EventDomainAdapter) {
        self.adapter = adapter
    }

    func reset() {
        lock.lock()
        emittedConnected = false
        lock.unlock()
        deduper.clear()
    }

    func isServerConnectedFrame(_ dataLine: String) -> Bool {
        adapter.isServerConnectedFrame(dataLine)
    }

    func accept(_ dataLine: String) -> Result {
        if dataLine.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return Result(parsed: [], toEmit: [], parseFailed: false)
        }
        switch adapter.parse(dataLine) {
        case .unrecognized:
            return Result(
                parsed: [],
                toEmit: [OpencodeEvent(type: "sse.parse.failed", sessionID: nil)],
                parseFailed: true
            )
        case .dropped(let type):
            return Result(
                parsed: [],
                toEmit: [],
                parseFailed: false,
                droppedType: type
            )
        case .events(let parsed):
            lock.lock()
            var toEmit: [OpencodeEvent] = []
            for event in parsed {
                switch event.type {
                case "server.connected":
                    if !emittedConnected {
                        emittedConnected = true
                        toEmit.append(event)
                    }
                case "server.heartbeat":
                    break
                default:
                    if deduper.accept(event.id) {
                        toEmit.append(event)
                    }
                }
            }
            lock.unlock()
            return Result(parsed: parsed, toEmit: toEmit, parseFailed: false)
        }
    }
}
