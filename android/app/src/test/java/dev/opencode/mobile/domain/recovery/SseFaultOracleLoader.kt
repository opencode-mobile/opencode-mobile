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
            "v1-to-v2-probe-while-occupying",
            "remove-project-while-v1-live",
            "gap-during-initial-rest",
            "profile-a-to-b-to-a",
            "rest-slow-sse-wins",
            "rest-fail-retries-same-gap",
            "late-subscribe-open-gap",
            "clear-cache-drops-old-observation",
            "sse-parse-failed-opens-gap",
            "v1-instance-lease-lost-then-recovered",
            "v1-growth-then-incumbent-lease-lost",
            "old-generation-stays-writable",
            "path-lost-then-path-ready",
            "background-stop-then-foreground",
            "v1-profile-switch-does-not-reuse-directories",
            "v1-catch-up-waits-for-required-leases",
        )

    fun loadTrace(id: String): SseFaultTrace = json.decodeFromString(readResource("/traces/$id.json"))

    fun loadAllTraces(): List<SseFaultTrace> = traceIds.map(::loadTrace)

    fun loadSchemaObject(name: String): JsonObject = json.parseToJsonElement(readResource("/schema/$name")).jsonObject

    fun schemaRequiredFields(schema: JsonObject): List<String> = schema["required"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()

    private fun readResource(path: String): String {
        val stream =
            checkNotNull(SseFaultOracleLoader::class.java.getResourceAsStream(path)) {
                "Missing SSE fault oracle resource $path"
            }
        return stream.bufferedReader().use { it.readText() }
    }
}
