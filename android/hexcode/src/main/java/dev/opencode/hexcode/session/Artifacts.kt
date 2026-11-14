package dev.opencode.hexcode.session

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Whether OpenCode's experimental artifacts work for today's project (app-plan.md, Artifacts (M8) › Drift): read from
 * the list route, always with `location[directory]`. [Unavailable] is a server that doesn't declare the routes, or
 * answers them in a way this app doesn't know; it stays for the connection, and the feature switches off quietly.
 */
enum class ArtifactsState { Checking, On, Off, Unavailable }

/** One artifact from `GET /api/experimental/artifacts` (`Artifact.Info`); [sessionId] is optional on the server. */
data class ArtifactInfo(
    val id: String,
    val provider: String,
    val sessionId: String?,
    val title: String,
    val entry: String,
    /** A path on the server for the local provider (`/api/experimental/artifacts/<id>/view/`). */
    val url: String,
    val created: Long,
    val updated: Long,
) {
    /** Only the local provider's artifacts, served by this server, make a tile in v1. */
    val local: Boolean get() = provider == "local" && url.startsWith(ARTIFACT_PATH)

    companion object {
        const val ARTIFACT_PATH = "/api/experimental/artifacts/"

        /** One list item, or null when it lacks what the app uses. */
        fun read(item: JsonObject): ArtifactInfo? {
            val id = item.str("id")?.takeIf { it.startsWith("art_") } ?: return null
            val title = item.str("title") ?: return null
            val url = item.str("url") ?: return null
            val time = item["time"] as? JsonObject ?: return null
            val updated = (time["updated"] as? JsonPrimitive)?.longOrNull ?: return null
            return ArtifactInfo(
                id = id,
                provider = item.str("provider").orEmpty(),
                sessionId = item.str("sessionID"),
                title = title,
                entry = item.str("entry").orEmpty(),
                url = url,
                created = (time["created"] as? JsonPrimitive)?.longOrNull ?: updated,
                updated = updated,
            )
        }
    }
}

/** The card's artifact tile: the artifact to open and its title. Saved with the card, so Nothing new keeps it. */
@Serializable
data class ArtifactTile(
    val id: String,
    val title: String,
)

/** What the list route answered (artifacts-proposal.md §1.5). */
sealed interface ArtifactListAnswer {
    /** `200` with a list that decodes. */
    data class On(
        val items: List<ArtifactInfo>,
    ) : ArtifactListAnswer

    /** `403`: artifacts are off for the location. The message names the key; its wording isn't relied on. */
    data object Off : ArtifactListAnswer

    /** A bare `404`, `405`, `400`, or a `200` that doesn't decode: the route or its shape has changed. */
    data object Changed : ArtifactListAnswer

    /** `401`. */
    data object SignedOut : ArtifactListAnswer

    /** `5xx`, a timeout, unreachable: the last state stays. */
    data object Unknown : ArtifactListAnswer

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** [status] is null when no answer came (unreachable, timed out). */
        fun of(
            status: Int?,
            body: String?,
        ): ArtifactListAnswer =
            when (status) {
                null -> Unknown
                200 -> decode(body)?.let(::On) ?: Changed
                403 -> Off
                401 -> SignedOut
                // OpenCode's own errors carry a `_tag`; a route that isn't declared answers with an empty body.
                404 -> if (tagged(body)) Unknown else Changed
                400, 405 -> Changed
                else -> Unknown
            }

        private fun decode(body: String?): List<ArtifactInfo>? {
            val root = body?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() } as? JsonObject ?: return null
            val data = root["data"] as? JsonArray ?: return null
            return data.map { (it as? JsonObject)?.let(ArtifactInfo::read) ?: return null }
        }

        private fun tagged(body: String?): Boolean =
            (body?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() } as? JsonObject)?.str("_tag") != null
    }
}

/**
 * Whether the server declares what Hex Code builds on (app-plan.md, Artifacts (M8) › Drift): the list and view routes,
 * and `Artifact.Info` with `id`, `title`, `url` and `time.updated`. Every OpenCode from 2.2.4 declares them whether
 * artifacts are on or off. A missing route never blocks the main screen; it only switches the feature off.
 */
object ArtifactsDeclared {
    private const val LIST = "/api/experimental/artifacts"
    private const val VIEW = "/api/experimental/artifacts/{artifactID}/view/*"

    fun of(openapi: JsonObject): Boolean {
        val paths = openapi["paths"] as? JsonObject ?: return false
        if (!get(paths, LIST) || !get(paths, VIEW)) return false
        val schemas = (openapi["components"] as? JsonObject)?.get("schemas") as? JsonObject ?: return false
        val info = schemas["Artifact.Info"] as? JsonObject ?: return false
        val properties = info["properties"] as? JsonObject ?: return false
        if (listOf("id", "title", "url", "time").any { it !in properties }) return false
        val time = resolve(properties["time"], schemas) ?: return false
        return (time["properties"] as? JsonObject)?.containsKey("updated") == true
    }

    private fun get(
        paths: JsonObject,
        path: String,
    ): Boolean = (paths[path] as? JsonObject)?.containsKey("get") == true

    /** A schema, or the one its `$ref` names. */
    private fun resolve(
        element: JsonElement?,
        schemas: JsonObject,
    ): JsonObject? {
        val schema = element as? JsonObject ?: return null
        val ref = schema.str("\$ref") ?: return schema
        return schemas[ref.substringAfterLast('/')] as? JsonObject
    }
}

/**
 * The card's artifact (app-plan.md, Artifacts (M8) › Using it; m6-proposal.md Open question 3): the newest artifact of
 * today's session or one of its known subagents whose `time.updated` is at or after the start of the latest run. At
 * most one; only the local provider's; none without a `sessionID`, or before any run.
 */
object NewestArtifact {
    fun of(
        items: List<ArtifactInfo>,
        rootId: String,
        children: Set<String>,
        runStart: Long?,
    ): ArtifactTile? {
        runStart ?: return null
        return items
            .filter { it.local && it.updated >= runStart }
            .filter { it.sessionId == rootId || it.sessionId in children }
            .maxByOrNull { it.updated }
            ?.let { ArtifactTile(it.id, it.title) }
    }
}

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
