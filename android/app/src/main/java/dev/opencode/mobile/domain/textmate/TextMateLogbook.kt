package dev.opencode.mobile.domain.textmate

import androidx.core.util.AtomicFile
import dev.opencode.mobile.markdown.TextMateParseObservation
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

fun interface TextMateLogbookRecorder {
    fun submit(observations: List<TextMateParseObservation>)
}

object NoOpTextMateLogbookRecorder : TextMateLogbookRecorder {
    override fun submit(observations: List<TextMateParseObservation>) = Unit
}

@Serializable
data class TextMateLogbookEntry(
    val schemaVersion: Int = 1,
    val id: String,
    val timestampEpochMilliseconds: Long,
    val observation: TextMateParseObservation,
)

data class TextMateLogbookSnapshot(
    val entries: List<TextMateLogbookEntry>,
    val droppedEntries: Long,
    val storageError: String?,
)

class TextMateLogbook internal constructor(
    private val file: File,
    dispatcher: CoroutineDispatcher,
    private val clockEpochMilliseconds: () -> Long = System::currentTimeMillis,
) : TextMateLogbookRecorder {
    private sealed interface Command {
        data class Submit(
            val entries: List<TextMateLogbookEntry>,
        ) : Command

        data class Snapshot(
            val limit: Int,
            val result: CompletableDeferred<TextMateLogbookSnapshot>,
        ) : Command

        data class Clear(
            val result: CompletableDeferred<Unit>,
        ) : Command
    }

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    private val commands = Channel<Command>(capacity = MAX_PENDING_BATCHES)
    private val droppedEntries = AtomicLong(0)
    private var persistedEntryCount = 0
    private var initialized = false
    private var storageError: String? = null

    init {
        CoroutineScope(SupervisorJob() + dispatcher).launch {
            for (command in commands) {
                when (command) {
                    is Command.Submit -> append(command.entries)
                    is Command.Snapshot -> {
                        command.result.complete(snapshotOnWriter(command.limit))
                    }
                    is Command.Clear -> {
                        clearOnWriter()
                        command.result.complete(Unit)
                    }
                }
            }
        }
    }

    override fun submit(observations: List<TextMateParseObservation>) {
        if (observations.isEmpty()) return
        val timestamp = clockEpochMilliseconds()
        val entries =
            observations.map { observation ->
                TextMateLogbookEntry(
                    id = UUID.randomUUID().toString(),
                    timestampEpochMilliseconds = timestamp,
                    observation = observation,
                )
            }
        if (commands.trySend(Command.Submit(entries)).isFailure) {
            droppedEntries.addAndGet(entries.size.toLong())
        }
    }

    suspend fun snapshot(limit: Int = MAX_PERSISTED_ENTRIES): TextMateLogbookSnapshot {
        val result = CompletableDeferred<TextMateLogbookSnapshot>()
        commands.send(Command.Snapshot(limit.coerceIn(1, MAX_PERSISTED_ENTRIES), result))
        return result.await()
    }

    suspend fun clear() {
        val result = CompletableDeferred<Unit>()
        commands.send(Command.Clear(result))
        result.await()
    }

    private fun append(entries: List<TextMateLogbookEntry>) {
        ensureInitialized()
        runCatching {
            file.parentFile?.mkdirs()
            val payload =
                buildString {
                    entries.forEach { entry ->
                        append(json.encodeToString(entry))
                        append('\n')
                    }
                }
            file.appendText(payload)
            persistedEntryCount += entries.size
            if (persistedEntryCount > COMPACT_AT_ENTRIES || file.length() > MAX_FILE_BYTES) {
                compact()
            }
            storageError = null
        }.onFailure { failure ->
            storageError = failure.javaClass.simpleName
        }
    }

    private fun snapshotOnWriter(limit: Int): TextMateLogbookSnapshot {
        ensureInitialized()
        val entries =
            runCatching { readValidEntries().takeLast(limit).asReversed() }
                .onFailure { failure -> storageError = failure.javaClass.simpleName }
                .getOrDefault(emptyList())
        return TextMateLogbookSnapshot(
            entries = entries,
            droppedEntries = droppedEntries.get(),
            storageError = storageError,
        )
    }

    private fun clearOnWriter() {
        runCatching {
            AtomicFile(file).delete()
            persistedEntryCount = 0
            initialized = true
            storageError = null
            droppedEntries.set(0)
        }.onFailure { failure ->
            storageError = failure.javaClass.simpleName
        }
    }

    private fun ensureInitialized() {
        if (initialized) return
        persistedEntryCount = runCatching { readValidEntries().size }.getOrDefault(0)
        initialized = true
    }

    private fun compact() {
        val retained = readValidEntries().takeLast(MAX_PERSISTED_ENTRIES)
        val atomicFile = AtomicFile(file)
        val output = atomicFile.startWrite()
        try {
            val writer = output.bufferedWriter()
            retained.forEach { entry ->
                writer.append(json.encodeToString(entry))
                writer.newLine()
            }
            writer.flush()
            atomicFile.finishWrite(output)
            persistedEntryCount = retained.size
        } catch (failure: Throwable) {
            atomicFile.failWrite(output)
            throw failure
        }
    }

    private fun readValidEntries(): List<TextMateLogbookEntry> {
        if (!file.exists()) return emptyList()
        return file.useLines { lines ->
            lines.mapNotNull { line ->
                line
                    .takeIf(String::isNotBlank)
                    ?.let { encoded ->
                        runCatching { json.decodeFromString<TextMateLogbookEntry>(encoded) }.getOrNull()
                    }
            }.toList()
        }
    }

    companion object {
        const val MAX_PERSISTED_ENTRIES = 500
        private const val COMPACT_AT_ENTRIES = 550
        private const val MAX_PENDING_BATCHES = 64
        private const val MAX_FILE_BYTES = 2L * 1024L * 1024L

        fun fileIn(filesDirectory: File): File =
            File(filesDirectory, "debug/textmate-logbook-v1.jsonl")
    }
}
