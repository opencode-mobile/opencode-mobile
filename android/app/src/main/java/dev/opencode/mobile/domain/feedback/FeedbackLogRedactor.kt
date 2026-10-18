package dev.opencode.mobile.domain.feedback

import dev.opencode.mobile.data.local.ServerConnectionDefaults
import dev.opencode.mobile.domain.connection.ConnectionProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI

/**
 * Second redaction pass for logs attached to a feedback report (#721). The diagnostic buffer
 * already strips credentials; a report must also never carry the server address, so URLs,
 * IP addresses, `host:port` pairs and the active profile's host, username and name go too.
 */
object FeedbackLogRedactor {
    const val HOST = "[REDACTED-HOST]"
    const val URL = "[REDACTED-URL]"
    const val VALUE = "[REDACTED]"

    private val patterns =
        listOf(
            Regex("""\b[A-Za-z][A-Za-z0-9+.-]*://[^\s"'<>]+""") to URL,
            Regex("""\[[0-9A-Fa-f:.]+](?::\d{1,5})?""") to HOST,
            Regex("""\b\d{1,3}(?:\.\d{1,3}){3}(?::\d{1,5})?\b""") to HOST,
            Regex("""\b[A-Za-z][A-Za-z0-9-]*(?:\.[A-Za-z0-9-]+)*:\d{2,5}\b""") to HOST,
            // "(port 4403)" in socket errors, after the address itself was masked.
            Regex("""(?i)\b(port\s*[=:]?\s*)\d{1,5}\b""") to "$1$VALUE",
        )

    private val json = Json { ignoreUnknownKeys = true }

    /** Profile values to hide wherever they appear. The default username is too common to strip. */
    fun knownValues(profile: ConnectionProfile?): List<String> {
        if (profile == null) return emptyList()
        val hosts =
            (listOf(profile.baseUrl) + profile.serverUrls).mapNotNull { url ->
                runCatching { URI(url.trim()).host }.getOrNull()?.trim('[', ']')
            }
        val username = profile.username.takeIf { !it.equals(ServerConnectionDefaults.USERNAME, ignoreCase = true) }
        return (hosts + listOfNotNull(username, profile.name))
            .map(String::trim)
            .filter { it.length >= 3 }
            .distinct()
            .sortedByDescending { it.length }
    }

    fun redact(
        text: String,
        known: List<String> = emptyList(),
    ): String {
        var result = text
        for ((regex, replacement) in patterns) {
            result = regex.replace(result, replacement)
        }
        for (value in known) {
            result = result.replace(value, VALUE, ignoreCase = true)
        }
        return result
    }

    /** Redacts the `message` of every entry line of a diagnostic JSONL export. */
    fun redactJsonl(
        jsonl: String,
        known: List<String> = emptyList(),
    ): String =
        jsonl.lineSequence().joinToString("\n") { line ->
            if (line.isBlank()) return@joinToString line
            val entry = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()
            val message = entry?.get("message")?.jsonPrimitive?.contentOrNull
            if (entry == null || message == null) {
                redact(line, known)
            } else {
                JsonObject(entry + ("message" to JsonPrimitive(redact(message, known)))).toString()
            }
        }
}
