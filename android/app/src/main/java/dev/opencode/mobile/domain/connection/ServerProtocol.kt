package dev.opencode.mobile.domain.connection

/**
 * OpenCode HTTP API generation for an active server connection.
 *
 * Detection follows desktop `detectServerProtocol`: prefer `/global/health`
 * (v1), else `/api/health` with numeric `pid` (v2), else transitional
 * `/api/health` with `healthy: true` (v1-shaped).
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
) {
    companion object {
        fun forProtocol(protocol: ServerProtocol): ServerCapabilities =
            when (protocol) {
                ServerProtocol.V1 ->
                    ServerCapabilities(supportsSessionMessageDiff = true)
                ServerProtocol.V2 ->
                    ServerCapabilities(supportsSessionMessageDiff = false)
            }
    }
}
