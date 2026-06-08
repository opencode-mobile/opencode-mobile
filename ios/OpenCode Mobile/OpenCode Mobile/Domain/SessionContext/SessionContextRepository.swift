import Foundation

protocol SessionContextRepository: Sendable {
    func loadSessionContext(sessionID: String, directory: String?) async throws -> SessionContextSnapshot
}

struct DefaultSessionContextRepository: SessionContextRepository, Sendable {
    private let sessionRepository: any SessionRepository
    private let messageRepository: any MessageRepository
    private let modelRepository: any ModelRepository
    private let calculator: SessionContextCalculator

    init(
        sessionRepository: any SessionRepository,
        messageRepository: any MessageRepository,
        modelRepository: any ModelRepository,
        calculator: SessionContextCalculator = SessionContextCalculator()
    ) {
        self.sessionRepository = sessionRepository
        self.messageRepository = messageRepository
        self.modelRepository = modelRepository
        self.calculator = calculator
    }

    func loadSessionContext(sessionID: String, directory: String?) async throws -> SessionContextSnapshot {
        do {
            async let sessionLoad = sessionRepository.getSession(id: sessionID, directory: directory)
            async let messagesLoad = messageRepository.listMessages(sessionID: sessionID, directory: directory)
            async let modelsLoad = modelRepository.listModelOptions(directory: directory)
            return try await calculator.snapshot(
                session: sessionLoad,
                messages: messagesLoad,
                modelCatalog: modelsLoad
            )
        } catch {
            throw SessionContextError.underlying((error as? LocalizedError)?.errorDescription ?? error.localizedDescription)
        }
    }
}

struct SessionContextCalculator: Sendable {
    func snapshot(
        session: Session,
        messages: [Message],
        modelCatalog: ModelSelectionCatalog
    ) -> SessionContextSnapshot {
        let latestAssistant = latestAssistantWithTokens(messages)
        let modelProviderID = latestAssistant?.modelProviderID ?? session.modelProviderID
        let modelID = latestAssistant?.modelID ?? session.modelID
        let option = modelProviderID.flatMap { providerID in
            modelID.flatMap { modelCatalog.option(providerID: providerID, modelID: $0) }
        }
        let tokenStats = latestAssistant?.tokens.map { stats(from: $0, contextLimit: option?.contextLimitTokens) }
        let counts = SessionContextMessageCounts(
            total: messages.count,
            user: messages.filter { $0.role == .user }.count,
            assistant: messages.filter { $0.role == .assistant }.count
        )
        let inputTokens = latestAssistant?.tokens?.input ?? 0
        return SessionContextSnapshot(
            sessionID: session.id,
            sessionTitle: session.title,
            providerLabel: option?.providerLabel ?? modelProviderID,
            modelLabel: option?.modelLabel ?? modelID,
            tokenStats: tokenStats,
            messageCounts: counts,
            totalCost: messages.filter { $0.role == .assistant }.reduce(0) { $0 + ($1.cost ?? 0) },
            createdAtEpochMs: session.createdAtEpochMs,
            lastActivityEpochMs: latestAssistant?.sentAtEpochMs ?? session.updatedAtEpochMs,
            breakdown: inputTokens > 0 ? breakdown(messages: messages, inputTokens: inputTokens) : [],
            rawMessages: messages.map { SessionContextRawMessageRow(id: $0.id, role: $0.role, createdAtEpochMs: $0.sentAtEpochMs) }
        )
    }

    private func latestAssistantWithTokens(_ messages: [Message]) -> Message? {
        messages.reversed().first { message in
            message.role == .assistant && (message.tokens?.total ?? 0) > 0
        }
    }

    private func stats(from usage: MessageTokenUsage, contextLimit: Int64?) -> SessionContextTokenStats {
        SessionContextTokenStats(
            contextLimit: contextLimit,
            total: usage.total,
            input: usage.input,
            output: usage.output,
            reasoning: usage.reasoning,
            cacheRead: usage.cacheRead,
            cacheWrite: usage.cacheWrite,
            usagePercent: contextLimit.flatMap { $0 > 0 ? Int(round(Double(usage.total) / Double($0) * 100)) : nil }
        )
    }

    private func breakdown(messages: [Message], inputTokens: Int64) -> [SessionContextBreakdownItem] {
        typealias Kind = SessionContextBreakdownItem.Kind
        let characterBuckets = messages.reduce(into: [SessionContextBreakdownItem.Kind: Int]()) { buckets, message in
            let estimate = message.contextEstimate
            buckets[Kind.system, default: 0] += estimate.systemCharacters
            buckets[Kind.user, default: 0] += estimate.userCharacters
            buckets[Kind.assistant, default: 0] += estimate.assistantCharacters
            buckets[Kind.tool, default: 0] += estimate.toolCharacters
        }

        var tokenBuckets = characterBuckets.mapValues { Int64(ceil(Double($0) / 4.0)) }
        let estimatedTotal = tokenBuckets.values.reduce(0, +)
        if estimatedTotal > inputTokens, estimatedTotal > 0 {
            let scale = Double(inputTokens) / Double(estimatedTotal)
            tokenBuckets = tokenBuckets.mapValues { Int64(floor(Double($0) * scale)) }
        }
        let scaledTotal = tokenBuckets.values.reduce(0, +)
        tokenBuckets[Kind.other] = max(0, inputTokens - scaledTotal)

        return [Kind.system, Kind.user, Kind.assistant, Kind.tool, Kind.other].compactMap { kind in
            guard let tokens = tokenBuckets[kind], tokens > 0 else { return nil }
            let width = Double(tokens) / Double(inputTokens) * 100
            return SessionContextBreakdownItem(
                kind: kind,
                tokens: tokens,
                widthPercent: width,
                displayPercent: (width * 10).rounded() / 10
            )
        }
    }
}
