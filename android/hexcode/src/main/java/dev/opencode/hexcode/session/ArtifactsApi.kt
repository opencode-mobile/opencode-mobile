package dev.opencode.hexcode.session

import dev.opencode.hexcode.firstrun.ModelChoice

/** A file read with `GET /api/fs/read` from its own folder: its exact bytes, or that it isn't there. */
sealed interface FileRead {
    class Exists(
        val bytes: ByteArray,
    ) : FileRead

    data object Missing : FileRead

    /** The server couldn't be asked, or answered something else. */
    data object Unknown : FileRead
}

/** The calls the artifact feature makes ([dev.opencode.hexcode.app.OpenCodeArtifactsApi] in the app). */
interface ArtifactsApi {
    /** Whether `/openapi.json` declares the artifact routes ([ArtifactsDeclared]); null when it couldn't be read. */
    suspend fun declared(): Boolean?

    /** `GET /api/experimental/artifacts?location[directory]=<dir>`. */
    suspend fun list(directory: String): ArtifactListAnswer

    /** `GET /api/config?location[directory]=<dir>`; null when it couldn't be read. */
    suspend fun config(directory: String): ConfigDocuments?

    /** `GET /api/fs/read/<name>` with the file's own folder as the location. */
    suspend fun readFile(path: String): FileRead

    /** `POST /api/experimental/fs/write` of [bytes] to the absolute [path]: true when it was written. */
    suspend fun writeFile(
        path: String,
        bytes: ByteArray,
        directory: String,
    ): Boolean

    /** Puts [ArtifactPrompts.RULES] on [sessionId] as [ArtifactPrompts.RULES_ID]. */
    suspend fun putRules(sessionId: String)

    /** Removes [ArtifactPrompts.RULES_ID] from [sessionId]; one that isn't there is no failure. */
    suspend fun deleteRules(sessionId: String)

    /** `time.busy` of [sessionId]: when its latest run started, or null before any. */
    suspend fun runStart(sessionId: String): Long?

    /** Whether the session is still there; null when that couldn't be read. */
    suspend fun sessionExists(sessionId: String): Boolean?

    /** Creates "Hex Code setup" in [directory] with [model] and [rules]; returns its id. */
    suspend fun createSetupSession(
        directory: String,
        model: ModelChoice?,
        rules: List<ArtifactPrompts.Rule>,
    ): String

    /** Sends [text] to [sessionId], as the user. */
    suspend fun prompt(
        sessionId: String,
        text: String,
    )

    /** The approvals [sessionId] waits for now. */
    suspend fun approvals(sessionId: String): List<PendingItem>

    /** Allow once, or Reject (with no note: the run should end). */
    suspend fun reply(
        sessionId: String,
        requestId: String,
        allow: Boolean,
    )
}

/** What the phone keeps for the artifact feature. */
interface ArtifactsStore {
    /** The setup session made earlier, and the project it's in. */
    suspend fun setupSession(): Pair<String, String>?

    suspend fun saveSetupSession(
        id: String,
        directory: String,
    )

    /** The one-time hint has been shown on this phone. */
    suspend fun hintSeen(): Boolean

    suspend fun setHintSeen()
}

/** Keeps nothing: the sample sessions, and tests that don't care. */
object NoArtifactsStore : ArtifactsStore {
    override suspend fun setupSession(): Pair<String, String>? = null

    override suspend fun saveSetupSession(
        id: String,
        directory: String,
    ) = Unit

    override suspend fun hintSeen() = true

    override suspend fun setHintSeen() = Unit
}
