package dev.opencode.mobile.domain.connection

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** v2 features that depend on the server release, read from its API contract. */
data class V2ServerFeatures(
    val sessionArchive: Boolean = false,
    val sessionTurnDiff: Boolean = false,
    val sessionGenerate: Boolean = false,
)

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

    fun features(document: JsonObject): V2ServerFeatures =
        V2ServerFeatures(
            sessionArchive = declaresSessionArchive(document),
            sessionTurnDiff = declaresSessionTurnDiff(document),
            sessionGenerate = declaresSessionGenerate(document),
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
}
