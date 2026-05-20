package dev.opencode.mobile.data.remote

import dev.opencode.mobile.data.remote.dto.HealthDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get

class HealthApi(
  private val httpClient: HttpClient,
) {
  suspend fun getHealth(): HealthDto = httpClient.get("global/health").body()
}
