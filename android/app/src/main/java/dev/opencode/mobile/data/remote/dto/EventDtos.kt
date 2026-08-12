package dev.opencode.mobile.data.remote.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class EventEnvelopeDto(
    val payload: EventPayloadDto? = null,
    val type: String? = null,
    val properties: JsonObject? = null,
    val data: JsonObject? = null,
)

@Serializable
data class EventPayloadDto(
    val type: String,
    val properties: JsonObject = JsonObject(emptyMap()),
)
