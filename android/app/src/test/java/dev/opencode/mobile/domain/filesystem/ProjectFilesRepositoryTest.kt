package dev.opencode.mobile.domain.filesystem

import dev.opencode.mobile.data.remote.FilesystemApi
import dev.opencode.mobile.data.remote.V1FileContentResponse
import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.connection.ConnectionProfileStore
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.network.ActiveConnectionManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.io.encoding.Base64

class ProjectFilesRepositoryTest {
    // v1

    @Test
    fun v1ListRoot_usesDotPathAndWorktreeDirectory_sortsFoldersFirst() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val repository =
                repository(ServerProtocol.V1) { request ->
                    requests += request
                    respondJson(
                        """
                        [
                          {"name":"b.kt","path":"b.kt","absolute":"/repo/b.kt","type":"file","ignored":false},
                          {"name":"build","path":"build","absolute":"/repo/build","type":"directory","ignored":true},
                          {"name":"A.md","path":"A.md","absolute":"/repo/A.md","type":"file","ignored":false},
                          {"name":"src","path":"src","absolute":"/repo/src","type":"directory","ignored":false}
                        ]
                        """.trimIndent(),
                    )
                }

            val entries = repository.listEntries("/repo", "")

            assertEquals(listOf("build", "src", "A.md", "b.kt"), entries.map { it.name })
            assertEquals(listOf(true, true, false, false), entries.map { it.isDirectory })
            assertTrue(entries.first { it.name == "build" }.isIgnored)
            val request = requests.single()
            assertTrue(request.url.encodedPath.endsWith("/file"))
            assertEquals(".", request.url.parameters["path"])
            assertEquals("/repo", request.url.parameters["directory"])
        }

    @Test
    fun v1ListChild_keepsDirectoryAndVariesPath() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val repository =
                repository(ServerProtocol.V1) { request ->
                    requests += request
                    respondJson("""[{"name":"main.kt","path":"src/main.kt","absolute":"/repo/src/main.kt","type":"file"}]""")
                }

            val entries = repository.listEntries("/repo", "src")

            assertEquals(listOf(ProjectEntry("main.kt", "src/main.kt", isDirectory = false)), entries)
            assertEquals("src", requests.single().url.parameters["path"])
            assertEquals("/repo", requests.single().url.parameters["directory"])
        }

    @Test
    fun v1Find_requestsFilesOnlyWithLimitAndDerivesNames() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val repository =
                repository(ServerProtocol.V1) { request ->
                    requests += request
                    respondJson("""["src/ui/Button.kt","README.md"]""")
                }

            val results = repository.findFiles("/repo", "but")

            assertEquals(listOf("Button.kt", "README.md"), results.map { it.name })
            assertEquals(listOf("src/ui", ""), results.map { it.parentPath })
            assertTrue(results.none { it.isDirectory })
            val request = requests.single()
            assertTrue(request.url.encodedPath.endsWith("/find/file"))
            assertEquals("but", request.url.parameters["query"])
            assertEquals("false", request.url.parameters["dirs"])
            assertEquals("50", request.url.parameters["limit"])
            assertEquals("/repo", request.url.parameters["directory"])
        }

    @Test
    fun v1ReadText_usesContentEndpoint() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val repository =
                repository(ServerProtocol.V1) { request ->
                    requests += request
                    respondJson("""{"type":"text","content":"fun main() {}"}""")
                }

            val content = repository.readFile("/repo", "src/main.kt")

            assertEquals(ProjectFileContent.Text("fun main() {}", isTruncated = false, totalBytes = 13), content)
            val request = requests.single()
            assertTrue(request.url.encodedPath.endsWith("/file/content"))
            assertEquals("src/main.kt", request.url.parameters["path"])
            assertEquals("/repo", request.url.parameters["directory"])
        }

    @Test
    fun v1ReadEmptyText_isEmpty() =
        runTest {
            val repository = repository(ServerProtocol.V1) { respondJson("""{"type":"text","content":""}""") }

            assertEquals(ProjectFileContent.Empty, repository.readFile("/repo", "missing.txt"))
        }

    @Test
    fun v1ReadBase64Image_isImage() =
        runTest {
            val bytes = byteArrayOf(-119, 80, 78, 71, 1, 2, 3)
            val repository =
                repository(ServerProtocol.V1) {
                    respondJson(
                        """{"type":"binary","content":"${Base64.Default.encode(bytes)}","encoding":"base64","mimeType":"image/png"}""",
                    )
                }

            val content = repository.readFile("/repo", "logo.png") as ProjectFileContent.Image

            assertArrayEquals(bytes, content.bytes)
            assertEquals("image/png", content.mimeType)
        }

    @Test
    fun v1ReadBase64NonImage_isBinaryWithMimeAndSize() =
        runTest {
            val repository =
                repository(ServerProtocol.V1) {
                    respondJson(
                        """{"type":"binary","content":"${Base64.Default.encode(
                            ByteArray(5),
                        )}","encoding":"base64","mimeType":"application/pdf"}""",
                    )
                }

            assertEquals(
                ProjectFileContent.Binary(mimeType = "application/pdf", byteCount = 5),
                repository.readFile("/repo", "doc.pdf"),
            )
        }

    @Test
    fun v1ReadLargeText_truncatesToOneMebibyteOnLineBoundary() =
        runTest {
            val line = "x".repeat(99) + "\n"
            val text = line.repeat(20_000) // 2,000,000 bytes
            val repository =
                repository(ServerProtocol.V1) {
                    respondJson(Json.encodeToString(mapOf("type" to "text", "content" to text)))
                }

            val content = repository.readFile("/repo", "big.txt") as ProjectFileContent.Text

            assertTrue(content.isTruncated)
            assertEquals(2_000_000L, content.totalBytes)
            assertTrue(content.text.length <= ProjectFileLimits.TEXT_PREVIEW_BYTES)
            assertFalse(content.text.endsWith("\n"))
            assertTrue(content.text.split('\n').all { it.length == 99 })
        }

    @Test
    fun v1ReadBodyPastCap_stopsReadingAsTooLarge() =
        runTest {
            val api =
                FilesystemApi(testClient { respondJson("""{"type":"text","content":"${"y".repeat(100)}"}""") })

            assertEquals(V1FileContentResponse.TooLarge, api.readProjectFile("/repo", "huge.log", maxBodyBytes = 32))
        }

    @Test
    fun v1ReadBodyPastCap_isTooLargeWithUnknownSize() =
        runTest {
            val text = "z".repeat(ProjectFileLimits.V1_BODY_BYTES + 1)
            val repository =
                repository(ServerProtocol.V1) {
                    respondJson(Json.encodeToString(mapOf("type" to "text", "content" to text)))
                }

            assertEquals(ProjectFileContent.TooLarge(byteCount = null), repository.readFile("/repo", "build.log"))
        }

    @Test
    fun v1ReadLargeText_cutMidCharacter_isTextWithoutReplacementCharacters() =
        runTest {
            val text = "ab" + "€".repeat(ProjectFileLimits.TEXT_PREVIEW_BYTES / 3 + 10) // single line, 3-byte chars
            val repository =
                repository(ServerProtocol.V1) {
                    respondJson(Json.encodeToString(mapOf("type" to "text", "content" to text)))
                }

            val content = repository.readFile("/repo", "euros.txt") as ProjectFileContent.Text

            assertTrue(content.isTruncated)
            assertFalse(content.text.contains('\uFFFD'))
            // 2 + 349,524 × 3 = 1,048,574 bytes; the next character's first 2 bytes are dropped.
            assertEquals(ProjectFileLimits.TEXT_PREVIEW_BYTES - 2, content.text.encodeToByteArray().size)
        }

    @Test
    fun v1ServerError_throwsDomainError() =
        runTest {
            val repository =
                repository(ServerProtocol.V1) {
                    respond("boom", HttpStatusCode.InternalServerError)
                }

            try {
                repository.readFile("/repo", "../outside")
                fail("expected ProjectFilesException")
            } catch (error: ProjectFilesException) {
                assertEquals("Couldn't open file", error.message)
                assertEquals("The server responded with 500.", error.detail)
            }
        }

    // v2

    @Test
    fun v2ListRoot_omitsPathAndUsesLocation_derivesNamesWithoutTrailingSlash() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val repository =
                repository(ServerProtocol.V2) { request ->
                    requests += request
                    respondJson(
                        """
                        {
                          "location":{"directory":"/repo"},
                          "data":[
                            {"path":"README.md","type":"file"},
                            {"path":"src/","type":"directory"},
                            {"path":".env","type":"file"}
                          ]
                        }
                        """.trimIndent(),
                    )
                }

            val entries = repository.listEntries("/repo", "")

            assertEquals(
                listOf(
                    ProjectEntry("src", "src", isDirectory = true),
                    ProjectEntry(".env", ".env", isDirectory = false),
                    ProjectEntry("README.md", "README.md", isDirectory = false),
                ),
                entries,
            )
            val request = requests.single()
            assertTrue(request.url.encodedPath.endsWith("/api/fs/list"))
            assertNull(request.url.parameters["path"])
            assertEquals("/repo", request.url.parameters["location[directory]"])
        }

    @Test
    fun v2ListChild_sendsRelativePathAndKeepsLocationRootedPaths() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val repository =
                repository(ServerProtocol.V2) { request ->
                    requests += request
                    respondJson(
                        """{"location":{"directory":"/repo"},"data":[{"path":"src/ui/","type":"directory"},{"path":"main.kt","type":"file"}]}""",
                    )
                }

            val entries = repository.listEntries("/repo", "src")

            assertEquals(listOf("src/ui", "src/main.kt"), entries.map { it.relativePath })
            assertEquals(listOf("ui", "main.kt"), entries.map { it.name })
            assertTrue(entries.none { it.isIgnored })
            assertEquals("src", requests.single().url.parameters["path"])
            assertEquals("/repo", requests.single().url.parameters["location[directory]"])
        }

    @Test
    fun v2Find_requestsFileTypeWithLimit() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val repository =
                repository(ServerProtocol.V2) { request ->
                    requests += request
                    respondJson("""{"location":{"directory":"/repo"},"data":[{"path":"src/App.kt","type":"file"}]}""")
                }

            val results = repository.findFiles("/repo", "app")

            assertEquals(listOf(ProjectEntry("App.kt", "src/App.kt", isDirectory = false)), results)
            val request = requests.single()
            assertTrue(request.url.encodedPath.endsWith("/api/fs/find"))
            assertEquals("app", request.url.parameters["query"])
            assertEquals("file", request.url.parameters["type"])
            assertEquals("50", request.url.parameters["limit"])
            assertEquals("/repo", request.url.parameters["location[directory]"])
        }

    @Test
    fun v2ReadText_percentEncodesSegmentsAndSendsRange() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val repository =
                repository(ServerProtocol.V2) { request ->
                    requests += request
                    respondBytes("hello\nworld".encodeToByteArray(), contentType = "text/plain; charset=utf-8")
                }

            val content = repository.readFile("/repo", "src/my file#1+ü.kt")

            assertEquals(ProjectFileContent.Text("hello\nworld", isTruncated = false, totalBytes = 11), content)
            val request = requests.single()
            assertTrue(
                request.url.encodedPath,
                request.url.encodedPath.endsWith("/api/fs/read/src/my%20file%231%2B%C3%BC.kt"),
            )
            assertEquals("bytes=0-1048575", request.headers[HttpHeaders.Range])
            assertEquals("/repo", request.url.parameters["location[directory]"])
        }

    @Test
    fun v2Read206_withLargerTotal_isTruncatedAtLastNewline() =
        runTest {
            val body = ("a".repeat(1023) + "\n").repeat(1024) // exactly 1 MiB
            val repository =
                repository(ServerProtocol.V2) {
                    respondBytes(
                        body.encodeToByteArray(),
                        status = HttpStatusCode.PartialContent,
                        extraHeaders = listOf(HttpHeaders.ContentRange to "bytes 0-1048575/5000000"),
                    )
                }

            val content = repository.readFile("/repo", "big.txt") as ProjectFileContent.Text

            assertTrue(content.isTruncated)
            assertEquals(5_000_000L, content.totalBytes)
            assertEquals(1024, content.text.lines().size)
            assertFalse(content.text.endsWith("\n"))
        }

    @Test
    fun v2Read206_exactlyOneMebibyteEndingMidCharacter_isTruncatedText() =
        runTest {
            val limit = ProjectFileLimits.TEXT_PREVIEW_BYTES
            // "ab" + 3-byte characters: 1 MiB holds 349,524 whole ones and 2 bytes of the next.
            val full = ("ab" + "€".repeat(limit / 3 + 1)).encodeToByteArray()
            val repository =
                repository(ServerProtocol.V2) {
                    respondBytes(
                        full.copyOf(limit),
                        status = HttpStatusCode.PartialContent,
                        extraHeaders = listOf(HttpHeaders.ContentRange to "bytes 0-${limit - 1}/${full.size}"),
                    )
                }

            val content = repository.readFile("/repo", "euros.txt") as ProjectFileContent.Text

            assertTrue(content.isTruncated)
            assertEquals(full.size.toLong(), content.totalBytes)
            assertEquals(limit - 2, content.text.encodeToByteArray().size)
            assertFalse(content.text.contains('\uFFFD'))
        }

    @Test
    fun v2Read206_withUnknownTotal_fullRangeIsTruncatedText() =
        runTest {
            val limit = ProjectFileLimits.TEXT_PREVIEW_BYTES
            // "a" + 2-byte characters: the range ends on the lead byte of a split character.
            val cut = ("a" + "é".repeat(limit / 2)).encodeToByteArray().copyOf(limit)
            val repository =
                repository(ServerProtocol.V2) {
                    respondBytes(
                        cut,
                        status = HttpStatusCode.PartialContent,
                        extraHeaders = listOf(HttpHeaders.ContentRange to "bytes 0-${limit - 1}/*"),
                    )
                }

            val content = repository.readFile("/repo", "accents.txt") as ProjectFileContent.Text

            assertTrue(content.isTruncated)
            assertNull(content.totalBytes)
            assertEquals(limit - 1, content.text.encodeToByteArray().size)
        }

    @Test
    fun v2ReadImagePastCap_isTooLarge() =
        runTest {
            val bytes = ByteArray(ProjectFileLimits.IMAGE_PREVIEW_BYTES + 1) { 1 }
            val repository = repository(ServerProtocol.V2) { respondBytes(bytes, contentType = "image/png") }

            assertEquals(
                ProjectFileContent.TooLarge(byteCount = bytes.size.toLong()),
                repository.readFile("/repo", "huge.png"),
            )
        }

    @Test
    fun v2Read200_fullBodyPastLimit_stopsAfterOneMebibyteAndTruncates() =
        runTest {
            val body = ("b".repeat(499) + "\n").repeat(4200) // 2.1 MB, older server ignores Range
            val repository = repository(ServerProtocol.V2) { respondBytes(body.encodeToByteArray()) }

            val content = repository.readFile("/repo", "old-server.txt") as ProjectFileContent.Text

            assertTrue(content.isTruncated)
            // 1 MiB holds 2097 whole 500-byte lines; the partial 2098th is dropped.
            assertEquals(2097, content.text.lines().size)
            assertTrue(content.text.lines().all { it.length == 499 })
        }

    @Test
    fun v2ReadNulByte_isBinary() =
        runTest {
            val repository =
                repository(ServerProtocol.V2) {
                    respondBytes(byteArrayOf(0x50, 0x4B, 0x00, 0x01), contentType = "application/zip")
                }

            assertEquals(
                ProjectFileContent.Binary(mimeType = "application/zip", byteCount = 4),
                repository.readFile("/repo", "archive.zip"),
            )
        }

    @Test
    fun v2ReadInvalidUtf8_isBinary() =
        runTest {
            val repository = repository(ServerProtocol.V2) { respondBytes(byteArrayOf(0x61, 0xC3.toByte(), 0x28)) }

            assertTrue(repository.readFile("/repo", "weird.dat") is ProjectFileContent.Binary)
        }

    @Test
    fun v2ReadEmptyBody_isEmpty() =
        runTest {
            val repository = repository(ServerProtocol.V2) { respondBytes(ByteArray(0)) }

            assertEquals(ProjectFileContent.Empty, repository.readFile("/repo", "empty.txt"))
        }

    @Test
    fun v2ReadImage_requestsWithoutRange() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val bytes = byteArrayOf(-1, -40, -1, 1, 2)
            val repository =
                repository(ServerProtocol.V2) { request ->
                    requests += request
                    respondBytes(bytes, contentType = "image/jpeg")
                }

            val content = repository.readFile("/repo", "photo.JPG") as ProjectFileContent.Image

            assertArrayEquals(bytes, content.bytes)
            assertEquals("image/jpeg", content.mimeType)
            assertNull(requests.single().headers[HttpHeaders.Range])
        }

    @Test
    fun v2NotFound_throwsDomainError() =
        runTest {
            val repository = repository(ServerProtocol.V2) { respond("", HttpStatusCode.NotFound) }

            try {
                repository.listEntries("/repo", "gone")
                fail("expected ProjectFilesException")
            } catch (error: ProjectFilesException) {
                assertEquals("Couldn't load folder", error.message)
                assertEquals("The server responded with 404.", error.detail)
            }
        }

    // Decoder edge cases

    @Test
    fun truncatedText_cutsBeforeSplitMultibyteCharacter() {
        val bytes = "ab€cd".encodeToByteArray() // € is 3 bytes at offsets 2..4
        assertEquals("ab", ProjectFileContentDecoder.truncatedText(bytes, 3))
        assertEquals("ab€", ProjectFileContentDecoder.truncatedText(bytes, 5))
    }

    @Test
    fun truncatedText_dropsTrailingPartialLine() {
        val bytes = "one\ntwo\nthr".encodeToByteArray()
        assertEquals("one\ntwo", ProjectFileContentDecoder.truncatedText(bytes, bytes.size))
    }

    @Test
    fun decodeV2_imagePastCap_isTooLarge() {
        val content =
            ProjectFileContentDecoder.decodeV2(
                path = "huge.png",
                bytes = ByteArray(16),
                exceededLimit = true,
                totalBytes = 20_000_000,
                contentType = "image/png",
                requestedAsImage = true,
            )
        assertEquals(ProjectFileContent.TooLarge(byteCount = 20_000_000), content)
    }

    @Test
    fun decodeV1_imagePastCap_isTooLarge() {
        val bytes = ByteArray(ProjectFileLimits.IMAGE_PREVIEW_BYTES + 1)
        val content =
            ProjectFileContentDecoder.decodeV1(
                path = "huge.png",
                type = "binary",
                content = Base64.Default.encode(bytes),
                encoding = "base64",
                mimeType = "image/png",
            )
        assertEquals(ProjectFileContent.TooLarge(byteCount = bytes.size.toLong()), content)
    }

    @Test
    fun contentRangeTotal_parsesTotalOrNull() {
        assertEquals(4_000_000L, FilesystemApi.contentRangeTotal("bytes 0-1048575/4000000"))
        assertNull(FilesystemApi.contentRangeTotal("bytes 0-1048575/*"))
    }

    private suspend fun repository(
        protocol: ServerProtocol,
        handler: MockRequestHandler,
    ): DefaultProjectFilesRepository =
        DefaultProjectFilesRepository(
            FilesystemApi(testClient(handler)),
            FilesystemDomainAdapter(),
            activeConnectionManager = manager(protocol),
        )

    private suspend fun manager(protocol: ServerProtocol): ActiveConnectionManager {
        val manager = ActiveConnectionManager(InMemoryProfileStore())
        manager.applyProfile(
            ConnectionProfile(baseUrl = "http://127.0.0.1:4096/", username = "opencode", password = ""),
            serverProtocol = protocol,
        )
        return manager
    }

    private fun testClient(handler: MockRequestHandler): HttpClient =
        HttpClient(MockEngine(handler)) {
            expectSuccess = false
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
}

private fun MockRequestHandleScope.respondJson(content: String) =
    respond(
        content = content,
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )

private fun MockRequestHandleScope.respondBytes(
    bytes: ByteArray,
    status: HttpStatusCode = HttpStatusCode.OK,
    contentType: String = "application/octet-stream",
    extraHeaders: List<Pair<String, String>> = emptyList(),
) = respond(
    content = bytes,
    status = status,
    headers =
        io.ktor.http.Headers.build {
            append(HttpHeaders.ContentType, contentType)
            append(HttpHeaders.ContentLength, bytes.size.toString())
            extraHeaders.forEach { (name, value) -> append(name, value) }
        },
)

private class InMemoryProfileStore : ConnectionProfileStore {
    private var profile: ConnectionProfile? = null

    override suspend fun activeProfile(): ConnectionProfile? = profile

    override suspend fun saveProfile(profile: ConnectionProfile) {
        this.profile = profile
    }

    override suspend fun addedWorktrees(serverBaseUrl: String): List<String> = emptyList()

    override suspend fun addWorktree(
        serverBaseUrl: String,
        worktree: String,
    ) {}

    override suspend fun removeWorktree(
        serverBaseUrl: String,
        worktree: String,
    ) {}

    override suspend fun setAddedWorktrees(
        serverBaseUrl: String,
        worktrees: List<String>,
    ) {}

    override suspend fun clearAll() {
        profile = null
    }
}
