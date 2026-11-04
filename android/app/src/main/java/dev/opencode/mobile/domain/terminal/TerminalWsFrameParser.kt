package dev.opencode.mobile.domain.terminal

import dev.opencode.mobile.domain.json.serverPayloadJson
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

sealed interface TerminalWsFrame {
    data class Output(
        val text: String,
    ) : TerminalWsFrame

    data class Cursor(
        val value: String,
    ) : TerminalWsFrame

    data object Ignored : TerminalWsFrame
}

object TerminalWsFrameParser {
    private val json = serverPayloadJson()

    fun parseText(text: String): TerminalWsFrame = TerminalWsFrame.Output(text)

    fun parseBinary(bytes: ByteArray): TerminalWsFrame {
        if (bytes.isEmpty() || bytes[0] != 0.toByte()) return TerminalWsFrame.Ignored
        val raw = bytes.decodeToString(startIndex = 1)
        val cursor =
            runCatching {
                json
                    .parseToJsonElement(raw)
                    .jsonObject["cursor"]
                    ?.jsonPrimitive
                    ?.intOrNull
            }.getOrNull() ?: return TerminalWsFrame.Ignored
        if (cursor < 0) return TerminalWsFrame.Ignored
        return TerminalWsFrame.Cursor(cursor.toString())
    }
}
