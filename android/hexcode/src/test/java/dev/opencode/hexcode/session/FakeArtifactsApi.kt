package dev.opencode.hexcode.session

import dev.opencode.hexcode.firstrun.ModelChoice
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** An OpenCode in memory for the artifact feature: answers are set by the test, calls are recorded. */
class FakeArtifactsApi : ArtifactsApi {
    var declared: Boolean? = true
    var declaredReads = 0

    /** The list route's answer now; [lists] is read first, one answer per call, when set. */
    var list: ArtifactListAnswer = ArtifactListAnswer.Off
    val lists = ArrayDeque<ArtifactListAnswer>()
    var listCalls = 0

    var config: ConfigDocuments? = configOf()
    var configCalls = 0
    val files = HashMap<String, ByteArray>()
    var fileReadFails = false
    val writes = mutableListOf<Pair<String, ByteArray>>()
    var writeOk = true

    /** Runs after a write, like the server reading the file again. */
    var afterWrite: (String, ByteArray) -> Unit = { _, _ -> }

    val ruleCalls = mutableListOf<String>()
    var runStart: Long? = 1_000L
    val sessions = mutableSetOf<String>()
    val created = mutableListOf<Triple<String, ModelChoice?, List<ArtifactPrompts.Rule>>>()
    val prompts = mutableListOf<Pair<String, String>>()
    var promptFails: Exception? = null
    var approvals: List<PendingItem> = emptyList()
    val replies = mutableListOf<Triple<String, String, Boolean>>()

    override suspend fun declared(): Boolean? {
        declaredReads++
        return declared
    }

    override suspend fun list(directory: String): ArtifactListAnswer {
        listCalls++
        return lists.removeFirstOrNull() ?: list
    }

    override suspend fun config(directory: String): ConfigDocuments? {
        configCalls++
        return config
    }

    override suspend fun readFile(path: String): FileRead {
        if (fileReadFails) return FileRead.Unknown
        return files[path]?.let { FileRead.Exists(it) } ?: FileRead.Missing
    }

    override suspend fun writeFile(
        path: String,
        bytes: ByteArray,
        directory: String,
    ): Boolean {
        writes += path to bytes
        if (writeOk) {
            files[path] = bytes
            afterWrite(path, bytes)
        }
        return writeOk
    }

    override suspend fun putRules(sessionId: String) {
        ruleCalls += "put $sessionId"
    }

    override suspend fun deleteRules(sessionId: String) {
        ruleCalls += "delete $sessionId"
    }

    override suspend fun runStart(sessionId: String): Long? = runStart

    override suspend fun sessionExists(sessionId: String): Boolean? = sessionId in sessions

    override suspend fun createSetupSession(
        directory: String,
        model: ModelChoice?,
        rules: List<ArtifactPrompts.Rule>,
    ): String {
        val id = "ses_setup${created.size + 1}"
        created += Triple(directory, model, rules)
        sessions += id
        return id
    }

    override suspend fun prompt(
        sessionId: String,
        text: String,
    ) {
        promptFails?.let { throw it }
        prompts += sessionId to text
    }

    override suspend fun approvals(sessionId: String): List<PendingItem> = approvals

    override suspend fun reply(
        sessionId: String,
        requestId: String,
        allow: Boolean,
    ) {
        replies += Triple(sessionId, requestId, allow)
    }

    companion object {
        const val DIR = "/tmp/hex/proj"
        const val GLOBAL = "/tmp/hex/config/opencode"
        const val GLOBAL_FILE = "$GLOBAL/opencode.json"

        private val json = Json

        fun info(text: String): JsonObject = json.parseToJsonElement(text) as JsonObject

        /** The config with [documents] (path to info JSON) and the global folder. */
        fun configOf(vararg documents: Pair<String?, String>): ConfigDocuments =
            ConfigDocuments(documents.map { (path, info) -> ConfigDocument(path, info(info)) }, GLOBAL)
    }
}

/** A store in memory. */
class MemoryArtifactsStore(
    var session: Pair<String, String>? = null,
    var seen: Boolean = false,
) : ArtifactsStore {
    override suspend fun setupSession() = session

    override suspend fun saveSetupSession(
        id: String,
        directory: String,
    ) {
        session = id to directory
    }

    override suspend fun hintSeen() = seen

    override suspend fun setHintSeen() {
        seen = true
    }
}
