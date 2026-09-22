package dev.opencode.mobile.domain.mcp

import dev.opencode.mobile.data.remote.McpApi
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.network.ActiveConnectionManager
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultMcpRepository
    @Inject
    constructor(
        private val api: McpApi,
        private val adapter: McpDomainAdapter,
        private val activeConnectionManager: ActiveConnectionManager? = null,
    ) : McpRepository {
        override suspend fun listServers(directory: String?): List<McpServer> =
            try {
                if (activeConnectionManager?.currentProtocolOrProbe() == ServerProtocol.V2) {
                    adapter.toServersFromV2(api.listServersV2(directory))
                } else {
                    adapter.toServers(api.listServers(directory))
                }
            } catch (throwable: Throwable) {
                throw adapter.mapError(throwable)
            }
    }
