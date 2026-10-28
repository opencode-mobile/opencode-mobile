package dev.opencode.mobile.data.remote

import dev.opencode.mobile.data.remote.dto.ApiLocatedEnvelope
import dev.opencode.mobile.data.remote.dto.FileContentDto
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
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.appendEncodedPathSegments
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream

class FilesystemApi private constructor(
    private val httpClient: ClientProvider,
) {
    constructor(httpClient: OpencodeHttpClient) : this(ClientProvider(httpClient::get))

    internal constructor(httpClient: HttpClient) : this(ClientProvider { httpClient })

    suspend fun getPaths(): PathDto = httpClient.get().get("path").body()

    /**
     * v1 `GET /file` for the Add project browser: [location] is the listed folder's root (`/`,
     * `C:\`) so listing never creates a server project; [path] is the absolute folder. A non-2xx
     * answer throws a `ResponseException` carrying the status.
     */
    suspend fun listFiles(
        location: String,
        path: String,
    ): List<FileNodeDto> =
        httpClient
            .get()
            .get("file") {
                parameter("path", path)
                applyDirectory(location)
            }.successBody()

    suspend fun getLocationV2(): V2LocationInfoDto = httpClient.get().get("api/location").body()

    /**
     * v2 `GET /api/fs/list` for the Add project browser, located at [location] (the folder's
     * root). Entry paths are relative to the envelope's `location.directory`. A missing folder
     * or a file answers 404, which throws a `ResponseException` carrying the status.
     */
    suspend fun listFilesV2(
        location: String,
        path: String,
    ): ApiLocatedEnvelope<List<V2FsEntryDto>> =
        httpClient
            .get()
            .get("api/fs/list") {
                parameter("path", path)
                applyLocationDirectory(location)
            }.successBody()

    // File Browser: `directory`/location stays the worktree so the server keeps one instance;
    // only `path` varies.

    /** v1 `GET /file`; [relativeDirectory] `"."` lists the worktree root. */
    suspend fun listProjectEntries(
        worktree: String,
        relativeDirectory: String,
    ): List<FileNodeDto> =
        httpClient
            .get()
            .get("file") {
                parameter("path", relativeDirectory.ifEmpty { "." })
                applyDirectory(worktree)
            }.successBody()

    /** v1 `GET /find/file`: fuzzy file paths relative to [worktree]. */
    suspend fun findProjectFiles(
        worktree: String,
        query: String,
        limit: Int,
    ): List<String> =
        httpClient
            .get()
            .get("find/file") {
                parameter("query", query)
                parameter("dirs", "false")
                parameter("limit", limit)
                applyDirectory(worktree)
            }.successBody()

    /**
     * v1 `GET /file/content`. The server has no size cap, so reading stops past [maxBodyBytes]
     * and reports [V1FileContentResponse.TooLarge].
     */
    suspend fun readProjectFile(
        worktree: String,
        relativePath: String,
        maxBodyBytes: Int,
    ): V1FileContentResponse =
        httpClient
            .get()
            .prepareGet("file/content") {
                parameter("path", relativePath)
                applyDirectory(worktree)
            }.execute { response ->
                response.requireSuccess()
                val read = response.bodyAsChannel().readCapped(maxBodyBytes)
                if (read.exceeded) {
                    V1FileContentResponse.TooLarge
                } else {
                    V1FileContentResponse.Content(json.decodeFromString(read.bytes.decodeToString()))
                }
            }

    /** v2 `GET /api/fs/list`; a blank [relativeDirectory] lists the location root. */
    suspend fun listProjectEntriesV2(
        worktree: String,
        relativeDirectory: String,
    ): List<V2FsEntryDto> =
        httpClient
            .get()
            .get("api/fs/list") {
                relativeDirectory.takeIf { it.isNotEmpty() && it != "." }?.let { parameter("path", it) }
                applyLocationDirectory(worktree)
            }.successBody<ApiLocatedEnvelope<List<V2FsEntryDto>>>()
            .data

    /** v2 `GET /api/fs/find` for files only. */
    suspend fun findProjectFilesV2(
        worktree: String,
        query: String,
        limit: Int,
    ): List<V2FsEntryDto> =
        httpClient
            .get()
            .get("api/fs/find") {
                parameter("query", query)
                parameter("type", "file")
                parameter("limit", limit)
                applyLocationDirectory(worktree)
            }.successBody<ApiLocatedEnvelope<List<V2FsEntryDto>>>()
            .data

    /**
     * v2 `GET /api/fs/read/<path>`: raw bytes, no envelope. Reads at most [byteLimit] + 1 bytes;
     * the extra byte tells the caller the file is larger. [rangeBytes] sends `Range: bytes=0-(n-1)`,
     * which newer servers answer with 206 and older ones ignore (200, full body).
     */
    suspend fun readProjectFileV2(
        worktree: String,
        relativePath: String,
        byteLimit: Int,
        rangeBytes: Int?,
    ): V2RawFileRead =
        httpClient
            .get()
            .prepareGet {
                url { appendEncodedPathSegments(v2ReadPathSegments(relativePath)) }
                applyLocationDirectory(worktree)
                rangeBytes?.let { header(HttpHeaders.Range, "bytes=0-${it - 1}") }
            }.execute { response ->
                response.requireSuccess()
                val read = response.bodyAsChannel().readCapped(byteLimit)
                V2RawFileRead(
                    bytes = read.bytes,
                    exceededLimit = read.exceeded,
                    isPartial = response.status == HttpStatusCode.PartialContent,
                    totalBytes =
                        response.headers[HttpHeaders.ContentRange]?.let(::contentRangeTotal)
                            ?: response.headers[HttpHeaders.ContentLength]
                                ?.toLongOrNull()
                                ?.takeIf { response.status == HttpStatusCode.OK },
                    contentType = response.headers[HttpHeaders.ContentType]?.substringBefore(';')?.trim(),
                )
            }

    /**
     * v2 `GET /api/fs/read/<path>`, [relativePath] under [location]. The route is confined to its
     * location, so a file outside the project (an upload under the server's tmp directory) is
     * read by locating at a directory that contains it. Null on any failure or past [maxBytes].
     */
    suspend fun readServerFileV2(
        location: String,
        relativePath: String,
        maxBytes: Int,
    ): ByteArray? =
        httpClient
            .get()
            .prepareGet {
                url { appendEncodedPathSegments(v2ReadPathSegments(relativePath)) }
                applyLocationDirectory(location)
            }.execute { response ->
                response.requireSuccess()
                val read = response.bodyAsChannel().readCapped(maxBytes)
                read.bytes.takeUnless { read.exceeded }
            }

    private fun v2ReadPathSegments(relativePath: String): List<String> =
        listOf("api", "fs", "read") + encodeRelativePathSegments(relativePath)

    private fun interface ClientProvider {
        fun get(): HttpClient
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Percent-encodes every byte outside RFC 3986 unreserved characters, per segment. */
        fun encodeRelativePathSegments(relativePath: String): List<String> =
            relativePath
                .split('/')
                .filter { it.isNotEmpty() && it != "." }
                .map(::percentEncodeSegment)

        private fun percentEncodeSegment(segment: String): String =
            buildString {
                segment.encodeToByteArray().forEach { byte ->
                    val char = (byte.toInt() and 0xFF).toChar()
                    if (char.isLetterOrDigit() && char.code < 0x80 || char in "-._~") {
                        append(char)
                    } else {
                        append('%')
                        append(HEX[(byte.toInt() shr 4) and 0x0F])
                        append(HEX[byte.toInt() and 0x0F])
                    }
                }
            }

        /** `bytes 0-1048575/4000000` → 4000000; `*` or malformed → null. */
        internal fun contentRangeTotal(header: String): Long? = header.substringAfterLast('/', "").trim().toLongOrNull()

        private const val HEX = "0123456789ABCDEF"
    }
}

sealed interface V1FileContentResponse {
    data class Content(
        val dto: FileContentDto,
    ) : V1FileContentResponse

    data object TooLarge : V1FileContentResponse
}

class V2RawFileRead(
    val bytes: ByteArray,
    val exceededLimit: Boolean,
    val isPartial: Boolean,
    val totalBytes: Long?,
    val contentType: String?,
)

private class CappedRead(
    val bytes: ByteArray,
    val exceeded: Boolean,
)

/** Reads up to [limit] + 1 bytes; stops early so a large body is never fully buffered. */
private suspend fun ByteReadChannel.readCapped(limit: Int): CappedRead {
    val output = ByteArrayOutputStream()
    val chunk = ByteArray(16 * 1024)
    var total = 0L
    while (total <= limit) {
        val wanted = minOf(chunk.size.toLong(), limit + 1L - total).toInt()
        val read = readAvailable(chunk, 0, wanted)
        if (read == -1) break
        if (read == 0) continue
        output.write(chunk, 0, read)
        total += read
    }
    return CappedRead(bytes = output.toByteArray(), exceeded = total > limit)
}

private suspend fun HttpResponse.requireSuccess() {
    if (!status.isSuccess()) {
        throw HttpBodyResponseException(this, runCatching { bodyAsText() }.getOrDefault(""))
    }
}

private suspend inline fun <reified T> HttpResponse.successBody(): T {
    requireSuccess()
    return body()
}
