package dev.opencode.mobile.data.remote.dto

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Serializable
data class CommandDto(
    val name: String,
    val description: String? = null,
    val agent: String? = null,
    val model: String? = null,
    val source: String? = null,
    @Serializable(with = LenientOptionalStringSerializer::class)
    val template: String? = null,
    val subtask: Boolean? = null,
    val hints: List<String> = emptyList(),
)

@OptIn(ExperimentalSerializationApi::class)
internal object LenientOptionalStringSerializer : KSerializer<String?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("LenientOptionalString", PrimitiveKind.STRING).nullable

    override fun deserialize(decoder: Decoder): String? {
        val jsonDecoder =
            decoder as? JsonDecoder
                ?: return decoder.decodeNullableSerializableValue(String.serializer())
        return when (val element = jsonDecoder.decodeJsonElement()) {
            JsonNull -> null
            is JsonPrimitive -> element.contentOrNull
            else -> null
        }
    }

    override fun serialize(
        encoder: Encoder,
        value: String?,
    ) {
        if (value == null) {
            encoder.encodeNull()
        } else {
            encoder.encodeString(value)
        }
    }
}

@Serializable
data class RunCommandRequestDto(
    val command: String,
    val arguments: String,
    val agent: String? = null,
    val model: String? = null,
    val variant: String? = null,
    val parts: List<CommandFilePartDto> = emptyList(),
)

@Serializable
data class CommandFilePartDto(
    val type: String = "file",
    val mime: String,
    val url: String,
    val filename: String? = null,
    val id: String? = null,
)

@Serializable
data class V2RunCommandRequestDto(
    val command: String,
    val arguments: String? = null,
    val agent: String? = null,
    val model: V2ModelRefDto? = null,
    val files: List<V2PromptFileDto>? = null,
    val delivery: String,
)
