package dev.opencode.mobile.domain.connection

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** v2 features that depend on the server release, read from its API contract. */
data class V2ServerFeatures(
    val sessionArchive: Boolean = false,
    val sessionTurnDiff: Boolean = false,
    val sessionGenerate: Boolean = false,
    /** `GET /api/shell`: agent-run shells the background work pill lists (opencode 2.2+). */
    val agentShells: Boolean = false,
    /**
     * `paths.tmp` from `/api/info`, set only when the document also declares the upload route
     * (`POST /api/experimental/fs/write`). Null means streamed uploads are off for this connection.
     */
    val attachmentUploadTmp: String? = null,
    /**
     * The document declares the upload route but `/api/info` could not be read, so
     * [attachmentUploadTmp] is unknown rather than absent. Such a result is not cached.
     */
    val tmpDirectoryUnread: Boolean = false,
) {
    val attachmentUpload: Boolean
        get() = attachmentUploadTmp != null
}

/**
 * Reads release-dependent v2 support from the server's OpenAPI document (`/openapi.json`).
 *
 * opencode 2.1.0 added `archived` to the `PATCH /api/session/{sessionID}` body and the
 * per-turn `GET /api/session/{sessionID}/diff` route. Older 2.x servers answer that PATCH
 * with 204 and ignore the field, `time.archived` predates 2.1, and `/api/info` reports
 * `local` or `0.0.0-<channel>-<n>` for source and preview builds, so the contract is the
 * reliable signal. `POST /api/session/{sessionID}/generate` (`/btw`) shipped in 2.0.x, so
 * earlier 2.x servers lack it too.
 */
object V2ServerContract {
    const val OPENAPI_PATH = "openapi.json"

    private const val SESSION_PATH = "/api/session/{sessionID}"
    private const val SESSION_DIFF_PATH = "/api/session/{sessionID}/diff"
    private const val SESSION_GENERATE_PATH = "/api/session/{sessionID}/generate"
    private const val SHELL_PATH = "/api/shell"
    private const val MAX_SCHEMA_DEPTH = 8
    private const val FS_WRITE_PATH = "/api/experimental/fs/write"
    const val INFO_PATH = "api/info"

    fun features(document: JsonObject): V2ServerFeatures =
        V2ServerFeatures(
            sessionArchive = declaresSessionArchive(document),
            sessionTurnDiff = declaresSessionTurnDiff(document),
            sessionGenerate = declaresSessionGenerate(document),
            agentShells = declaresAgentShells(document),
        )

    fun declaresSessionArchive(document: JsonObject): Boolean {
        val schema =
            document
                .obj("paths")
                ?.obj(SESSION_PATH)
                ?.obj("patch")
                ?.obj("requestBody")
                ?.obj("content")
                ?.obj("application/json")
                ?.obj("schema")
                ?.let { document.resolve(it) }
                ?: return false
        return schema.obj("properties")?.containsKey("archived") == true
    }

    fun declaresSessionTurnDiff(document: JsonObject): Boolean =
        document
            .obj("paths")
            ?.obj(SESSION_DIFF_PATH)
            ?.obj("get") != null

    fun declaresSessionGenerate(document: JsonObject): Boolean =
        document
            .obj("paths")
            ?.obj(SESSION_GENERATE_PATH)
            ?.obj("post") != null

    /** Agent-run shells: `GET /api/shell` lists them; output and removal hang off the same path. */
    fun declaresAgentShells(document: JsonObject): Boolean =
        document
            .obj("paths")
            ?.obj(SHELL_PATH)
            ?.obj("get") != null

    /**
     * The upload route is experimental and can change without a version bump, so it is trusted
     * only while the document still has the shape the app sends: a required `path` query, an
     * `application/octet-stream` body, and a 200 answer whose `data.path` resolves.
     */
    fun declaresAttachmentUpload(document: JsonObject): Boolean {
        val post = document.obj("paths")?.obj(FS_WRITE_PATH)?.obj("post") ?: return false
        val pathQuery =
            (post["parameters"] as? JsonArray)
                ?.mapNotNull { (it as? JsonObject)?.let { parameter -> document.resolve(parameter) } }
                ?.firstOrNull { it.string("name") == "path" && it.string("in") == "query" }
                ?: return false
        if ((pathQuery["required"] as? JsonPrimitive)?.booleanOrNull != true) return false
        val octetStream =
            post
                .obj("requestBody")
                ?.let { document.resolve(it) }
                ?.obj("content")
                ?.obj("application/octet-stream")
        if (octetStream == null) return false
        val answer =
            post
                .obj("responses")
                ?.obj("200")
                ?.let { document.resolve(it) }
                ?.obj("content")
                ?.obj("application/json")
                ?.obj("schema")
                ?: return false
        val data = document.property(answer, "data") ?: return false
        return document.property(data, "path") != null
    }

    /** `paths.tmp` of the `/api/info` answer, or null when the server does not declare it. */
    fun tmpDirectory(info: JsonObject): String? =
        info
            .obj("paths")
            ?.string("tmp")
            ?.takeIf { it.isNotBlank() }

    /** [name] among a schema's properties, looking through `$ref` and `allOf`/`anyOf`/`oneOf` members. */
    private fun JsonObject.property(
        schema: JsonObject,
        name: String,
        depth: Int = 0,
    ): JsonObject? {
        if (depth > MAX_SCHEMA_DEPTH) return null
        val resolved = resolve(schema) ?: return null
        resolved.obj("properties")?.obj(name)?.let { return it }
        for (combinator in listOf("allOf", "anyOf", "oneOf")) {
            val members = resolved[combinator] as? JsonArray ?: continue
            for (member in members) {
                (member as? JsonObject)?.let { property(it, name, depth + 1) }?.let { return it }
            }
        }
        return null
    }

    /** Follows a local `#/components/...` reference; other schemas are returned as is. */
    private fun JsonObject.resolve(schema: JsonObject): JsonObject? {
        val ref = (schema["\$ref"] as? JsonPrimitive)?.contentOrNull ?: return schema
        if (!ref.startsWith("#/")) return null
        return ref
            .removePrefix("#/")
            .split('/')
            .fold(this as JsonObject?) { node, key -> node?.obj(key) }
    }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
