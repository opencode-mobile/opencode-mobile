import Foundation
import Testing
@testable import OpenCode_Mobile

struct SessionContextCalculatorTests {
    @Test func snapshot_usesLatestAssistantWithNonzeroTokens() {
        let snapshot = SessionContextCalculator().snapshot(
            session: session(),
            messages: [
                assistant(
                    id: "msg-old",
                    cost: 0.25,
                    tokens: MessageTokenUsage(input: 10, output: 2, reasoning: 1, cacheRead: 0, cacheWrite: 0),
                    sentAtEpochMs: 2_000
                ),
                user(id: "msg-user", text: "Prompt", sentAtEpochMs: 3_000),
                assistant(
                    id: "msg-latest-empty",
                    cost: 0.50,
                    tokens: MessageTokenUsage(input: 0, output: 0, reasoning: 0, cacheRead: 0, cacheWrite: 0),
                    sentAtEpochMs: 4_000
                ),
                assistant(
                    id: "msg-latest",
                    cost: 0.75,
                    tokens: MessageTokenUsage(input: 80, output: 9, reasoning: 3, cacheRead: 7, cacheWrite: 1),
                    sentAtEpochMs: 5_000
                ),
            ],
            modelCatalog: catalog(limit: 200)
        )

        #expect(snapshot.tokenStats?.total == 100)
        #expect(snapshot.tokenStats?.usagePercent == 50)
        #expect(snapshot.messageCounts.total == 4)
        #expect(snapshot.messageCounts.user == 1)
        #expect(snapshot.messageCounts.assistant == 3)
        #expect(snapshot.totalCost == 1.5)
        #expect(snapshot.lastActivityEpochMs == 5_000)
        #expect(snapshot.providerLabel == "Anthropic")
        #expect(snapshot.modelLabel == "Claude Sonnet")
    }

    @Test func snapshot_usagePercentIsNilWhenContextLimitMissing() {
        let snapshot = SessionContextCalculator().snapshot(
            session: session(),
            messages: [
                assistant(tokens: MessageTokenUsage(input: 10, output: 2, reasoning: 1, cacheRead: 1, cacheWrite: 1)),
            ],
            modelCatalog: catalog(limit: nil)
        )

        #expect(snapshot.tokenStats?.total == 15)
        #expect(snapshot.tokenStats?.usagePercent == nil)
    }

    @Test func snapshot_breakdownUsesRemainderForUnderInputEstimate() {
        let snapshot = SessionContextCalculator().snapshot(
            session: session(),
            messages: [
                user(id: "msg-user", text: "abcdefgh", estimate: MessageContextEstimate(userCharacters: 8)),
                assistant(
                    id: "msg-assistant",
                    text: "abcd",
                    tokens: MessageTokenUsage(input: 20, output: 1, reasoning: 0, cacheRead: 0, cacheWrite: 0),
                    estimate: MessageContextEstimate(assistantCharacters: 4)
                ),
            ],
            modelCatalog: catalog(limit: 100)
        )

        #expect(snapshot.breakdown.first { $0.kind == .user }?.tokens == 2)
        #expect(snapshot.breakdown.first { $0.kind == .assistant }?.tokens == 1)
        #expect(snapshot.breakdown.first { $0.kind == .other }?.tokens == 17)
    }

    @Test func snapshot_breakdownScalesOverInputEstimate() {
        let snapshot = SessionContextCalculator().snapshot(
            session: session(),
            messages: [
                user(id: "msg-user", text: "user", estimate: MessageContextEstimate(userCharacters: 40)),
                assistant(
                    id: "msg-assistant",
                    text: "assistant",
                    tokens: MessageTokenUsage(input: 10, output: 1, reasoning: 0, cacheRead: 0, cacheWrite: 0),
                    estimate: MessageContextEstimate(assistantCharacters: 40)
                ),
            ],
            modelCatalog: catalog(limit: 100)
        )

        #expect(snapshot.breakdown.first { $0.kind == .user }?.tokens == 5)
        #expect(snapshot.breakdown.first { $0.kind == .assistant }?.tokens == 5)
        #expect(snapshot.breakdown.first { $0.kind == .other } == nil)
    }

    private func session() -> Session {
        Session(
            id: "ses-1",
            title: "Session",
            slug: nil,
            projectID: nil,
            directory: "/repo",
            parentID: nil,
            createdAtEpochMs: 1_000,
            updatedAtEpochMs: 6_000
        )
    }

    private func user(
        id: String = "msg-user",
        text: String = "Hello",
        sentAtEpochMs: Int64? = 1_000,
        estimate: MessageContextEstimate = MessageContextEstimate(userCharacters: 5)
    ) -> Message {
        Message(id: id, role: .user, text: text, model: nil, mode: nil, sentAtEpochMs: sentAtEpochMs, contextEstimate: estimate)
    }

    private func assistant(
        id: String = "msg-assistant",
        text: String = "Hi",
        cost: Double? = nil,
        tokens: MessageTokenUsage? = nil,
        sentAtEpochMs: Int64? = 2_000,
        estimate: MessageContextEstimate = MessageContextEstimate(assistantCharacters: 2)
    ) -> Message {
        Message(
            id: id,
            role: .assistant,
            text: text,
            cost: cost,
            tokens: tokens,
            model: "claude-sonnet",
            modelProviderID: "anthropic",
            modelID: "claude-sonnet",
            mode: nil,
            sentAtEpochMs: sentAtEpochMs,
            contextEstimate: estimate
        )
    }

    private func catalog(limit: Int64?) -> ModelSelectionCatalog {
        let option = ModelSelectionOption(
            providerID: "anthropic",
            providerName: "Anthropic",
            modelID: "claude-sonnet",
            modelName: "Claude Sonnet",
            variants: [],
            contextLimitTokens: limit
        )
        return ModelSelectionCatalog(options: [option], serverDefault: nil)
    }
}
