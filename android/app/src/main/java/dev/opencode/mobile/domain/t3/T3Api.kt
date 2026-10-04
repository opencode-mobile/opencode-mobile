package dev.opencode.mobile.domain.t3

import dev.opencode.mobile.domain.connection.ConnectionEngine
import dev.opencode.mobile.domain.connection.ConnectionProfile
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class T3Api
    @Inject
    constructor(
        private val http: T3Http,
    ) {
        suspend fun fetchEnvironment(baseUrl: String): T3EnvironmentDescriptor {
            val response = http.get(T3Urls.join(baseUrl, T3Urls.ENVIRONMENT_PATH))
            requireSuccess(response)
            val descriptor = T3Json.environmentDescriptor(response.body)
            val version = descriptor.orchestrationProtocolVersion
            when {
                version == null -> throw T3ProtocolException("This T3 environment uses orchestration protocol 1; OpenCode Mobile requires 2")
                version != T3Urls.ORCHESTRATION_PROTOCOL ->
                    throw T3ProtocolException("This T3 environment uses orchestration protocol $version; OpenCode Mobile requires 2")
            }
            if (descriptor.environmentId.isBlank()) {
                throw T3ProtocolException("T3 environment descriptor did not include environmentId")
            }
            return descriptor
        }

        /**
         * Cheap re-check of an existing environment: re-fetches the descriptor and
         * throws [T3ProtocolException] when the orchestration protocol no longer matches.
         */
        suspend fun validateEnvironment(baseUrl: String): T3EnvironmentDescriptor = fetchEnvironment(baseUrl)

        suspend fun exchangeToken(
            baseUrl: String,
            pairingToken: String,
        ): String {
            val response =
                http.post(
                    url = T3Urls.join(baseUrl, T3Urls.TOKEN_PATH),
                    body = T3TokenExchange.encodeBody(pairingToken),
                    contentType = "application/x-www-form-urlencoded",
                )
            requireSuccess(response)
            return T3Json.accessToken(response.body)
        }

        suspend fun fetchShell(profile: ConnectionProfile): T3ShellSnapshot {
            val response = http.get(T3Urls.join(profile.baseUrl, T3Urls.SHELL_PATH), orchestrationHeaders(profile))
            requireSuccess(response)
            return T3Json.shellSnapshot(response.body)
        }

        suspend fun fetchThread(
            profile: ConnectionProfile,
            threadId: String,
        ): T3ThreadSnapshot {
            val response =
                http.get(
                    T3Urls.join(profile.baseUrl, T3Urls.threadPath(threadId)),
                    orchestrationHeaders(profile),
                )
            requireSuccess(response)
            return T3Json.threadSnapshot(response.body)
        }

        suspend fun fetchAssetBytes(url: String): ByteArray? = http.getBytes(url)

        suspend fun fetchWsTicket(profile: ConnectionProfile): T3WsTicket {
            val response =
                http.post(
                    url = T3Urls.join(profile.baseUrl, T3Urls.WS_TICKET_PATH),
                    body = "",
                    contentType = "application/json",
                    headers = bearerHeaders(profile),
                )
            requireSuccess(response)
            return T3Json.wsTicket(response.body)
        }

        suspend fun pair(
            baseUrl: String,
            pairingToken: String,
        ): T3PairingResult {
            val descriptor = fetchEnvironment(baseUrl)
            val accessToken = exchangeToken(baseUrl, pairingToken)
            val profile =
                ConnectionProfile(
                    baseUrl = baseUrl,
                    username = ConnectionEngine.T3_USERNAME,
                    password = accessToken,
                    engine = ConnectionEngine.T3,
                )
            val projects =
                runCatching { fetchShell(profile).projects }
                    .getOrDefault(emptyList())
            return T3PairingResult(
                accessToken = accessToken,
                descriptor = descriptor,
                projects = projects,
            )
        }

        private fun orchestrationHeaders(profile: ConnectionProfile): Map<String, String> =
            bearerHeaders(profile) +
                mapOf(T3Urls.ORCHESTRATION_PROTOCOL_HEADER to T3Urls.ORCHESTRATION_PROTOCOL.toString())

        private fun bearerHeaders(profile: ConnectionProfile): Map<String, String> =
            mapOf("Authorization" to "Bearer ${profile.password}")

        private fun requireSuccess(response: T3HttpResponse) {
            if (response.code in 200..299) return
            throw T3HttpException.from(response.code, response.body)
        }
    }
