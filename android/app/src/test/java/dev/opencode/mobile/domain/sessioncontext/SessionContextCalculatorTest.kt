package dev.opencode.mobile.domain.sessioncontext

import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessageContextEstimate
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.MessageTokenUsage
import dev.opencode.mobile.domain.model.ModelSelectionCatalog
import dev.opencode.mobile.domain.model.ModelSelectionOption
import dev.opencode.mobile.domain.session.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionContextCalculatorTest {
    @Test
    fun snapshot_usesLatestAssistantWithNonzeroTokens() {
        val snapshot =
            SessionContextCalculator().snapshot(
                session = session(),
                messages =
                    listOf(
                        assistant(
                            id = "msg-old",
                            cost = 0.25,
                            tokens = MessageTokenUsage(input = 10, output = 2, reasoning = 1, cacheRead = 0, cacheWrite = 0),
                            sentAtEpochMs = 2_000L,
                        ),
                        user(id = "msg-user", text = "Prompt", sentAtEpochMs = 3_000L),
                        assistant(
                            id = "msg-latest-empty",
                            cost = 0.50,
                            tokens = MessageTokenUsage(input = 0, output = 0, reasoning = 0, cacheRead = 0, cacheWrite = 0),
                            sentAtEpochMs = 4_000L,
                        ),
                        assistant(
                            id = "msg-latest",
                            cost = 0.75,
                            tokens = MessageTokenUsage(input = 80, output = 9, reasoning = 3, cacheRead = 7, cacheWrite = 1),
                            sentAtEpochMs = 5_000L,
                        ),
                    ),
                modelCatalog = catalog(limit = 200),
            )

        assertEquals(100L, snapshot.tokenStats?.total)
        assertEquals(50, snapshot.tokenStats?.usagePercent)
        assertEquals(4, snapshot.messageCounts.total)
        assertEquals(1, snapshot.messageCounts.user)
        assertEquals(3, snapshot.messageCounts.assistant)
        assertEquals(1.5, snapshot.totalCost, 0.0001)
        assertEquals(5_000L, snapshot.lastActivityEpochMs)
        assertEquals("Anthropic", snapshot.providerLabel)
        assertEquals("Claude Sonnet", snapshot.modelLabel)
    }

    @Test
    fun snapshot_usagePercentIsNullWhenContextLimitMissing() {
        val snapshot =
            SessionContextCalculator().snapshot(
                session = session(),
                messages =
                    listOf(
                        assistant(tokens = MessageTokenUsage(input = 10, output = 2, reasoning = 1, cacheRead = 1, cacheWrite = 1)),
                    ),
                modelCatalog = catalog(limit = null),
            )

        assertEquals(15L, snapshot.tokenStats?.total)
        assertNull(snapshot.tokenStats?.usagePercent)
    }

    @Test
    fun snapshot_breakdownUsesRemainderForUnderInputEstimate() {
        val snapshot =
            SessionContextCalculator().snapshot(
                session = session(),
                messages =
                    listOf(
                        user(id = "msg-user", text = "abcdefgh", estimate = MessageContextEstimate(userCharacters = 8)),
                        assistant(
                            id = "msg-assistant",
                            text = "abcd",
                            tokens = MessageTokenUsage(input = 20, output = 1, reasoning = 0, cacheRead = 0, cacheWrite = 0),
                            estimate = MessageContextEstimate(assistantCharacters = 4),
                        ),
                    ),
                modelCatalog = catalog(limit = 100),
            )

        assertEquals(2L, snapshot.breakdown.first { it.kind == SessionContextBreakdownKind.User }.tokens)
        assertEquals(1L, snapshot.breakdown.first { it.kind == SessionContextBreakdownKind.Assistant }.tokens)
        assertEquals(17L, snapshot.breakdown.first { it.kind == SessionContextBreakdownKind.Other }.tokens)
    }

    @Test
    fun snapshot_breakdownScalesOverInputEstimate() {
        val snapshot =
            SessionContextCalculator().snapshot(
                session = session(),
                messages =
                    listOf(
                        user(id = "msg-user", text = "user", estimate = MessageContextEstimate(userCharacters = 40)),
                        assistant(
                            id = "msg-assistant",
                            text = "assistant",
                            tokens = MessageTokenUsage(input = 10, output = 1, reasoning = 0, cacheRead = 0, cacheWrite = 0),
                            estimate = MessageContextEstimate(assistantCharacters = 40),
                        ),
                    ),
                modelCatalog = catalog(limit = 100),
            )

        assertEquals(5L, snapshot.breakdown.first { it.kind == SessionContextBreakdownKind.User }.tokens)
        assertEquals(5L, snapshot.breakdown.first { it.kind == SessionContextBreakdownKind.Assistant }.tokens)
        assertNull(snapshot.breakdown.firstOrNull { it.kind == SessionContextBreakdownKind.Other })
    }

    private fun session(): Session =
        Session(
            id = "ses-1",
            title = "Session",
            directory = "/repo",
            modelProviderId = "anthropic",
            modelId = "claude-sonnet",
            createdAtEpochMs = 1_000L,
            updatedAtEpochMs = 6_000L,
        )

    private fun user(
        id: String = "msg-user",
        text: String = "Hello",
        sentAtEpochMs: Long? = 1_000L,
        estimate: MessageContextEstimate = MessageContextEstimate(userCharacters = 5),
    ): Message =
        Message(
            id = id,
            role = MessageRole.User,
            text = text,
            sentAtEpochMs = sentAtEpochMs,
            contextEstimate = estimate,
        )

    private fun assistant(
        id: String = "msg-assistant",
        text: String = "Hi",
        cost: Double? = null,
        tokens: MessageTokenUsage? = null,
        sentAtEpochMs: Long? = 2_000L,
        estimate: MessageContextEstimate = MessageContextEstimate(assistantCharacters = 2),
    ): Message =
        Message(
            id = id,
            role = MessageRole.Assistant,
            text = text,
            cost = cost,
            tokens = tokens,
            model = "claude-sonnet",
            modelProviderId = "anthropic",
            modelId = "claude-sonnet",
            sentAtEpochMs = sentAtEpochMs,
            contextEstimate = estimate,
        )

    private fun catalog(limit: Long?): ModelSelectionCatalog {
        val option =
            ModelSelectionOption(
                providerId = "anthropic",
                providerName = "Anthropic",
                modelId = "claude-sonnet",
                modelName = "Claude Sonnet",
                contextLimitTokens = limit,
            )
        return ModelSelectionCatalog(options = listOf(option), serverDefault = null)
    }
}
