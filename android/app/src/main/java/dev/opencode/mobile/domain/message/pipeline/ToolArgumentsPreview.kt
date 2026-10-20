package dev.opencode.mobile.domain.message

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Tool arguments still streaming on a v2 server. `tool.input.delta` appends the
 * raw text to `input.preview`; settled arguments (`tool.called`, or a parsable
 * `tool.input.ended`) replace it. The raw text is partial JSON, so values are
 * read leniently: a string still being written counts as its prefix so far.
 */
object ToolArgumentsPreview {
    const val INPUT_KEY = "preview"

    /** Raw argument text while a pending or running tool has no settled arguments yet. */
    fun raw(tool: MessagePart.Tool): String? {
        if (tool.status != ToolStatus.Pending && tool.status != ToolStatus.Running) return null
        val input = tool.input ?: return null
        if (input.keys.any { it != INPUT_KEY }) return null
        val preview = input[INPUT_KEY] as? JsonPrimitive ?: return null
        return preview.content.takeIf { preview.isString }
    }

    fun isReceiving(tool: MessagePart.Tool): Boolean = raw(tool) != null

    /** The first non-empty top-level string among [keys] in the streaming arguments. */
    fun string(
        tool: MessagePart.Tool,
        vararg keys: String,
    ): String? {
        val fields = fields(raw(tool) ?: return null)
        return keys.firstNotNullOfOrNull { key -> fields[key]?.takeIf { it.isNotEmpty() } }
    }

    /** Store merge: a settled input drops the streaming preview instead of keeping it beside the arguments. */
    fun mergedInput(
        existing: JsonObject?,
        incoming: JsonObject?,
    ): JsonObject? {
        if (incoming == null || incoming.isEmpty()) return existing
        if (existing == null || existing.isEmpty()) return incoming
        val base = if (INPUT_KEY in incoming) existing else JsonObject(existing - INPUT_KEY)
        return JsonObject(base + incoming)
    }

    /** Previews grow one delta at a time and are read several times per frame. */
    @Volatile
    private var lastParsed: Pair<String, Map<String, String>>? = null

    private fun fields(raw: String): Map<String, String> {
        lastParsed?.let { (text, fields) -> if (text == raw) return fields }
        return partialStringFields(raw).also { lastParsed = raw to it }
    }

    /**
     * Top-level string fields of a JSON object that may be cut off anywhere.
     * Never throws: anything unreadable ends the scan with what was read.
     */
    fun partialStringFields(raw: String): Map<String, String> {
        val fields = linkedMapOf<String, String>()
        val reader = PartialJsonReader(raw)
        reader.skipWhitespace()
        if (!reader.consume('{')) return fields
        while (true) {
            reader.skipWhitespace()
            if (reader.atEnd || reader.peek() == '}') return fields
            if (reader.consume(',')) continue
            if (reader.peek() != '"') return fields
            val key = reader.readString()
            if (!key.closed) return fields
            reader.skipWhitespace()
            if (!reader.consume(':')) return fields
            reader.skipWhitespace()
            if (reader.atEnd) return fields
            if (reader.peek() == '"') {
                val value = reader.readString()
                fields[key.text] = value.text
                if (!value.closed) return fields
            } else {
                reader.skipValue()
            }
        }
    }

    private class PartialJsonReader(
        private val text: String,
    ) {
        private var index = 0

        val atEnd: Boolean get() = index >= text.length

        fun peek(): Char = text[index]

        fun consume(char: Char): Boolean {
            if (atEnd || text[index] != char) return false
            index++
            return true
        }

        fun skipWhitespace() {
            while (!atEnd && text[index].isWhitespace()) index++
        }

        class Result(
            val text: String,
            val closed: Boolean,
        )

        /** Reads a string starting at its opening quote; an unfinished escape is dropped. */
        fun readString(): Result {
            index++
            val out = StringBuilder()
            while (!atEnd) {
                val char = text[index]
                when (char) {
                    '"' -> {
                        index++
                        return Result(out.toString(), closed = true)
                    }
                    '\\' -> {
                        if (index + 1 >= text.length) break
                        val escaped = text[index + 1]
                        if (escaped == 'u') {
                            if (index + 6 > text.length) break
                            val code = text.substring(index + 2, index + 6).toIntOrNull(16) ?: break
                            out.append(code.toChar())
                            index += 6
                        } else {
                            out.append(
                                when (escaped) {
                                    'n' -> '\n'
                                    't' -> '\t'
                                    'r' -> '\r'
                                    'b' -> '\b'
                                    'f' -> '\u000C'
                                    else -> escaped
                                },
                            )
                            index += 2
                        }
                    }
                    else -> {
                        out.append(char)
                        index++
                    }
                }
            }
            index = text.length
            return Result(out.toString(), closed = false)
        }

        /** Skips a non-string value up to the `,` or `}` that ends it. */
        fun skipValue() {
            var depth = 0
            while (!atEnd) {
                when (text[index]) {
                    '"' -> {
                        readString()
                        continue
                    }
                    '{', '[' -> depth++
                    '}', ']' -> {
                        if (depth == 0) return
                        depth--
                    }
                    ',' -> if (depth == 0) return
                }
                index++
            }
        }
    }
}
