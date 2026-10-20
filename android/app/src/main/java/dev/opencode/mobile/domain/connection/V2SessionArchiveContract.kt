package dev.opencode.mobile.domain.connection

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Reads v2 session archive support from the server's OpenAPI document (`/openapi.json`).
 *
 * opencode 2.1.0 added `archived` to the `PATCH /api/session/{sessionID}` body. Older 2.x
 * servers answer that PATCH with 204 and ignore the field, `time.archived` predates 2.1,
 * and `/api/info` reports `local` or `0.0.0-<channel>-<n>` for source and preview builds,
 * so the contract is the reliable signal.
 */
object V2SessionArchiveContract {
    const val OPENAPI_PATH = "openapi.json"

    private const val SESSION_PATH = "/api/session/{sessionID}"

    fun isDeclared(document: JsonObject): Boolean {
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
