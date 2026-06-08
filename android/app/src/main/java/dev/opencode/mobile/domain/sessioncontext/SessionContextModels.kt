package dev.opencode.mobile.domain.sessioncontext

import dev.opencode.mobile.domain.message.MessageRole

data class SessionContextSnapshot(
    val sessionId: String,
    val sessionTitle: String,
    val providerLabel: String?,
    val modelLabel: String?,
    val tokenStats: SessionContextTokenStats?,
    val messageCounts: SessionContextMessageCounts,
    val totalCost: Double,
    val createdAtEpochMs: Long?,
    val lastActivityEpochMs: Long?,
    val breakdown: List<SessionContextBreakdownItem>,
    val rawMessages: List<SessionContextRawMessageRow>,
)

data class SessionContextTokenStats(
    val contextLimit: Long?,
    val total: Long,
    val input: Long,
    val output: Long,
    val reasoning: Long,
    val cacheRead: Long,
    val cacheWrite: Long,
    val usagePercent: Int?,
)

data class SessionContextMessageCounts(
    val total: Int,
    val user: Int,
    val assistant: Int,
)

data class SessionContextBreakdownItem(
    val kind: SessionContextBreakdownKind,
    val tokens: Long,
    val widthPercent: Double,
    val displayPercent: Double,
)

enum class SessionContextBreakdownKind {
    System,
    User,
    Assistant,
    Tool,
    Other,
}

data class SessionContextRawMessageRow(
    val id: String,
    val role: MessageRole,
    val createdAtEpochMs: Long?,
)

sealed class SessionContextError : Exception() {
    data class Underlying(
        override val message: String,
    ) : SessionContextError()
}
