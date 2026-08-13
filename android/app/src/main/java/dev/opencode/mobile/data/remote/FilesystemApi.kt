package dev.opencode.mobile.data.remote

import dev.opencode.mobile.data.remote.dto.ApiLocatedEnvelope
import dev.opencode.mobile.data.remote.dto.FileNodeDto
import dev.opencode.mobile.data.remote.dto.PathDto
import dev.opencode.mobile.data.remote.dto.V2FsEntryDto
import dev.opencode.mobile.data.remote.dto.V2LocationInfoDto
import dev.opencode.mobile.network.OpencodeHttpClient
import dev.opencode.mobile.network.applyDirectory
import dev.opencode.mobile.network.applyLocationDirectory
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter

class FilesystemApi private constructor(
    private val httpClient: ClientProvider,
) {
    constructor(httpClient: OpencodeHttpClient) : this(ClientProvider(httpClient::get))

    internal constructor(httpClient: HttpClient) : this(ClientProvider { httpClient })

    suspend fun getPaths(): PathDto = httpClient.get().get("path").body()

    // Lists absoluteDirectory via path=. + directory= so paths outside the boot worktree work.
    suspend fun listFiles(absoluteDirectory: String): List<FileNodeDto> =
        httpClient
            .get()
            .get("file") {
                parameter("path", ".")
                applyDirectory(absoluteDirectory)
            }.body()

    suspend fun getLocationV2(): V2LocationInfoDto = httpClient.get().get("api/location").body()

    suspend fun listFilesV2(absoluteDirectory: String): List<V2FsEntryDto> =
        httpClient
            .get()
            .get("api/fs/list") {
                parameter("path", ".")
                applyLocationDirectory(absoluteDirectory)
            }.body<ApiLocatedEnvelope<List<V2FsEntryDto>>>()
            .data

    private fun interface ClientProvider {
        fun get(): HttpClient
    }
}
