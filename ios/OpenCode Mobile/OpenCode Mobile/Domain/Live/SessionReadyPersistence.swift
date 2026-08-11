import Foundation

nonisolated struct SessionReadyPersistedEntry: Codable, Equatable, Sendable {
    var sessionID: String
    var directory: String
    var atEpochMs: Double
}

nonisolated struct SessionReadyPersistedState: Codable, Equatable, Sendable {
    var ready: [SessionReadyPersistedEntry] = []
    var watched: [SessionReadyPersistedEntry] = []
}

nonisolated protocol SessionReadyPersistence: AnyObject, Sendable {
    func load() -> SessionReadyPersistedState
    func save(_ state: SessionReadyPersistedState)
}

nonisolated final class InMemorySessionReadyPersistence: SessionReadyPersistence, @unchecked Sendable {
    private var state = SessionReadyPersistedState()

    func load() -> SessionReadyPersistedState { state }

    func save(_ state: SessionReadyPersistedState) {
        self.state = SessionReadyPersistedState(ready: state.ready, watched: state.watched)
    }
}

nonisolated final class UserDefaultsSessionReadyPersistence: SessionReadyPersistence, @unchecked Sendable {
    private let defaults: UserDefaults
    private let stateKey = "session_ready.state"
    private let legacyReadyKey = "session_ready.entries"

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func load() -> SessionReadyPersistedState {
        if let data = defaults.data(forKey: stateKey),
           let state = try? JSONDecoder().decode(SessionReadyPersistedState.self, from: data)
        {
            return state
        }
        // Legacy: ready-only array with readyAtEpochMs.
        if let data = defaults.data(forKey: legacyReadyKey),
           let legacy = try? JSONDecoder().decode([LegacyReadyEntry].self, from: data)
        {
            return SessionReadyPersistedState(
                ready: legacy.map {
                    SessionReadyPersistedEntry(
                        sessionID: $0.sessionID,
                        directory: $0.directory,
                        atEpochMs: $0.readyAtEpochMs
                    )
                }
            )
        }
        return SessionReadyPersistedState()
    }

    func save(_ state: SessionReadyPersistedState) {
        guard let data = try? JSONEncoder().encode(state) else { return }
        defaults.set(data, forKey: stateKey)
        defaults.removeObject(forKey: legacyReadyKey)
    }

    private struct LegacyReadyEntry: Codable {
        var sessionID: String
        var directory: String
        var readyAtEpochMs: Double
    }
}

nonisolated protocol SessionReadyClock: Sendable {
    func nowEpochMs() -> Double
}

nonisolated struct SystemSessionReadyClock: SessionReadyClock {
    nonisolated func nowEpochMs() -> Double {
        Date().timeIntervalSince1970 * 1000
    }
}

final class MutableSessionReadyClock: SessionReadyClock, @unchecked Sendable {
    var nowEpochMsValue: Double

    init(nowEpochMsValue: Double) {
        self.nowEpochMsValue = nowEpochMsValue
    }

    func nowEpochMs() -> Double { nowEpochMsValue }
}
