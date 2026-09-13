package dev.opencode.mobile.domain.sync

sealed class ProjectionKey {
    abstract val directory: String

    data class SessionList(
        override val directory: String,
    ) : ProjectionKey()

    data class SessionStatuses(
        override val directory: String,
    ) : ProjectionKey()

    data class Todos(
        override val directory: String,
        val sessionId: String,
    ) : ProjectionKey()

    data class Permissions(
        override val directory: String,
    ) : ProjectionKey()

    data class Questions(
        override val directory: String,
    ) : ProjectionKey()

    data class Transcript(
        override val directory: String,
        val sessionId: String,
    ) : ProjectionKey()
}
