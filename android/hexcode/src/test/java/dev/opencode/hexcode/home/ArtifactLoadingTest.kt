package dev.opencode.hexcode.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The in-app browser loading an artifact: every file through the app's client (m6-proposal.md §2.4). */
class ArtifactLoadingTest {
    private val address = ArtifactAddress("http://192.168.1.20:4096/", "art_1")

    @Test
    fun theWebViewOpensTheArtifactsViewOnTheAddressInUse() =
        assertEquals("http://192.168.1.20:4096/api/experimental/artifacts/art_1/view/", address.start)

    @Test
    fun theEntryAndEachFileAreFetchedFromTheServer() {
        assertEquals("api/experimental/artifacts/art_1/view/", address.serverPath(address.start))
        assertEquals("api/experimental/artifacts/art_1/view/style.css", address.serverPath("${address.start}style.css"))
        assertEquals("api/experimental/artifacts/art_1/view/data.json", address.serverPath("${address.start}data.json"))
        assertEquals("api/experimental/artifacts/art_1/view/js/app.js?v=2", address.serverPath("${address.start}js/app.js?v=2#top"))
    }

    @Test
    fun anythingElseIsLeftToTheWebView() {
        assertNull(address.serverPath("http://192.168.1.20:4096/api/experimental/artifacts/art_2/view/"))
        assertNull(address.serverPath("http://192.168.1.20:4096/api/session"))
        assertNull(address.serverPath("https://cdn.example.com/lib.js"))
        assertNull(address.serverPath("http://192.168.1.20:4097/api/experimental/artifacts/art_1/view/"))
    }

    @Test
    fun statusesMapToTheBrowsersErrors() {
        assertNull(ArtifactFailure.of(200))
        assertNull(ArtifactFailure.of(304))
        assertEquals(ArtifactFailure.Unreachable, ArtifactFailure.of(null))
        assertEquals(ArtifactFailure.Gone, ArtifactFailure.of(404))
        assertEquals(ArtifactFailure.Off, ArtifactFailure.of(403))
        assertEquals(ArtifactFailure.SignedOut, ArtifactFailure.of(401))
        assertEquals(ArtifactFailure.Failed(500), ArtifactFailure.of(500))
        assertEquals(ArtifactFailure.Failed(503), ArtifactFailure.of(503))
    }

    @Test
    fun theServersTypeAndHeadersPassThrough() {
        val file =
            ArtifactFile(
                200,
                mapOf(
                    "content-type" to "text/html; charset=utf-8",
                    "content-security-policy" to "sandbox allow-scripts",
                    "access-control-allow-origin" to "*",
                    "cache-control" to "private, no-cache",
                    "x-content-type-options" to "nosniff",
                    "content-length" to "120",
                    "transfer-encoding" to "chunked",
                    "connection" to "keep-alive",
                ),
                ByteArray(0),
            )
        assertEquals("text/html", file.mime)
        assertEquals("utf-8", file.charset)
        assertEquals(
            mapOf(
                "content-security-policy" to "sandbox allow-scripts",
                "access-control-allow-origin" to "*",
                "cache-control" to "private, no-cache",
                "x-content-type-options" to "nosniff",
            ),
            file.passedHeaders,
        )
    }

    @Test
    fun aFileWithoutATypeIsBytes() {
        val file = ArtifactFile(200, mapOf("content-type" to "application/json"), ByteArray(0))
        assertEquals("application/json", file.mime)
        assertNull(file.charset)
        assertEquals("application/octet-stream", ArtifactFile(200, emptyMap(), ByteArray(0)).mime)
    }

    @Test
    fun aMarkdownEntryShowsAsPlainText() {
        assertEquals("text/plain", ArtifactFile(200, mapOf("content-type" to "text/markdown; charset=utf-8"), ByteArray(0)).webMime)
        assertEquals("text/css", ArtifactFile(200, mapOf("content-type" to "text/css"), ByteArray(0)).webMime)
    }
}
