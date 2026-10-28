package dev.opencode.mobile.domain.feedback

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Builds the `feedback/{reportId}` document from `shared/feedback/report.example.json`,
 * without `createdAt` (a server timestamp transform sets it). Map strings are capped at
 * [MAX_FIELD_LENGTH] as the rules require.
 */
object FeedbackPayloadBuilder {
    const val MAX_FIELD_LENGTH = 64

    fun document(
        report: FeedbackReport,
        uid: String,
    ): JsonObject {
        val metadata = report.metadata
        return buildJsonObject {
            put("uid", uid)
            put("kind", report.kind.wireValue)
            put("message", report.message.take(FeedbackReport.MAX_MESSAGE_LENGTH))
            putJsonObject("app") {
                put("platform", metadata.platform)
                put("version", metadata.version.capped())
                put("build", metadata.build.capped())
                put("osVersion", metadata.osVersion.capped())
                put("device", metadata.device.capped())
                put("locale", metadata.locale.capped())
            }
            putJsonObject("context") {
                put("screen", metadata.screen.wireValue)
                metadata.serverProtocol?.let { put("serverProtocol", it.capped()) }
                metadata.serverVersion?.let { put("serverVersion", it.capped()) }
                put("engine", metadata.engine)
            }
            putJsonArray("attachments") {
                report.attachments.forEach { attachment ->
                    add(
                        buildJsonObject {
                            put("name", attachment.name)
                            put("contentType", attachment.contentType)
                            put("size", attachment.size)
                        },
                    )
                }
            }
        }
    }

    private fun String.capped(): String = take(MAX_FIELD_LENGTH)
}

/** Firestore REST `Value` encoding of plain JSON. */
object FirestoreValues {
    fun fields(document: JsonObject): JsonObject = JsonObject(document.mapValues { (_, value) -> value(value) })

    fun value(element: JsonElement): JsonObject =
        when (element) {
            is JsonNull -> buildJsonObject { put("nullValue", JsonNull) }
            is JsonObject -> buildJsonObject { put("mapValue", buildJsonObject { put("fields", fields(element)) }) }
            is JsonArray ->
                buildJsonObject {
                    put("arrayValue", buildJsonObject { put("values", buildJsonArray { element.forEach { add(value(it)) } }) })
                }
            is JsonPrimitive ->
                when {
                    element.isString -> buildJsonObject { put("stringValue", element.content) }
                    element.content == "true" || element.content == "false" ->
                        buildJsonObject { put("booleanValue", element.content == "true") }
                    element.longOrNull != null -> buildJsonObject { put("integerValue", element.content) }
                    else -> buildJsonObject { put("doubleValue", element) }
                }
        }
}
