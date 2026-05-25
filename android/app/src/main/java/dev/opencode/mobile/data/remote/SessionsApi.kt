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
import dev.opencode.mobile.network.applyDirectory
import dev.opencode.mobile.network.setJsonBody
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

class SessionsApi(
  private val httpClient: HttpClient,
  private val json: Json,
) {
  suspend fun listSessions(directory: String? = null): List<SessionDto> =
    httpClient.get("session") {
      applyDirectory(directory)
    }.body()

  suspend fun createSession(body: CreateSessionRequestDto, directory: String? = null): SessionDto =
    httpClient.post("session") {
      applyDirectory(directory)
      setJsonBody(json, body)
    }.body()

  suspend fun getAllSessionStatuses(directory: String? = null): Map<String, JsonObject> =
    httpClient.get("session/status") {
      applyDirectory(directory)
    }.body()

  suspend fun getSession(id: String, directory: String? = null): SessionDto =
    httpClient.get("session/$id") {
      applyDirectory(directory)
    }.body()

  suspend fun updateSession(id: String, body: UpdateSessionRequestDto, directory: String? = null): SessionDto =
    httpClient.patch("session/$id") {
      applyDirectory(directory)
      setJsonBody(json, body)
    }.body()

  suspend fun deleteSession(id: String, directory: String? = null): Boolean =
    httpClient.delete("session/$id") {
      applyDirectory(directory)
    }.body()

  suspend fun listChildSessions(id: String, directory: String? = null): List<SessionDto> =
    httpClient.get("session/$id/children") {
      applyDirectory(directory)
    }.body()

  suspend fun listSessionTodos(id: String, directory: String? = null): List<TodoDto> =
    httpClient.get("session/$id/todo") {
      applyDirectory(directory)
    }.body()

  suspend fun initializeSessionAgents(
    id: String,
    body: InitSessionRequestDto,
    directory: String? = null,
  ): Boolean =
    httpClient.post("session/$id/init") {
      applyDirectory(directory)
      setJsonBody(json, body)
    }.body()

  suspend fun forkSession(id: String, body: ForkSessionRequestDto, directory: String? = null): SessionDto =
    httpClient.post("session/$id/fork") {
      applyDirectory(directory)
      setJsonBody(json, body)
    }.body()

  suspend fun abortSession(id: String, directory: String? = null): Boolean =
    httpClient.post("session/$id/abort") {
      applyDirectory(directory)
    }.body()

  suspend fun shareSession(id: String, directory: String? = null): SessionDto =
    httpClient.post("session/$id/share") {
      applyDirectory(directory)
    }.body()

  suspend fun unshareSession(id: String, directory: String? = null): SessionDto =
    httpClient.delete("session/$id/share") {
      applyDirectory(directory)
    }.body()

  suspend fun getSessionDiff(id: String, messageId: String?, directory: String? = null): List<FileDiffDto> =
    httpClient.get("session/$id/diff") {
      applyDirectory(directory)
      messageId?.let { parameter("messageID", it) }
    }.body()

  suspend fun summarizeSession(
    id: String,
    body: SummarizeSessionRequestDto,
    directory: String? = null,
  ): Boolean =
    httpClient.post("session/$id/summarize") {
      applyDirectory(directory)
      setJsonBody(json, body)
    }.body()

  suspend fun revertSession(id: String, body: RevertSessionRequestDto, directory: String? = null): Boolean =
    httpClient.post("session/$id/revert") {
      applyDirectory(directory)
      setJsonBody(json, body)
    }.body()

  suspend fun unrevertSession(id: String, directory: String? = null): Boolean =
    httpClient.post("session/$id/unrevert") {
      applyDirectory(directory)
    }.body()

  suspend fun respondToPermission(
    sessionId: String,
    permissionId: String,
    body: PermissionResponseRequestDto,
    directory: String? = null,
  ): Boolean =
    httpClient.post("session/$sessionId/permissions/$permissionId") {
      applyDirectory(directory)
      setJsonBody(json, body)
    }.body()
}
