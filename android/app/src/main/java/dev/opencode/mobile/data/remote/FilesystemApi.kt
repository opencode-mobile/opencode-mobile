package dev.opencode.mobile.data.remote

import dev.opencode.mobile.data.remote.dto.FileNodeDto
import dev.opencode.mobile.data.remote.dto.PathDto
import dev.opencode.mobile.network.OpencodeHttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter

class FilesystemApi(
    private val httpClient: OpencodeHttpClient,
) {
    suspend fun getPaths(): PathDto = httpClient.get().get("path").body()

    suspend fun listFiles(path: String): List<FileNodeDto> =
        httpClient
            .get()
            .get("file") {
                parameter("path", path)
            }.body()
}
