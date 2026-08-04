package dev.opencode.mobile.domain.diagnostic

import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class DiagnosticLogArea(
    val wireValue: String,
) {
    Sse("sse"),
    Permission("permission"),
    Question("question"),
    Session("session"),
}

enum class DiagnosticLogLevel(
    val wireValue: String,
) {
    Debug("debug"),
    Info("info"),
    Warning("warning"),
    Error("error"),
}

@Serializable
data class DiagnosticLogEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestampEpochMilliseconds: Long,
    val area: String,
    val level: String,
    val message: String,
)

data class DiagnosticLogSnapshot(
    val entries: List<DiagnosticLogEntry>,
    val droppedBySoftCap: Int,
    val windowMinutes: Int,
    val softCap: Int,
)

object DiagnosticLogRedactor {
    const val MAX_MESSAGE_LENGTH = 500

    private val patterns =
        listOf(
            Regex("""(?i)\bBasic\s+[A-Za-z0-9+/=_-]+""") to "Basic [REDACTED]",
            Regex("""(?i)\bBearer\s+\S+""") to "Bearer [REDACTED]",
            Regex("""(?i)(password\s*[=:]\s*)\S+""") to "$1[REDACTED]",
            Regex("""(?i)(token\s*[=:]\s*)\S+""") to "$1[REDACTED]",
            Regex("""(?i)(authorization\s*[=:]\s*).+""") to "$1[REDACTED]",
            Regex("""(?i)(OPENCODE_SERVER_PASSWORD\s*[=:]\s*)\S+""") to "$1[REDACTED]",
        )

    fun redact(message: String): String {
        var result = message
        for ((regex, replacement) in patterns) {
            result = regex.replace(result, replacement)
        }
        return if (result.length > MAX_MESSAGE_LENGTH) {
            result.take(MAX_MESSAGE_LENGTH) + "…"
        } else {
            result
        }
    }
}

class DiagnosticLogBuffer(
    private val clockEpochMilliseconds: () -> Long = System::currentTimeMillis,
) {
    private val lock = ReentrantLock()
    private val entries = ArrayDeque<DiagnosticLogEntry>()
    private var droppedBySoftCap = 0

    fun append(
        area: DiagnosticLogArea,
        level: DiagnosticLogLevel,
        message: String,
    ) {
        val entry =
            DiagnosticLogEntry(
                timestampEpochMilliseconds = clockEpochMilliseconds(),
                area = area.wireValue,
                level = level.wireValue,
                message = DiagnosticLogRedactor.redact(message),
            )
        lock.withLock {
            entries.addLast(entry)
            evictLocked(now = entry.timestampEpochMilliseconds)
        }
    }

    fun snapshot(): DiagnosticLogSnapshot =
        lock.withLock {
            val now = clockEpochMilliseconds()
            evictLocked(now = now)
            DiagnosticLogSnapshot(
                entries = entries.toList(),
                droppedBySoftCap = droppedBySoftCap,
                windowMinutes = WINDOW_MINUTES,
                softCap = SOFT_CAP,
            )
        }

    fun clear() {
        lock.withLock {
            entries.clear()
            droppedBySoftCap = 0
        }
    }

    fun exportJsonl(): String {
        val snapshot = snapshot()
        val lines = ArrayList<String>(snapshot.entries.size + 1)
        lines +=
            json.encodeToString(
                DiagnosticExportMeta(
                    windowMinutes = snapshot.windowMinutes,
                    softCap = snapshot.softCap,
                    droppedBySoftCap = snapshot.droppedBySoftCap,
                    entryCount = snapshot.entries.size,
                    exportedAtEpochMilliseconds = clockEpochMilliseconds(),
                ),
            )
        for (entry in snapshot.entries) {
            lines +=
                json.encodeToString(
                    DiagnosticExportEntry(
                        id = entry.id,
                        timestampEpochMilliseconds = entry.timestampEpochMilliseconds,
                        area = entry.area,
                        level = entry.level,
                        message = entry.message,
                    ),
                )
        }
        return lines.joinToString(separator = "\n", postfix = "\n")
    }

    private fun evictLocked(now: Long) {
        val cutoff = now - WINDOW_MILLISECONDS
        while (entries.isNotEmpty() && entries.first().timestampEpochMilliseconds < cutoff) {
            entries.removeFirst()
        }
        while (entries.size > SOFT_CAP) {
            entries.removeFirst()
            droppedBySoftCap += 1
        }
    }

    companion object {
        const val WINDOW_MINUTES = 5
        const val WINDOW_MILLISECONDS: Long = WINDOW_MINUTES * 60L * 1_000L
        const val SOFT_CAP = 2_000

        private val json =
            Json {
                encodeDefaults = true
                ignoreUnknownKeys = true
            }

        /** Process-lifetime buffer shared by DI and non-injected call sites. */
        val shared = DiagnosticLogBuffer()
    }
}

@Serializable
private data class DiagnosticExportMeta(
    val type: String = "meta",
    val schemaVersion: Int = 1,
    val windowMinutes: Int,
    val softCap: Int,
    val droppedBySoftCap: Int,
    val entryCount: Int,
    val exportedAtEpochMilliseconds: Long,
)

@Serializable
private data class DiagnosticExportEntry(
    val type: String = "entry",
    val id: String,
    val timestampEpochMilliseconds: Long,
    val area: String,
    val level: String,
    val message: String,
)
