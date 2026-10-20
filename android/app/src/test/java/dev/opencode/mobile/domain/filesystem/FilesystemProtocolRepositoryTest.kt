package dev.opencode.mobile.domain.filesystem

import dev.opencode.mobile.data.remote.FilesystemApi
import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.connection.ConnectionProfileStore
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.network.ActiveConnectionManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FilesystemProtocolRepositoryTest {
    @Test
    fun v1GetPaths_usesUnprefixedPath() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                testClient { request ->
                    requests += request
                    respondJson("""{"home":"/home","worktree":"/repo","directory":"/repo"}""")
                }
            val repository =
                DefaultFilesystemRepository(
                    FilesystemApi(client),
                    FilesystemDomainAdapter(),
                    activeConnectionManager = manager(ServerProtocol.V1),
                )

            val paths = repository.getPaths()

            assertEquals("/home", paths.home)
            assertEquals("/repo", paths.directory)
            assertTrue(
                requests
                    .single()
                    .url.encodedPath
                    .endsWith("/path"),
            )
        }

    @Test
    fun v2GetPaths_mapsLocationDirectory() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                testClient { request ->
                    requests += request
                    respondJson(
                        """
                        {
                          "directory":"/repo",
                          "project":{"id":"p","directory":"/repo","canonical":"/repo"}
                        }
                        """.trimIndent(),
                    )
                }
            val repository =
                DefaultFilesystemRepository(
                    FilesystemApi(client),
                    FilesystemDomainAdapter(),
                    activeConnectionManager = manager(ServerProtocol.V2),
                )

            val paths = repository.getPaths()

            assertEquals("/repo", paths.home)
            assertEquals("/repo", paths.directory)
            assertEquals("/repo", paths.worktree)
            assertTrue(
                requests
                    .single()
                    .url.encodedPath
                    .contains("/api/location"),
            )
        }

    @Test
    fun v2ListDirectory_joinsRelativeEntries() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                testClient { request ->
                    requests += request
                    respondJson(
                        """
                        {
                          "location":{"directory":"/repo","project":{"id":"p","directory":"/repo","canonical":"/repo"}},
                          "data":[
                            {"path":"src/","type":"directory"},
                            {"path":"docs","type":"directory"},
                            {"path":".github/","type":"directory"},
                            {"path":"README.md","type":"file"}
                          ]
                        }
                        """.trimIndent(),
                    )
                }
            val repository =
                DefaultFilesystemRepository(
                    FilesystemApi(client),
                    FilesystemDomainAdapter(),
                    activeConnectionManager = manager(ServerProtocol.V2),
                )

            val nodes = repository.listDirectory("/repo").folders

            assertEquals(listOf(".github", "docs", "src"), nodes.map { it.name })
            assertEquals(listOf("/repo/.github", "/repo/docs", "/repo/src"), nodes.map { it.absolutePath })
            assertTrue(nodes.all { it.isDirectory })
            assertTrue(
                requests
                    .single()
                    .url.encodedPath
                    .contains("/api/fs/list"),
            )
            assertEquals("/", requests.single().url.parameters["location[directory]"])
            assertEquals("/repo", requests.single().url.parameters["path"])
        }

    @Test
    fun v1ListDirectory_locatesAtRootAndPassesAbsolutePath() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                testClient { request ->
                    requests += request
                    respondJson(
                        "[" +
                            """{"name":"OpenChamber 2.3.3","path":"Volumes/OpenChamber 2.3.3/",""" +
                            """"absolute":"/Volumes/OpenChamber 2.3.3","type":"directory"},""" +
                            """{"name":"notes.txt","path":"Volumes/notes.txt","absolute":"/Volumes/notes.txt","type":"file"}""" +
                            "]",
                    )
                }
            val repository =
                DefaultFilesystemRepository(
                    FilesystemApi(client),
                    FilesystemDomainAdapter(),
                    activeConnectionManager = manager(ServerProtocol.V1),
                )

            val nodes = repository.listDirectory("/Volumes").folders

            assertEquals(listOf("OpenChamber 2.3.3"), nodes.map { it.name })
            assertEquals(listOf("/Volumes/OpenChamber 2.3.3"), nodes.map { it.absolutePath })
            val request = requests.single()
            assertTrue(request.url.encodedPath.endsWith("/file"))
            assertEquals("/", request.url.parameters["directory"])
            assertEquals("/Volumes", request.url.parameters["path"])
        }

    @Test
    fun v1ListDirectory_windowsLocatesAtDriveRoot() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                testClient { request ->
                    requests += request
                    respondJson("[]")
                }
            val repository =
                DefaultFilesystemRepository(
                    FilesystemApi(client),
                    FilesystemDomainAdapter(),
                    activeConnectionManager = manager(ServerProtocol.V1),
                )

            repository.listDirectory("D:\\work\\code")

            assertEquals("D:\\", requests.single().url.parameters["directory"])
            assertEquals("D:\\work\\code", requests.single().url.parameters["path"])
        }

    @Test
    fun v2ListDirectory_resolvesEntriesAgainstResponseLocation() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                testClient { request ->
                    requests += request
                    respondJson(
                        """
                        {
                          "location":{"directory":"/","project":{"id":"global","directory":"/","canonical":"/"}},
                          "data":[{"path":"Volumes/OpenChamber 2.3.3/","type":"directory"}]
                        }
                        """.trimIndent(),
                    )
                }
            val repository =
                DefaultFilesystemRepository(
                    FilesystemApi(client),
                    FilesystemDomainAdapter(),
                    activeConnectionManager = manager(ServerProtocol.V2),
                )

            val nodes = repository.listDirectory("/Volumes").folders

            assertEquals(listOf("OpenChamber 2.3.3"), nodes.map { it.name })
            assertEquals(listOf("/Volumes/OpenChamber 2.3.3"), nodes.map { it.absolutePath })
            assertEquals("/", requests.single().url.parameters["location[directory]"])
            assertEquals("/Volumes", requests.single().url.parameters["path"])
        }

    @Test
    fun v2ListDirectory_windowsResolvesParentRelativeAndCrossDriveEntries() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                testClient { request ->
                    requests += request
                    respondJson(
                        """
                        {
                          "location":{"directory":"C:\\Users\\me"},
                          "data":[
                            {"path":"..\\other\\code\\","type":"directory"},
                            {"path":"E:\\backup\\","type":"directory"}
                          ]
                        }
                        """.trimIndent(),
                    )
                }
            val repository =
                DefaultFilesystemRepository(
                    FilesystemApi(client),
                    FilesystemDomainAdapter(),
                    activeConnectionManager = manager(ServerProtocol.V2),
                )

            val nodes = repository.listDirectory("C:\\Users\\other").folders

            assertEquals(listOf("backup", "code"), nodes.map { it.name })
            assertEquals(listOf("E:\\backup", "C:\\Users\\other\\code"), nodes.map { it.absolutePath })
            assertEquals("C:\\", requests.single().url.parameters["location[directory]"])
            assertEquals("C:\\Users\\other", requests.single().url.parameters["path"])
        }

    @Test
    fun v2ListDirectory_withoutLocationResolvesAgainstRoot() =
        runTest {
            val client =
                testClient {
                    respondJson("""{"data":[{"path":"Users\\me\\code\\","type":"directory"}]}""")
                }
            val repository =
                DefaultFilesystemRepository(
                    FilesystemApi(client),
                    FilesystemDomainAdapter(),
                    activeConnectionManager = manager(ServerProtocol.V2),
                )

            val nodes = repository.listDirectory("C:\\Users\\me").folders

            assertEquals(listOf("C:\\Users\\me\\code"), nodes.map { it.absolutePath })
        }

    @Test
    fun v2ListDirectory_keepsRootChildrenNamed() =
        runTest {
            val client =
                testClient {
                    respondJson(
                        """
                        {
                          "location":{"directory":"/","project":{"id":"global","directory":"/","canonical":"/"}},
                          "data":[
                            {"path":"home/","type":"directory"},
                            {"path":"etc/","type":"directory"}
                          ]
                        }
                        """.trimIndent(),
                    )
                }
            val repository =
                DefaultFilesystemRepository(
                    FilesystemApi(client),
                    FilesystemDomainAdapter(),
                    activeConnectionManager = manager(ServerProtocol.V2),
                )

            val nodes = repository.listDirectory("/").folders

            assertEquals(listOf("etc", "home"), nodes.map { it.name })
            assertEquals(listOf("/etc", "/home"), nodes.map { it.absolutePath })
        }

    @Test
    fun v2ListDirectory_joinsWindowsEntriesWithBackslashes() =
        runTest {
            val client =
                testClient {
                    respondJson(
                        """
                        {
                          "location":{
                            "directory":"C:\\Users\\me",
                            "project":{"id":"global","directory":"C:\\Users\\me","canonical":"C:\\Users\\me"}
                          },
                          "data":[
                            {"path":"code\\","type":"directory"},
                            {"path":"D:\\archive\\","type":"directory"}
                          ]
                        }
                        """.trimIndent(),
                    )
                }
            val repository =
                DefaultFilesystemRepository(
                    FilesystemApi(client),
                    FilesystemDomainAdapter(),
                    activeConnectionManager = manager(ServerProtocol.V2),
                )

            val nodes = repository.listDirectory("C:\\Users\\me").folders

            assertEquals(listOf("archive", "code"), nodes.map { it.name })
            assertEquals(listOf("D:\\archive", "C:\\Users\\me\\code"), nodes.map { it.absolutePath })
        }

    @Test
    fun v1ListDirectory_reportsGitAndVisibleFolderCount() =
        runTest {
            val client =
                testClient {
                    respondJson(
                        "[" +
                            """{"name":".git","path":".git","absolute":"/repo/.git","type":"directory"},""" +
                            """{"name":"src","path":"src","absolute":"/repo/src","type":"directory"},""" +
                            """{"name":"README.md","path":"README.md","absolute":"/repo/README.md","type":"file"}""" +
                            "]",
                    )
                }

            val listing = repository(client, ServerProtocol.V1).listDirectory("/repo")

            assertTrue(listing.isGitRepository)
            assertEquals(listOf(".git", "src"), listing.folders.map { it.name })
            assertEquals(1, listing.visibleFolderCount)
        }

    @Test
    fun v2ListDirectory_gitFileInWorktreeCountsAsRepository() =
        runTest {
            val client =
                testClient {
                    respondJson("""{"data":[{"path":".git","type":"file"},{"path":"src/","type":"directory"}]}""")
                }

            val listing = repository(client, ServerProtocol.V2).listDirectory("/wt")

            assertTrue(listing.isGitRepository)
            assertEquals(listOf("src"), listing.folders.map { it.name })
        }

    @Test
    fun v1ListDirectory_withoutGitIsNotRepository() =
        runTest {
            val client =
                testClient {
                    respondJson("""[{"name":".github","path":".github","absolute":"/a/.github","type":"directory"}]""")
                }

            val listing = repository(client, ServerProtocol.V1).listDirectory("/a")

            assertFalse(listing.isGitRepository)
            assertEquals(0, listing.visibleFolderCount)
        }

    @Test
    fun v2ListDirectory_404IsNotFoundWithoutWalkingUp() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                testClient { request ->
                    requests += request
                    respondError("""{"_tag":"FileNotFoundError"}""", HttpStatusCode.NotFound)
                }

            val error = listFailure(repository(client, ServerProtocol.V2), "/home/me/gone")

            assertTrue(error is DirectoryNotFoundException)
            assertEquals(listOf("/home/me/gone"), requests.map { it.url.parameters["path"] })
        }

    @Test
    fun v1ListDirectory_500WithMissingSegmentInParentIsNotFound() =
        runTest {
            val client =
                testClient { request ->
                    when (request.url.parameters["path"]) {
                        "/home/me" ->
                            respondJson("""[{"name":"code","path":"code","absolute":"/home/me/code","type":"directory"}]""")
                        else -> respondError("""{"name":"UnknownError"}""", HttpStatusCode.InternalServerError)
                    }
                }

            val error = listFailure(repository(client, ServerProtocol.V1), "/home/me/gone")

            assertTrue(error is DirectoryNotFoundException)
        }

    @Test
    fun v1ListDirectory_500ForAFileIsNotFound() =
        runTest {
            val client =
                testClient { request ->
                    when (request.url.parameters["path"]) {
                        "/home/me" ->
                            respondJson("""[{"name":"notes.txt","path":"notes.txt","absolute":"/home/me/notes.txt","type":"file"}]""")
                        else -> respondError("""{"name":"UnknownError"}""", HttpStatusCode.InternalServerError)
                    }
                }

            val error = listFailure(repository(client, ServerProtocol.V1), "/home/me/notes.txt")

            assertTrue(error is DirectoryNotFoundException)
        }

    @Test
    fun v1ListDirectory_500ForAnExistingFolderRethrows() =
        runTest {
            val client =
                testClient { request ->
                    when (request.url.parameters["path"]) {
                        "/home/me" ->
                            respondJson("""[{"name":"locked","path":"locked","absolute":"/home/me/locked","type":"directory"}]""")
                        else -> respondError("""{"name":"UnknownError"}""", HttpStatusCode.InternalServerError)
                    }
                }

            val error = listFailure(repository(client, ServerProtocol.V1), "/home/me/locked")

            assertTrue(error is ResponseException)
            assertEquals(HttpStatusCode.InternalServerError, (error as ResponseException).response.status)
        }

    @Test
    fun v1ListDirectory_walksUpToTheNearestListingAncestor() =
        runTest {
            val requests = mutableListOf<String?>()
            val client =
                testClient { request ->
                    val path = request.url.parameters["path"]
                    requests += path
                    when (path) {
                        "/home" ->
                            respondJson("""[{"name":"me","path":"me","absolute":"/home/me","type":"directory"}]""")
                        else -> respondError("""{"name":"UnknownError"}""", HttpStatusCode.InternalServerError)
                    }
                }

            val error = listFailure(repository(client, ServerProtocol.V1), "/home/other/gone")

            assertTrue(error is DirectoryNotFoundException)
            assertEquals(listOf("/home/other/gone", "/home/other", "/home"), requests)
        }

    @Test
    fun v1ListDirectory_windowsSegmentsMatchIgnoringCase() =
        runTest {
            val client =
                testClient { request ->
                    when (request.url.parameters["path"]) {
                        "C:\\Users" ->
                            respondJson("""[{"name":"Me","path":"Me","absolute":"C:\\Users\\Me","type":"directory"}]""")
                        else -> respondError("""{"name":"UnknownError"}""", HttpStatusCode.InternalServerError)
                    }
                }

            val error = listFailure(repository(client, ServerProtocol.V1), "C:\\Users\\me")

            assertTrue(error is ResponseException)
        }

    @Test
    fun v1ListDirectory_rethrowsWhenNoAncestorLists() =
        runTest {
            val client = testClient { respondError("""{"name":"UnknownError"}""", HttpStatusCode.InternalServerError) }

            val error = listFailure(repository(client, ServerProtocol.V1), "/a/b")

            assertTrue(error is ResponseException)
        }

    private suspend fun listFailure(
        repository: DefaultFilesystemRepository,
        path: String,
    ): Exception {
        try {
            repository.listDirectory(path)
        } catch (error: Exception) {
            return error
        }
        fail("Expected $path to fail")
        error("unreachable")
    }

    private suspend fun repository(
        client: HttpClient,
        protocol: ServerProtocol,
    ) = DefaultFilesystemRepository(
        FilesystemApi(client),
        FilesystemDomainAdapter(),
        activeConnectionManager = manager(protocol),
    )

    private suspend fun manager(protocol: ServerProtocol): ActiveConnectionManager {
        val manager = ActiveConnectionManager(FakeProfileStore())
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

private fun io.ktor.client.engine.mock.MockRequestHandleScope.respondJson(content: String) =
    respond(
        content = content,
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )

private fun io.ktor.client.engine.mock.MockRequestHandleScope.respondError(
    content: String,
    status: HttpStatusCode,
) = respond(
    content = content,
    status = status,
    headers = headersOf(HttpHeaders.ContentType, "application/json"),
)

private class FakeProfileStore : ConnectionProfileStore {
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
