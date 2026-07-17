package dev.opencode.mobile.data.remote

import dev.opencode.mobile.data.remote.dto.FileNodeDto
import dev.opencode.mobile.data.remote.dto.PathDto
import dev.opencode.mobile.network.OpencodeHttpClient
import dev.opencode.mobile.network.applyDirectory
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter

class FilesystemApi(
    private val httpClient: OpencodeHttpClient,
) {
    suspend fun getPaths(): PathDto = httpClient.get().get("path").body()

    // Lists absoluteDirectory via path=. + directory= so paths outside the boot worktree work.
    suspend fun listFiles(absoluteDirectory: String): List<FileNodeDto> =
        httpClient
            .get()
            .get("file") {
                parameter("path", ".")
                applyDirectory(absoluteDirectory)
            }.body()
}
