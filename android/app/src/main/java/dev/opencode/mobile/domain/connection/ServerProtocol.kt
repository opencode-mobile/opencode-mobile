package dev.opencode.mobile.domain.connection

/**
 * OpenCode HTTP API generation for an active server connection.
 *
 * Detection prefers `/global/health` with `healthy: true` (v1), else
 * `/api/info` with numeric `pid` (v2; `healthy` may be absent).
 */
enum class ServerProtocol(
    val wireValue: String,
) {
    V1("v1"),
    V2("v2"),
    ;

    val shortLabel: String get() = wireValue

    val apiCaption: String get() = "API $wireValue"

    val reachableMessage: String get() = "Server reachable · API $wireValue"
}

/**
 * Product capabilities for the active engine. OpenCode still derives them from
 * [ServerProtocol]. T3 has its own preset: the protocol probe reports v1, and
 * that fallback must not turn OpenCode-only controls back on.
 *
 * Screen Models read these flags instead of branching on protocol themselves.
 */
data class ServerCapabilities(
    val supportsSessionMessageDiff: Boolean,
    val supportsSessionArchive: Boolean,
    val supportsFollowUpDelivery: Boolean,
    val supportsTerminal: Boolean = true,
    val supportsRevert: Boolean = true,
    val supportsMentions: Boolean = true,
    val supportsSlashCommands: Boolean = true,
    val supportsDocumentAttachments: Boolean = true,
) {
    companion object {
        val t3: ServerCapabilities =
            ServerCapabilities(
                supportsSessionMessageDiff = false,
                supportsSessionArchive = true,
                supportsFollowUpDelivery = false,
                supportsTerminal = false,
                supportsRevert = false,
                supportsMentions = false,
                supportsSlashCommands = false,
                supportsDocumentAttachments = false,
            )

        fun forProtocol(protocol: ServerProtocol): ServerCapabilities =
            when (protocol) {
                ServerProtocol.V1 ->
                    ServerCapabilities(
                        supportsSessionMessageDiff = true,
                        supportsSessionArchive = true,
                        supportsFollowUpDelivery = false,
                    )
                ServerProtocol.V2 ->
                    ServerCapabilities(
                        supportsSessionMessageDiff = false,
                        supportsSessionArchive = false,
                        supportsFollowUpDelivery = true,
                    )
            }

        fun forEngine(
            engine: ConnectionEngine,
            protocol: ServerProtocol?,
        ): ServerCapabilities =
            if (engine == ConnectionEngine.T3) {
                t3
            } else {
                forProtocol(protocol ?: ServerProtocol.V1)
            }
    }
}
