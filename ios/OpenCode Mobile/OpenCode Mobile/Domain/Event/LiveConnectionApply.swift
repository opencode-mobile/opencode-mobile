import Foundation

nonisolated enum LiveConnectionApply {
    enum Change: Equatable, Sendable {
        case none
        case directories
        case identity
    }

    enum StartDecision: Equatable, Sendable {
        case ignore
        case stashPending
        case idempotent
        case reconcile(generation: UInt64, config: LiveConnectionConfig)
        case retarget(generation: UInt64, config: LiveConnectionConfig)
    }

    static func change(applied: LiveConnectionConfig, requested: LiveConnectionConfig) -> Change {
        if applied.identity != requested.identity { return .identity }
        if applied.directories != requested.directories { return .directories }
        return .none
    }

    static func startDecision(
        occupancyCurrent: Bool,
        applied: LiveConnectionConfig?,
        requested: LiveConnectionConfig,
        generation: UInt64
    ) -> StartDecision {
        guard occupancyCurrent else { return .ignore }
        guard let applied else { return .stashPending }
        switch change(applied: applied, requested: requested) {
        case .none: return .idempotent
        case .directories: return .reconcile(generation: generation, config: requested)
        case .identity: return .retarget(generation: generation, config: requested)
        }
    }
}
