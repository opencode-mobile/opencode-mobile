package dev.opencode.mobile.domain.message

import dev.opencode.mobile.data.remote.MessagesApi
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.domain.project.ProjectRepository
import dev.opencode.mobile.network.ActiveConnectionManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessageProtocolRouter
    @Inject
    constructor(
        private val api: MessagesApi,
        private val adapter: MessageDomainAdapter,
        private val activeConnectionManager: ActiveConnectionManager? = null,
        private val projectRepository: ProjectRepository? = null,
    ) {
        private val protocolMutex = Mutex()

        suspend fun listMessages(
            sessionId: String,
            directory: String?,
        ): List<Message> =
            if (isV2()) {
                adapter.projectV2(api.listMessagesV2(sessionId, directory))
            } else {
                api.listMessages(sessionId, directory).map(adapter::toMessage)
            }

        suspend fun sendPromptAsync(
            sessionId: String,
            options: PromptSendOptions,
            directory: String?,
        ) {
            if (isV2()) {
                options.mode?.takeIf { it.isNotBlank() }?.let { agent ->
                    api.switchSessionAgent(sessionId, agent, directory)
                }
                adapter.toV2SwitchModelRequest(options)?.let { model ->
                    api.switchSessionModel(sessionId, model, directory)
                }
                api.sendPromptV2(sessionId, adapter.toV2PromptRequest(options), directory)
            } else {
                api.sendPromptAsync(sessionId, adapter.toSendRequest(options), directory)
            }
        }

        private suspend fun isV2(): Boolean {
            protocolMutex.withLock {
                activeConnectionManager?.currentProtocol()?.let { return it == ServerProtocol.V2 }
                val projectRepository = projectRepository ?: return false
                runCatching { projectRepository.probeServer() }
                return activeConnectionManager?.currentProtocol() == ServerProtocol.V2
            }
        }
    }
