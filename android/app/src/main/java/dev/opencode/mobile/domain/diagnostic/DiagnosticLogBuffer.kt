package dev.opencode.mobile.domain.diagnostic

import androidx.core.util.AtomicFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

enum class DiagnosticLogArea(
    val wireValue: String,
) {
    Sse("sse"),
    Permission("permission"),
    Question("question"),
    Session("session"),
    Chat("chat"),
    Hang("hang"),
    Connection("connection"),
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
    val retainMinutes: Int,
    val softCap: Int,
)

object DiagnosticLogRedactor {
    const val MAX_MESSAGE_LENGTH = 500

    private const val SECRET_KEYS =
        "password|passwd|pwd|secret|client_secret|token|access_token|refresh_token|id_token|" +
            "auth_token|api[_-]?key|apikey|authorization|cookie|set-cookie|private_key|ticket"

    /**
     * Secret shapes, most specific first. [dev.opencode.mobile.domain.feedback.FeedbackLogRedactor]
     * runs these too before it masks addresses, so both exports share one definition of "credential".
     */
    private val patterns =
        listOf(
            // URL userinfo: scheme://user:password@host
            Regex("""(?i)\b([a-z][a-z0-9+.-]*://)[^/\s:@]+:[^/\s@]+@""") to "$1[REDACTED]@",
            // Quoted JSON keys: "password":"x", "api_key": "x"
            Regex("""(?i)("(?:$SECRET_KEYS)"\s*:\s*")[^"]*(")""") to "$1[REDACTED]$2",
            // Query parameters: ?auth_token=x&ticket=y
            Regex("""(?i)([?&](?:$SECRET_KEYS)=)[^&\s"']+""") to "$1[REDACTED]",
            // Well-known key formats
            Regex("""\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\b""") to "[REDACTED-JWT]",
            Regex("""\bsk-[A-Za-z0-9_-]{8,}\b""") to "[REDACTED-KEY]",
            Regex("""\bgh[pousr]_[A-Za-z0-9]{20,}\b""") to "[REDACTED-KEY]",
            Regex("""\bgithub_pat_[A-Za-z0-9_]{20,}\b""") to "[REDACTED-KEY]",
            Regex("""\bAKIA[0-9A-Z]{16}\b""") to "[REDACTED-KEY]",
            Regex("""\bxox[baprs]-[A-Za-z0-9-]{10,}\b""") to "[REDACTED-KEY]",
            Regex("""(?i)\bBasic\s+[A-Za-z0-9+/=_-]+""") to "Basic [REDACTED]",
            Regex("""(?i)\bBearer\s+\S+""") to "Bearer [REDACTED]",
            Regex("""(?i)(authorization\s*[=:]\s*).+""") to "$1[REDACTED]",
            Regex("""(?i)(OPENCODE_SERVER_PASSWORD\s*[=:]\s*)\S+""") to "$1[REDACTED]",
            // key=value / key: value for every secret key name
            Regex("""(?i)\b((?:$SECRET_KEYS)\s*[=:]\s*)\S+""") to "$1[REDACTED]",
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
    private val file: File? = null,
    private val clockEpochMilliseconds: () -> Long = System::currentTimeMillis,
    private val persistDelayMilliseconds: Long? = 250L,
) {
    private val lock = ReentrantLock()
    private val entries = ArrayDeque<DiagnosticLogEntry>()
    private var droppedBySoftCap = 0
    private val persistExecutor =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "diagnostic-log").apply { isDaemon = true }
        }

    @Volatile
    private var persistGeneration = 0L
    private var persistScheduled = false

    init {
        if (file != null) {
            loadFromDisk()
        }
    }

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
        val shouldSchedule =
            lock.withLock {
                entries.addLast(entry)
                evictLocked(now = entry.timestampEpochMilliseconds)
                val schedule = file != null && persistDelayMilliseconds != null && !persistScheduled
                if (schedule) persistScheduled = true
                schedule
            }
        if (shouldSchedule) schedulePersist()
    }

    fun snapshot(): DiagnosticLogSnapshot =
        lock.withLock {
            val now = clockEpochMilliseconds()
            evictLocked(now = now)
            DiagnosticLogSnapshot(
                entries = entries.toList(),
                droppedBySoftCap = droppedBySoftCap,
                retainMinutes = RETAIN_MINUTES,
                softCap = SOFT_CAP,
            )
        }

    fun flushPersisted() {
        persistExecutor.submit { writeOnPersistThread() }.get()
    }

    fun clear() {
        persistExecutor
            .submit {
                lock.withLock {
                    entries.clear()
                    droppedBySoftCap = 0
                    persistScheduled = false
                    persistGeneration++
                }
                clearPersistedLocked()
            }.get()
    }

    fun exportJsonl(window: DiagnosticLogExportWindow = DiagnosticLogExportWindow.DEFAULT): String {
        val snapshot = snapshot()
        val now = clockEpochMilliseconds()
        val cutoff = now - window.milliseconds
        val exported = snapshot.entries.filter { it.timestampEpochMilliseconds >= cutoff }
        val lines = ArrayList<String>(exported.size + 1)
        lines +=
            json.encodeToString(
                DiagnosticExportMeta(
                    windowMinutes = window.minutes,
                    retainMinutes = snapshot.retainMinutes,
                    exportWindowMinutes = window.minutes,
                    softCap = snapshot.softCap,
                    droppedBySoftCap = snapshot.droppedBySoftCap,
                    entryCount = exported.size,
                    exportedAtEpochMilliseconds = now,
                ),
            )
        for (entry in exported) {
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

    private fun evictLocked(now: Long): Boolean {
        val beforeCount = entries.size
        val cutoff = now - RETAIN_MILLISECONDS
        while (entries.isNotEmpty() && entries.first().timestampEpochMilliseconds < cutoff) {
            entries.removeFirst()
        }
        while (entries.size > SOFT_CAP) {
            entries.removeFirst()
            droppedBySoftCap += 1
        }
        return entries.size != beforeCount
    }

    private fun loadFromDisk() {
        val store = file ?: return
        if (!store.exists()) return
        val loaded =
            runCatching {
                store.useLines { lines ->
                    lines
                        .mapNotNull { line ->
                            line
                                .takeIf(String::isNotBlank)
                                ?.let { encoded ->
                                    runCatching { json.decodeFromString<DiagnosticLogEntry>(encoded) }.getOrNull()
                                }
                        }.toList()
                }
            }.getOrDefault(emptyList())
        entries.clear()
        entries.addAll(loaded)
        droppedBySoftCap = 0
        evictLocked(now = clockEpochMilliseconds())
    }

    private fun schedulePersist() {
        val delay = persistDelayMilliseconds ?: return
        persistExecutor.schedule({ writeOnPersistThread() }, delay, TimeUnit.MILLISECONDS)
    }

    private fun writeOnPersistThread() {
        val snapshot: List<DiagnosticLogEntry>
        val generation: Long
        lock.withLock {
            snapshot = entries.toList()
            persistScheduled = false
            persistGeneration++
            generation = persistGeneration
        }
        writeEntries(snapshot, generation)
    }

    private fun writeEntries(
        snapshot: List<DiagnosticLogEntry>,
        generation: Long,
    ) {
        val store = file ?: return
        runCatching {
            val data =
                buildString {
                    snapshot.forEach { entry ->
                        append(json.encodeToString(entry))
                        append('\n')
                    }
                }.toByteArray()
            lock.withLock {
                if (generation != persistGeneration) return
            }
            store.parentFile?.mkdirs()
            val atomicFile = AtomicFile(store)
            val output = atomicFile.startWrite()
            try {
                output.write(data)
                atomicFile.finishWrite(output)
            } catch (failure: Throwable) {
                atomicFile.failWrite(output)
                throw failure
            }
        }
    }

    private fun clearPersistedLocked() {
        val store = file ?: return
        runCatching { AtomicFile(store).delete() }
    }

    /** Drain entries when handing off an early in-memory buffer to a file-backed one. */
    private fun drainForHandoff(): List<DiagnosticLogEntry> =
        lock.withLock {
            if (entries.isEmpty()) return@withLock emptyList()
            val drained = entries.toList()
            entries.clear()
            droppedBySoftCap = 0
            drained
        }

    /** Merge orphaned early entries into this buffer and persist when file-backed. */
    private fun absorb(incoming: List<DiagnosticLogEntry>) {
        if (incoming.isEmpty()) return
        lock.withLock {
            val byId = LinkedHashMap<String, DiagnosticLogEntry>()
            for (entry in entries) {
                byId[entry.id] = entry
            }
            for (entry in incoming) {
                byId.putIfAbsent(entry.id, entry)
            }
            val merged = byId.values.sortedBy { it.timestampEpochMilliseconds }
            entries.clear()
            entries.addAll(merged)
            evictLocked(now = clockEpochMilliseconds())
        }
        flushPersisted()
    }

    companion object {
        const val RETAIN_MINUTES = 60
        const val RETAIN_MILLISECONDS: Long = RETAIN_MINUTES * 60L * 1_000L
        const val SOFT_CAP = 10_000

        private val json =
            Json {
                encodeDefaults = true
                ignoreUnknownKeys = true
            }

        private val sharedLock = Any()

        @Volatile
        private var sharedInstance: DiagnosticLogBuffer? = null

        /**
         * Process-lifetime buffer shared by DI and non-injected call sites.
         *
         * May start as an in-memory buffer before Hilt binds the file-backed
         * singleton; [bindShared] absorbs any early entries into that singleton.
         */
        val shared: DiagnosticLogBuffer
            get() =
                synchronized(sharedLock) {
                    sharedInstance ?: DiagnosticLogBuffer().also { sharedInstance = it }
                }

        fun bindShared(buffer: DiagnosticLogBuffer) {
            synchronized(sharedLock) {
                val previous = sharedInstance
                if (previous != null && previous !== buffer) {
                    buffer.absorb(previous.drainForHandoff())
                }
                sharedInstance = buffer
            }
        }

        /** Test-only: clear the process-wide shared handle between cases. */
        internal fun resetSharedForTests() {
            synchronized(sharedLock) {
                sharedInstance = null
            }
        }

        fun fileIn(filesDirectory: File): File = File(filesDirectory, "debug/diagnostic-logs-v1.jsonl")
    }
}

@Serializable
private data class DiagnosticExportMeta(
    val type: String = "meta",
    val schemaVersion: Int = 1,
    val windowMinutes: Int,
    val retainMinutes: Int,
    val exportWindowMinutes: Int,
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
