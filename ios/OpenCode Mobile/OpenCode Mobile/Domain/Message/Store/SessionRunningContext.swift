import Foundation

/// A model reference as the session records it: provider, model and (optional) variant.
nonisolated struct MessageModelContext: Equatable, Sendable {
    let providerID: String?
    let modelID: String
    var variant: String?
}

/// The agent and model a v2 prompt is recorded with. v2 user items carry neither; the REST
/// projection gives each the running agent and model of the timeline items before it
/// (switch items, assistants, users that have their own). Live, the same running value is
/// kept in arrival order so a delivered prompt shows the meta line REST will show.
nonisolated struct SessionRunningContext: Equatable, Sendable {
    var agent: String?
    var model: MessageModelContext?
    /// A live switch not yet followed by a message that carries its own agent or model.
    var switchedAgent: String?
    var switchedModel: MessageModelContext?
    var switchedAtEpochMs: Int64?

    var effectiveAgent: String? { switchedAgent ?? agent }
    var effectiveModel: MessageModelContext? { switchedModel ?? model }

    /// A message that carries its own agent or model supersedes earlier switches of that field.
    func advanced(by message: Message) -> SessionRunningContext {
        if message.mode?.lowercased() == Self.compactionMode { return self }
        let ownModel = message.modelContext
        let ownAgent = message.mode.flatMap { $0.isEmpty ? nil : $0 }
        guard ownAgent != nil || ownModel != nil else { return self }
        var next = self
        if let ownAgent {
            next.agent = ownAgent
            next.switchedAgent = nil
        }
        if let ownModel {
            next.model = ownModel
            next.switchedModel = nil
        }
        return next
    }

    func switched(agent: String?, model: MessageModelContext?, atEpochMs: Int64?) -> SessionRunningContext {
        var next = self
        if let agent { next.switchedAgent = agent }
        if let model { next.switchedModel = model }
        if let atEpochMs { next.switchedAtEpochMs = atEpochMs }
        return next
    }

    /// Re-derive from an authoritative message list. A live switch survives only when it is
    /// newer than everything in the list (REST drops trailing switch items from the projection).
    func reseeded(_ messages: [Message]) -> SessionRunningContext {
        var derived = messages.reduce(SessionRunningContext()) { $0.advanced(by: $1) }
        let newest = messages.map { max($0.sentAtEpochMs ?? 0, $0.completedAtEpochMs ?? 0) }.max() ?? 0
        let hasSwitch = switchedAgent != nil || switchedModel != nil
        guard hasSwitch, switchedAtEpochMs.map({ $0 > newest }) ?? true else { return derived }
        derived.switchedAgent = switchedAgent
        derived.switchedModel = switchedModel
        derived.switchedAtEpochMs = switchedAtEpochMs
        return derived
    }

    /// Fill the agent, model and variant a prompt is missing from the running value.
    func stamp(_ message: Message) -> Message {
        let needsAgent = message.mode == nil
        let needsModel = message.model == nil
        guard needsAgent || needsModel else { return message }
        let model = needsModel ? effectiveModel : nil
        return Message(
            id: message.id,
            role: message.role,
            text: message.text,
            parts: message.parts,
            cost: message.cost,
            tokens: message.tokens,
            model: model?.modelID ?? message.model,
            modelProviderID: model.map(\.providerID) ?? message.modelProviderID,
            modelID: model?.modelID ?? message.modelID,
            variant: model.map(\.variant) ?? message.variant,
            mode: needsAgent ? (effectiveAgent ?? message.mode) : message.mode,
            sentAtEpochMs: message.sentAtEpochMs,
            completedAtEpochMs: message.completedAtEpochMs,
            contextInputTokens: message.contextInputTokens,
            contextEstimate: message.contextEstimate,
            followUpDelivery: message.followUpDelivery,
            isWaitingForPickup: message.isWaitingForPickup,
            stopReason: message.stopReason,
            failure: message.failure
        )
    }

    private static let compactionMode = "compaction"
}

nonisolated extension Message {
    var modelContext: MessageModelContext? {
        guard let id = modelID ?? model else { return nil }
        return MessageModelContext(providerID: modelProviderID, modelID: id, variant: variant)
    }
}

/// Parses the client-only `message.context` event into a store patch.
nonisolated enum MessageContextPatch {
    static func from(_ properties: [String: AnySendable]) -> MessagePatch? {
        let agent = properties["agent"]?.stringValue
        let modelDict = properties["model"]?.dictionaryValue
        let model = modelDict?["id"]?.stringValue.map { id in
            MessageModelContext(
                providerID: modelDict?["providerID"]?.stringValue,
                modelID: id,
                variant: modelDict?["variant"]?.stringValue
            )
        }
        guard agent != nil || model != nil else { return nil }
        return .contextSwitched(agent: agent, model: model, atEpochMs: properties["created"]?.int64Value)
    }
}
