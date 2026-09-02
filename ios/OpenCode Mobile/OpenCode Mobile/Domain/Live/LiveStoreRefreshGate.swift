import Foundation

/// What a gated refresh did with its REST response.
nonisolated enum LiveStoreRefreshOutcome: Sendable {
    /// The response was applied to the store.
    case committed

    /// The response arrived after another source had already moved the state.
    case discarded

    /// The REST call itself failed; nothing was applied.
    case failed
}

/// Single-flight plus revision gate for one live-store observation.
///
/// Every live store runs three concurrent refresh triggers over the same state
/// (world signals, invalidation, the SSE stream). Without this gate two things go
/// wrong, and both are sticky — no later event corrects them:
///
/// 1. **A REST response resurrects a state an SSE patch already superseded.** The
///    status store awaits `getAllSessionStatuses`; while it is in flight a
///    `session.idle` patch drops the finished session; the REST task then resumes
///    and writes back its pre-idle snapshot, leaving a finished session spinning
///    until pull-to-refresh.
/// 2. **Two REST responses land out of order.** `permission.replied` starts a
///    refresh that returns `[]`, `permission.asked` starts a second that returns
///    `[P2]`, and on a slow radio the empty one lands last — the prompt never
///    appears and the agent stays blocked server-side.
///
/// The counter is the same shape as `SessionMessageEngine.replaceAll(ifRevision:)`:
/// capture the revision before awaiting, apply only if it has not moved. Callers
/// call ``bump()`` from every state mutation that a REST commit could otherwise
/// overwrite.
///
/// Serialisation fixes (2) and is also what makes the gate safe: capture, load and
/// commit all happen inside one link of the chain, so a refresh can never discard
/// itself against the revision its own predecessor bumped — a queued refresh
/// captures the *post* commit revision. Discarding therefore only ever means
/// "another source moved the state while this response was in flight".
///
/// The gate is per-observation, not per-store: two directories must not serialize
/// against each other. This is the Swift twin of Android's `LiveStoreRefreshGate`;
/// the actor plus the task chain stand in for Kotlin's `Mutex`, because actor
/// isolation alone is reentrant across `await` and would not serialize a refresh.
///
/// This gate does not touch invalidation acks. Acks are completed by the
/// collectors' `defer` blocks around the whole refresh, so a discarded response
/// still releases the pull-to-refresh spinner.
actor LiveStoreRefreshGate {
    private var revision = 0
    private var tail: Task<Void, Never>?

    /// Records a state mutation that did not come from a gated refresh — an SSE
    /// patch, or a local mutation that a REST snapshot would undo. An in-flight
    /// refresh that captured an earlier revision will discard its response.
    func bump() {
        revision += 1
    }

    /// Test seam: the current revision.
    func currentRevision() -> Int { revision }

    /// Runs `load` as the only refresh in flight for this observation, then applies
    /// it through `commit` unless another source bumped the revision meanwhile.
    ///
    /// `load` returns `nil` for a failed fetch, which is reported as
    /// ``LiveStoreRefreshOutcome/failed`` and leaves the state untouched.
    func refresh<Value: Sendable>(
        load: @escaping @Sendable () async -> Value?,
        commit: @escaping @Sendable (Value) async -> Void
    ) async -> LiveStoreRefreshOutcome {
        let predecessor = tail
        let work = Task<LiveStoreRefreshOutcome, Never> {
            // Queue behind whatever refresh this observation already has in flight.
            await predecessor?.value
            return await self.run(load: load, commit: commit)
        }
        tail = Task { _ = await work.value }
        return await withTaskCancellationHandler {
            await work.value
        } onCancel: {
            // Store teardown must not be held open by a stalled REST call.
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
        // The commit is itself a mutation. Keeping the counter monotonic across
        // every mutation is what makes "the revision moved" mean "state changed".
        revision += 1
        return .committed
    }
}
