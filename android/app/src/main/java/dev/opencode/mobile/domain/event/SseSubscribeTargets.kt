package dev.opencode.mobile.domain.event

import dev.opencode.mobile.domain.connection.ServerProtocol

object SseSubscribeTargets {
    data class Target(
        val path: String,
        val directory: String? = null,
    ) {
        val key: String
            get() = directory?.let { "$path:$it" } ?: path
    }

    fun forProtocol(
        protocol: ServerProtocol,
        directories: List<String> = emptyList(),
    ): List<Target> =
        when (protocol) {
            ServerProtocol.V2 -> listOf(Target("api/event"))
            ServerProtocol.V1 ->
                listOf(Target("global/event")) +
                    directories
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .distinct()
                        .map { Target("event", it) }
        }
}
