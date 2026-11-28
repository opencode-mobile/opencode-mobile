import Foundation

/// The `/agent` and `/model` posts a v2 send still needs before its prompt or command.
struct V2SelectionSwitches: Sendable {
    let agent: String?
    let model: V2SwitchModelRequestDTO?

    /// Decides which of the composer's picks a v2 send has to apply. The server appends an
    /// `agent-switched` message for every `/agent` post, even an unchanged one, so only a pick that
    /// differs from the session's current agent is posted. The model gets the same check (the
    /// server already ignores an unchanged one, default variant included); this saves the request.
    /// With no `current` session record every pick is posted, as before.
    static func resolve(
        current: V2SessionSelectionDTO?,
        agent: String?,
        model: V2SwitchModelRequestDTO?
    ) -> V2SelectionSwitches {
        var agentSwitch: String?
        if let agent, !agent.isEmpty, current == nil || current?.agent != agent {
            agentSwitch = agent
        }
        var modelSwitch: V2SwitchModelRequestDTO?
        if let model, current == nil || !sameModel(current?.model, model.model) {
            modelSwitch = model
        }
        return V2SelectionSwitches(agent: agentSwitch, model: modelSwitch)
    }

    private static func sameModel(_ current: V2PromptModelRefDTO?, _ picked: V2PromptModelRefDTO) -> Bool {
        guard let current else { return false }
        return current.providerID == picked.providerID
            && current.id == picked.id
            && (current.variant ?? defaultVariant) == (picked.variant ?? defaultVariant)
    }

    private static let defaultVariant = "default"
}

/// Applies a v2 send's agent and model before its prompt or command, skipping what the session
/// already uses. The session is read fresh each time, so a switch another client made since is
/// respected.
struct V2SessionSelectionApplier: Sendable {
    let api: any MessagesAPI

    /// A failed switch throws, so the send fails instead of running with the old agent or model.
    func apply(sessionID: String, agent: String?, model: V2SwitchModelRequestDTO?, directory: String?) async throws {
        if (agent ?? "").isEmpty, model == nil { return }
        let switches = V2SelectionSwitches.resolve(
            current: try await currentSelection(sessionID: sessionID),
            agent: agent,
            model: model
        )
        if let agent = switches.agent {
            try await api.switchSessionAgent(sessionID: sessionID, agent: agent, directory: directory)
        }
        if let model = switches.model {
            try await api.switchSessionModel(sessionID: sessionID, body: model, directory: directory)
        }
    }

    /// Nil when the read fails: the picks are then all posted, which is safe, only noisier.
    /// Cancellation still stops the send.
    private func currentSelection(sessionID: String) async throws -> V2SessionSelectionDTO? {
        do {
            return try await api.getSessionSelectionV2(sessionID: sessionID)
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            try Task.checkCancellation()
            DiagnosticLog.warning(.chat, "session read before send failed session=\(sessionID) error=\(error.localizedDescription)")
            return nil
        }
    }
}
