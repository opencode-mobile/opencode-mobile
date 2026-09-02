import Foundation

nonisolated enum LiveStoreRefreshOutcome: Sendable {
    case committed
    case discarded
    case failed
}

actor LiveStoreRefreshGate {
    private var revision = 0
    private var tail: Task<Void, Never>?

    func bump() {
        revision += 1
    }

    func currentRevision() -> Int { revision }

    func refresh<Value: Sendable>(
        load: @escaping @Sendable () async -> Value?,
        commit: @escaping @Sendable (Value) async -> Void
    ) async -> LiveStoreRefreshOutcome {
        let predecessor = tail
        let work = Task<LiveStoreRefreshOutcome, Never> {
            await predecessor?.value
            return await self.run(load: load, commit: commit)
        }
        tail = Task { _ = await work.value }
        return await withTaskCancellationHandler {
            await work.value
        } onCancel: {
            work.cancel()
        }
    }

    private func run<Value: Sendable>(
        load: @escaping @Sendable () async -> Value?,
        commit: @escaping @Sendable (Value) async -> Void
    ) async -> LiveStoreRefreshOutcome {
        let expected = revision
        guard let loaded = await load() else { return .failed }
        guard revision == expected else { return .discarded }
        await commit(loaded)
        revision += 1
        return .committed
    }
}
