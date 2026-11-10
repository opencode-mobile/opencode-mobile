package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.R
import dev.opencode.mobile.domain.text.UiText
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Timeline text helpers: lists of app text and server content joined without concatenation. */
internal object TimelineText {
    /** `a · b · c` through the `timeline_list_separator` pattern; null when [parts] is empty. */
    fun joined(parts: List<UiText>): UiText? =
        parts.reduceOrNull { acc, next -> UiText.resource(R.string.timeline_list_separator, acc, next) }

    fun verbatim(value: String?): UiText? = value?.let(UiText::verbatim)
}

/**
 * Saves [UiText] in a navigation key (the tool group route), so the route resolves its text
 * in the current language when it is shown again. Arguments are strings, numbers or nested
 * [UiText]; anything else is saved as its string form.
 */
object UiTextSerializer : KSerializer<UiText> {
    private val delegate = UiTextSurrogate.serializer()

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(
        encoder: Encoder,
        value: UiText,
    ) = encoder.encodeSerializableValue(delegate, UiTextSurrogate.from(value))

    override fun deserialize(decoder: Decoder): UiText = decoder.decodeSerializableValue(delegate).toUiText()
}

@Serializable
private sealed class UiTextSurrogate {
    @Serializable
    @SerialName("resource")
    data class Resource(
        val id: Int,
        val args: List<UiTextArgSurrogate>,
    ) : UiTextSurrogate()

    @Serializable
    @SerialName("plural")
    data class Plural(
        val id: Int,
        val count: Int,
        val args: List<UiTextArgSurrogate>,
    ) : UiTextSurrogate()

    @Serializable
    @SerialName("verbatim")
    data class Verbatim(
        val value: String,
    ) : UiTextSurrogate()

    fun toUiText(): UiText =
        when (this) {
            is Resource -> UiText.Resource(id, args.map { it.value() })
            is Plural -> UiText.Plural(id, count, args.map { it.value() })
            is Verbatim -> UiText.Verbatim(value)
        }

    companion object {
        fun from(text: UiText): UiTextSurrogate =
            when (text) {
                is UiText.Resource -> Resource(text.id, text.args.map(UiTextArgSurrogate::from))
                is UiText.Plural -> Plural(text.id, text.count, text.args.map(UiTextArgSurrogate::from))
                is UiText.Verbatim -> Verbatim(text.value)
            }
    }
}

@Serializable
private sealed class UiTextArgSurrogate {
    @Serializable
    @SerialName("string")
    data class Str(
        val value: String,
    ) : UiTextArgSurrogate()

    @Serializable
    @SerialName("int")
    data class IntArg(
        val value: Int,
    ) : UiTextArgSurrogate()

    @Serializable
    @SerialName("long")
    data class LongArg(
        val value: Long,
    ) : UiTextArgSurrogate()

    @Serializable
    @SerialName("text")
    data class Text(
        val value: UiTextSurrogate,
    ) : UiTextArgSurrogate()

    fun value(): Any =
        when (this) {
            is Str -> value
            is IntArg -> value
            is LongArg -> value
            is Text -> value.toUiText()
        }

    companion object {
        fun from(arg: Any): UiTextArgSurrogate =
            when (arg) {
                is UiText -> Text(UiTextSurrogate.from(arg))
                is Int -> IntArg(arg)
                is Long -> LongArg(arg)
                else -> Str(arg.toString())
            }
    }
}
