package dev.opencode.mobile.theme

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable
data class OpenCodeTheme(
    @SerialName("\u0024schema")
    @Serializable(SchemaSerializer::class)
    val schema: String? = null,
    val name: String,
    val id: String,
    val light: ThemeVariant,
    val dark: ThemeVariant,
)

@Serializable
data class ThemeVariant(
    val palette: PaletteSeeds,
    val overrides: Map<String, String>? = null,
)

@Serializable
data class PaletteSeeds(
    val neutral: String,
    val ink: String,
    val primary: String,
    val success: String,
    val warning: String,
    val error: String,
    val info: String,
    val interactive: String? = null,
    val diffAdd: String? = null,
    val diffDelete: String? = null,
)

class SchemaSerializer : KSerializer<String?> {
    override val descriptor = PrimitiveSerialDescriptor("schema", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String? =
        try {
            decoder.decodeString()
        } catch (_: Exception) {
            null
        }

    override fun serialize(
        encoder: Encoder,
        value: String?,
    ) {
        if (value != null) encoder.encodeString(value)
    }
}

data class ResolvedTokens(
    val tokens: Map<String, String> = emptyMap(),
) {
    operator fun get(key: String): String = tokens[key] ?: "#000000"
}
