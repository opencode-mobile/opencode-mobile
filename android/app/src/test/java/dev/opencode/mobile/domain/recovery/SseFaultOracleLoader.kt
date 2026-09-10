package dev.opencode.mobile.domain.recovery

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object SseFaultOracleLoader {
    val json =
        Json {
            ignoreUnknownKeys = false
            explicitNulls = false
        }

    val traceIds: List<String> =
        listOf(
            "android-brief-background-same-epoch-stale-latch",
            "cold-resume-double-epoch",
            "ios-profile-a-to-b-old-stream",
            "v1-one-target-silent-while-other-heartbeats",
            "add-project-while-v1-live",
        )

    fun loadTrace(id: String): SseFaultTrace = json.decodeFromString(readResource("/traces/$id.json"))

    fun loadAllTraces(): List<SseFaultTrace> = traceIds.map(::loadTrace)

    fun loadSchemaObject(name: String): JsonObject = json.parseToJsonElement(readResource("/schema/$name")).jsonObject

    fun schemaRequiredFields(schema: JsonObject): List<String> =
        schema["required"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()

    private fun readResource(path: String): String {
        val stream =
            checkNotNull(SseFaultOracleLoader::class.java.getResourceAsStream(path)) {
                "Missing SSE fault oracle resource $path"
            }
        return stream.bufferedReader().use { it.readText() }
    }
}
