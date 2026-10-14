package dev.opencode.mobile.domain.connection

/**
 * OpenCode HTTP API generation for an active server connection.
 *
 * Detection prefers `/global/health` with `healthy: true` (v1), else
 * `/api/info` or `/api/health` with numeric `pid` (v2; `healthy` may be absent).
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
 * Product capabilities derived from [ServerProtocol]. Screen Models read these
 * flags instead of branching on protocol themselves.
 */
data class ServerCapabilities(
    val supportsSessionMessageDiff: Boolean,
    val supportsSessionArchive: Boolean,
    val supportsFollowUpDelivery: Boolean,
    /** Server tracks per-session read state (`time.idle`/`time.viewed`, `session.viewed`). */
    val supportsSessionViewed: Boolean = false,
) {
    companion object {
        fun forProtocol(protocol: ServerProtocol): ServerCapabilities =
            when (protocol) {
                ServerProtocol.V1 ->
                    ServerCapabilities(
                        supportsSessionMessageDiff = true,
                        supportsSessionArchive = true,
                        supportsFollowUpDelivery = false,
                        supportsSessionViewed = false,
                    )
                ServerProtocol.V2 ->
                    ServerCapabilities(
                        supportsSessionMessageDiff = false,
                        supportsSessionArchive = false,
                        supportsFollowUpDelivery = true,
                        supportsSessionViewed = true,
                    )
            }
    }
}
