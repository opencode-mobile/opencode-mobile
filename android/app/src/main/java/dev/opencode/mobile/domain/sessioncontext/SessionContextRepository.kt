package dev.opencode.mobile.domain.sessioncontext

import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessageRepository
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.MessageTokenUsage
import dev.opencode.mobile.domain.model.ModelCatalogStore
import dev.opencode.mobile.domain.model.ModelSelectionCatalog
import dev.opencode.mobile.domain.session.Session
import dev.opencode.mobile.domain.session.SessionRepository
import javax.inject.Inject
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

interface SessionContextRepository {
    suspend fun loadSessionContext(
        sessionId: String,
        directory: String? = null,
    ): SessionContextSnapshot
}

class DefaultSessionContextRepository
    @Inject
    constructor(
        private val sessionRepository: SessionRepository,
        private val messageRepository: MessageRepository,
        private val modelCatalogStore: ModelCatalogStore,
        private val calculator: SessionContextCalculator,
    ) : SessionContextRepository {
        override suspend fun loadSessionContext(
            sessionId: String,
            directory: String?,
        ): SessionContextSnapshot =
            runCatching {
                calculator.snapshot(
                    session = sessionRepository.getSession(sessionId, directory),
                    messages = messageRepository.listMessages(sessionId, directory),
                    modelCatalog = modelCatalogStore.catalog(directory),
                )
            }.getOrElse {
                throw SessionContextError.Underlying(it.message ?: "Unable to load session context")
            }
    }

class SessionContextCalculator
    @Inject
    constructor() {
        fun snapshot(
            session: Session,
            messages: List<Message>,
            modelCatalog: ModelSelectionCatalog,
        ): SessionContextSnapshot {
            val latestAssistant = messages.asReversed().firstOrNull { it.role == MessageRole.Assistant && (it.tokens?.total ?: 0) > 0 }
            val modelProviderId = latestAssistant?.modelProviderId
            val modelId = latestAssistant?.modelId
            val option =
                if (modelProviderId != null && modelId != null) {
                    modelCatalog.option(modelProviderId, modelId)
                } else {
                    null
                }
            val tokenStats = latestAssistant?.tokens?.let { stats(it, option?.contextLimitTokens) }
            val counts =
                SessionContextMessageCounts(
                    total = messages.size,
                    user = messages.count { it.role == MessageRole.User },
                    assistant = messages.count { it.role == MessageRole.Assistant },
                )
            val inputTokens = latestAssistant?.tokens?.input ?: 0
            return SessionContextSnapshot(
                sessionId = session.id,
                sessionTitle = session.title,
                providerLabel = option?.providerLabel ?: modelProviderId,
                modelLabel = option?.modelLabel ?: modelId,
                tokenStats = tokenStats,
                messageCounts = counts,
                totalCost = messages.filter { it.role == MessageRole.Assistant }.sumOf { it.cost ?: 0.0 },
                createdAtEpochMs = session.createdAtEpochMs,
                lastActivityEpochMs = latestAssistant?.sentAtEpochMs ?: session.updatedAtEpochMs,
                breakdown = if (inputTokens > 0) breakdown(messages, inputTokens) else emptyList(),
            )
        }

        private fun stats(
            usage: MessageTokenUsage,
            contextLimit: Long?,
        ): SessionContextTokenStats =
            SessionContextTokenStats(
                contextLimit = contextLimit,
                total = usage.total,
                input = usage.input,
                output = usage.output,
                reasoning = usage.reasoning,
                cacheRead = usage.cacheRead,
                cacheWrite = usage.cacheWrite,
                usagePercent = contextLimit?.takeIf { it > 0 }?.let { (usage.total.toDouble() / it.toDouble() * 100).roundToInt() },
            )

        private fun breakdown(
            messages: List<Message>,
            inputTokens: Long,
        ): List<SessionContextBreakdownItem> {
            val characterBuckets =
                mutableMapOf(
                    SessionContextBreakdownKind.System to 0,
                    SessionContextBreakdownKind.User to 0,
                    SessionContextBreakdownKind.Assistant to 0,
                    SessionContextBreakdownKind.Tool to 0,
                )
            messages.forEach { message ->
                characterBuckets[SessionContextBreakdownKind.System] =
                    characterBuckets.getValue(SessionContextBreakdownKind.System) + message.contextEstimate.systemCharacters
                characterBuckets[SessionContextBreakdownKind.User] =
                    characterBuckets.getValue(SessionContextBreakdownKind.User) + message.contextEstimate.userCharacters
                characterBuckets[SessionContextBreakdownKind.Assistant] =
                    characterBuckets.getValue(SessionContextBreakdownKind.Assistant) + message.contextEstimate.assistantCharacters
                characterBuckets[SessionContextBreakdownKind.Tool] =
                    characterBuckets.getValue(SessionContextBreakdownKind.Tool) + message.contextEstimate.toolCharacters
            }

            val tokenBuckets = characterBuckets.mapValues { (_, chars) -> ceil(chars.toDouble() / 4.0).toLong() }.toMutableMap()
            val estimatedTotal = tokenBuckets.values.sum()
            if (estimatedTotal > inputTokens && estimatedTotal > 0) {
                val scale = inputTokens.toDouble() / estimatedTotal.toDouble()
                tokenBuckets.replaceAll { _, tokens -> floor(tokens.toDouble() * scale).toLong() }
            }
            val scaledTotal = tokenBuckets.values.sum()
            tokenBuckets[SessionContextBreakdownKind.Other] = (inputTokens - scaledTotal).coerceAtLeast(0)

            return listOf(
                SessionContextBreakdownKind.System,
                SessionContextBreakdownKind.User,
                SessionContextBreakdownKind.Assistant,
                SessionContextBreakdownKind.Tool,
                SessionContextBreakdownKind.Other,
            ).mapNotNull { kind ->
                val tokens = tokenBuckets[kind]?.takeIf { it > 0 } ?: return@mapNotNull null
                val width = tokens.toDouble() / inputTokens.toDouble() * 100
                SessionContextBreakdownItem(
                    kind = kind,
                    tokens = tokens,
                    widthPercent = width,
                    displayPercent = (width * 10.0).roundToInt() / 10.0,
                )
            }
        }
    }
