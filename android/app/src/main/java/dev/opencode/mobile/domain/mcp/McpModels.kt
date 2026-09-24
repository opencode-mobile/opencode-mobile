package dev.opencode.mobile.domain.mcp

/** Product status of one configured MCP server on the opencode server. */
enum class McpServerStatus {
    Connected,
    Connecting,
    Disabled,
    Failed,
    NeedsSignIn,
    ;

    val label: String
        get() =
            when (this) {
                Connected -> "Connected"
                Connecting -> "Connecting"
                Disabled -> "Disabled"
                Failed -> "Failed"
                NeedsSignIn -> "Needs sign-in"
            }

    /** True when the user should look at this server before relying on its tools. */
    val needsAttention: Boolean
        get() = this == Failed || this == NeedsSignIn
}

data class McpServer(
    val name: String,
    val status: McpServerStatus,
    val errorMessage: String? = null,
)

/** Aggregate health of the MCP server list, used for the Commands tile badge. */
data class McpServerSummary(
    val total: Int,
    val connected: Int,
    val attention: Int,
    val worst: McpServerStatus?,
) {
    val headline: String
        get() = "$connected of $total connected"

    companion object {
        val Empty = McpServerSummary(total = 0, connected = 0, attention = 0, worst = null)
    }
}

object McpServerCatalog {
    fun summary(servers: List<McpServer>): McpServerSummary {
        if (servers.isEmpty()) return McpServerSummary.Empty
        val statuses = servers.map { it.status }
        val worst =
            when {
                McpServerStatus.Failed in statuses -> McpServerStatus.Failed
                McpServerStatus.NeedsSignIn in statuses -> McpServerStatus.NeedsSignIn
                McpServerStatus.Connecting in statuses -> McpServerStatus.Connecting
                McpServerStatus.Connected in statuses -> McpServerStatus.Connected
                else -> McpServerStatus.Disabled
            }
        return McpServerSummary(
            total = servers.size,
            connected = statuses.count { it == McpServerStatus.Connected },
            attention = statuses.count { it.needsAttention },
            worst = worst,
        )
    }
}

sealed class McpError : Exception() {
    data class Transport(
        override val cause: Throwable,
    ) : McpError()

    data class Http(
        val statusCode: Int,
        override val message: String?,
    ) : McpError()

    data class Decoding(
        override val cause: Throwable,
    ) : McpError()
}
