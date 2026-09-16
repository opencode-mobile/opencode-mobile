package dev.opencode.mobile.data.remote

import kotlinx.serialization.json.Json

/**
 * SSE `data:` line parser matching iOS [SSEDataLineParser]: flush each complete JSON
 * payload without waiting for a blank event separator.
 */
internal class SseDataLineParser {
    private val dataBuffer = StringBuilder()

    fun ingest(line: String): List<String> {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) {
            return finish()
        }
        if (trimmed.startsWith(":")) {
            // Keep-alive comments are not events. The stream loop still counts
            // the received line as activity so a quiet socket is not torn down.
            return emptyList()
        }
        if (trimmed.startsWith("data:")) {
            val payload = trimmed.removePrefix("data:").trimStart()
            if (dataBuffer.isNotEmpty()) {
                dataBuffer.append('\n')
            }
            dataBuffer.append(payload)
            return if (isCompleteJsonPayload(dataBuffer.toString())) finish() else emptyList()
        }
        if (trimmed.startsWith("{")) {
            val payloads = finish().toMutableList()
            payloads.add(trimmed)
            return payloads
        }
        return emptyList()
    }

    fun finish(): List<String> {
        if (dataBuffer.isEmpty()) return emptyList()
        val payload = dataBuffer.toString()
        dataBuffer.clear()
        return listOf(payload)
    }

    private fun isCompleteJsonPayload(payload: String): Boolean = runCatching { Json.parseToJsonElement(payload) }.isSuccess
}
