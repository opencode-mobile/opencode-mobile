package dev.opencode.mobile.data.remote

import dev.opencode.mobile.data.remote.dto.CreateSessionRequestDto
import dev.opencode.mobile.data.remote.dto.FileDiffDto
import dev.opencode.mobile.data.remote.dto.ForkSessionRequestDto
import dev.opencode.mobile.data.remote.dto.InitSessionRequestDto
import dev.opencode.mobile.data.remote.dto.PermissionResponseRequestDto
import dev.opencode.mobile.data.remote.dto.RevertSessionRequestDto
import dev.opencode.mobile.data.remote.dto.SessionDto
import dev.opencode.mobile.data.remote.dto.SummarizeSessionRequestDto
import dev.opencode.mobile.data.remote.dto.TodoDto
import dev.opencode.mobile.data.remote.dto.UpdateSessionRequestDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import kotlinx.serialization.json.JsonObject

class SessionsApi(
  private val httpClient: HttpClient,
) {
  suspend fun listSessions(): List<SessionDto> =
    httpClient.get("session").body()

  suspend fun createSession(body: CreateSessionRequestDto): SessionDto =
    httpClient.post("session") { setBody(body) }.body()

  suspend fun getAllSessionStatuses(): Map<String, JsonObject> =
    httpClient.get("session/status").body()

  suspend fun getSession(id: String): SessionDto =
    httpClient.get("session/$id").body()

  suspend fun updateSession(id: String, body: UpdateSessionRequestDto): SessionDto =
    httpClient.patch("session/$id") { setBody(body) }.body()

  suspend fun deleteSession(id: String): Boolean =
    httpClient.delete("session/$id").body()

  suspend fun listChildSessions(id: String): List<SessionDto> =
    httpClient.get("session/$id/children").body()

  suspend fun listSessionTodos(id: String): List<TodoDto> =
    httpClient.get("session/$id/todo").body()

  suspend fun initializeSessionAgents(id: String, body: InitSessionRequestDto): Boolean =
    httpClient.post("session/$id/init") { setBody(body) }.body()

  suspend fun forkSession(id: String, body: ForkSessionRequestDto): SessionDto =
    httpClient.post("session/$id/fork") { setBody(body) }.body()

  suspend fun abortSession(id: String): Boolean =
    httpClient.post("session/$id/abort").body()

  suspend fun shareSession(id: String): SessionDto =
    httpClient.post("session/$id/share").body()

  suspend fun unshareSession(id: String): SessionDto =
    httpClient.delete("session/$id/share").body()

  suspend fun getSessionDiff(id: String, messageId: String?): List<FileDiffDto> =
    httpClient.get("session/$id/diff") {
      messageId?.let { parameter("messageID", it) }
    }.body()

  suspend fun summarizeSession(id: String, body: SummarizeSessionRequestDto): Boolean =
    httpClient.post("session/$id/summarize") { setBody(body) }.body()

  suspend fun revertSession(id: String, body: RevertSessionRequestDto): Boolean =
    httpClient.post("session/$id/revert") { setBody(body) }.body()

  suspend fun unrevertSession(id: String): Boolean =
    httpClient.post("session/$id/unrevert").body()

  suspend fun respondToPermission(
    sessionId: String,
    permissionId: String,
    body: PermissionResponseRequestDto,
  ): Boolean =
    httpClient.post("session/$sessionId/permissions/$permissionId") {
      setBody(body)
    }.body()
}
