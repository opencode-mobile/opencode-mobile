package dev.opencode.hexcode.home

/** An artifact to open in the in-app browser: its id, and the tile's title until the page has its own. */
data class ArtifactPage(
    val id: String,
    val title: String,
)

/** One file of an artifact as the server answered it, fetched by the app's own signed-in client. */
class ArtifactFile(
    val status: Int,
    /** Header names in lower case; the last value of each. */
    val headers: Map<String, String>,
    val body: ByteArray,
) {
    /** `text/html` from `text/html; charset=utf-8`; `application/octet-stream` when there's none. */
    val mime: String
        get() =
            headers["content-type"]
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase()
                ?.ifEmpty { null } ?: "application/octet-stream"

    /** What the web view is told: a Markdown entry shows as plain text (accepted for v1), anything else as it is. */
    val webMime: String get() = if (mime == "text/markdown") "text/plain" else mime

    /** The charset from the content type, if it names one. */
    val charset: String?
        get() =
            headers["content-type"]
                ?.split(';')
                ?.drop(1)
                ?.map { it.trim() }
                ?.firstOrNull { it.startsWith("charset=", ignoreCase = true) }
                ?.substringAfter('=')
                ?.trim('"', ' ')
                ?.ifEmpty { null }

    /**
     * The headers the web view gets: all the server's (its `content-security-policy: sandbox allow-scripts`,
     * `access-control-allow-origin: *`, caching), less the ones about the connection, which the app already handled.
     */
    val passedHeaders: Map<String, String>
        get() = headers.filterKeys { it !in HOP_BY_HOP }

    companion object {
        private val HOP_BY_HOP =
            setOf("content-length", "transfer-encoding", "connection", "keep-alive", "content-encoding", "content-type")
    }
}

/** Fetches an artifact's files with the kit's authenticated client; null when the computer couldn't be reached. */
fun interface ArtifactFetcher {
    suspend fun fetch(path: String): ArtifactFile?
}

/**
 * Where an artifact's files are (m6-proposal.md §2.4, Loading an artifact): the web view loads
 * `<address>/api/experimental/artifacts/<id>/view/`, and every request under that is answered by the app, which fetches
 * `GET <address>/api/experimental/artifacts/<id>/view/<path>?location[directory]=<dir>` itself. The web view never
 * holds the credentials, so no password prompt can appear. [address] is the address in use.
 */
class ArtifactAddress(
    address: String,
    val id: String,
) {
    /** The page the web view opens. */
    val start: String = "${address.trimEnd('/')}${PATH}$id/view/"

    /**
     * The path to fetch, relative to the server (`api/experimental/artifacts/<id>/view/style.css?v=2`), for a URL the
     * web view asks for; null when it isn't one of this artifact's files, which the web view then loads itself.
     */
    fun serverPath(url: String): String? {
        if (!url.startsWith(start)) return null
        val rest = url.substring(start.length).substringBefore('#')
        return "${PATH.removePrefix("/")}$id/view/$rest"
    }

    companion object {
        private const val PATH = "/api/experimental/artifacts/"
    }
}

/** Why an artifact's page didn't open (m6-proposal.md §2.4, Errors); only the main page's answer counts. */
sealed interface ArtifactFailure {
    /** The computer couldn't be reached or timed out: "Couldn't reach <name>", Try again. */
    data object Unreachable : ArtifactFailure

    /** `404`: "This artifact is gone", Done. */
    data object Gone : ArtifactFailure

    /** `403`: "Artifacts are off on <name>", Done and Turn on…. */
    data object Off : ArtifactFailure

    /** `401`: the browser closes, and home shows Signed out. */
    data object SignedOut : ArtifactFailure

    /** Anything else: "Couldn't open the artifact / The server answered <status>.", Try again. */
    data class Failed(
        val status: Int,
    ) : ArtifactFailure

    companion object {
        /** The main page's [status], or null when nothing answered; null back for a page that opened. */
        fun of(status: Int?): ArtifactFailure? =
            when {
                status == null -> Unreachable
                status in 200..299 || status == 304 -> null
                status == 404 -> Gone
                status == 403 -> Off
                status == 401 -> SignedOut
                else -> Failed(status)
            }
    }
}
