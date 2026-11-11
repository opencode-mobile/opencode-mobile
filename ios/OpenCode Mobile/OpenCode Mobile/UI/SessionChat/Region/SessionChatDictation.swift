import Foundation

struct SessionChatDictationRegionState: Equatable {
    /// The composer dictation card. Kept off `uiState` so a republish does not drop it.
    var dictation: ComposerDictationUiState?
}

/// The composer dictation card: listening, unavailable phases, model download, partial
/// transcript, level, the delayed Insert commit, and cancellation. It never edits composer text:
/// Insert hands the transcript to `onCommit` and the Screen Model splices it in.
@MainActor
final class SessionChatDictation {
    var onChange: (() -> Void)?
    /// A level change only: the Screen Model updates the card without a full publish, so the
    /// meter stays cheap.
    var onLevelChange: ((ComposerDictationUiState) -> Void)?
    /// Insert's commit, with the transcript to splice at the composer cursor.
    var onCommit: ((String) -> Void)?

    private(set) var state = SessionChatDictationRegionState()

    /// Tests set this to 0 to commit an insert synchronously.
    var finishDelayNs: UInt64 = 350_000_000

    private var insertGeneration: UInt64 = 0
    private var finishTask: Task<Void, Never>?

    func begin(languageLabel: String, onDevice: Bool) {
        discardInsert()
        state.dictation = ComposerDictationUiState(
            phase: .listening,
            committed: "",
            volatile: "",
            level: 0,
            languageLabel: languageLabel,
            onDevice: onDevice,
            startedAt: Date(),
            errorMessage: nil
        )
        notifyChange()
    }

    func beginUnavailable(
        _ phase: ComposerDictationPhase,
        message: String? = nil,
        languageLabel: String = ""
    ) {
        // A recognizer error can arrive after Insert has already been confirmed.
        // Dropping the card here would cancel the splice.
        guard state.dictation?.phase != .finishing else { return }
        discardInsert()
        state.dictation = ComposerDictationUiState(
            phase: phase,
            languageLabel: languageLabel,
            errorMessage: message
        )
        notifyChange()
    }

    /// Applies a model download update only while the card still shows the model.
    func modelDownload(_ phase: ComposerDictationPhase, progress: Double? = nil) {
        guard phase.isModelPhase, var current = state.dictation, current.phase.isModelPhase else { return }
        current.phase = phase
        current.downloadProgress = progress
        state.dictation = current
        notifyChange()
    }

    func partial(committed: String, volatile: String) {
        guard var current = state.dictation, current.phase == .listening else { return }
        current.committed = committed
        current.volatile = volatile
        state.dictation = current
        notifyChange()
    }

    func level(_ level: Double) {
        guard var current = state.dictation else { return }
        let clamped = min(1, max(0, level.isFinite ? level : 0))
        guard clamped != current.level else { return }
        current.level = clamped
        state.dictation = current
        onLevelChange?(current)
    }

    func insert() {
        guard let current = state.dictation else { return }
        let transcript = current.committed + current.volatile
        guard !transcript.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        insertGeneration &+= 1
        let generation = insertGeneration
        var finishing = current
        finishing.phase = .finishing
        state.dictation = finishing
        notifyChange()
        if finishDelayNs == 0 {
            commitInsert(generation: generation)
            return
        }
        let delay = finishDelayNs
        finishTask?.cancel()
        finishTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: delay)
            guard !Task.isCancelled, let self else { return }
            self.commitInsert(generation: generation)
        }
    }

    /// Drops a listening card and any in-flight finishing insert. Does not touch composer text.
    func cancel() {
        let hadCard = state.dictation != nil || finishTask != nil
        discardInsert()
        state.dictation = nil
        guard hadCard else { return }
        notifyChange()
    }

    func discardInsert() {
        insertGeneration &+= 1
        finishTask?.cancel()
        finishTask = nil
    }

    private func commitInsert(generation: UInt64) {
        guard generation == insertGeneration, let current = state.dictation else { return }
        onCommit?(current.committed + current.volatile)
        state.dictation = nil
        notifyChange()
    }

    private func notifyChange() {
        onChange?()
    }
}
