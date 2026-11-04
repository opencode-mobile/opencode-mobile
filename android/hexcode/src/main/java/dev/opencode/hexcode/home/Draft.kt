package dev.opencode.hexcode.home

/** Where one chunk of the draft is: its take is still recording, the phone is turning it into text, or it has text. */
sealed interface ChunkState {
    data object Listening : ChunkState

    data object Transcribing : ChunkState

    data class Text(
        val text: String,
    ) : ChunkState
}

/** One take in the draft. [id] stays the same while the chunk moves from Listening to text. */
data class Chunk(
    val id: Long,
    val state: ChunkState,
    val durationMs: Long = 0,
) {
    val text: String? get() = (state as? ChunkState.Text)?.text
}

/** A chunk deleted moments ago, kept so Undo can put it back where it was. */
data class DeletedChunk(
    val index: Int,
    val chunk: Chunk,
)

/**
 * The dictation draft: takes become chunks, each Listening → Transcribing → text. Releasing the talk button never
 * sends; nothing here sends at all yet. A re-record replaces its chunk once the new take ends, and keeps the old one if
 * the new take is dropped.
 */
data class Draft(
    val chunks: List<Chunk> = emptyList(),
    /** The chunk tapped for Delete or Re-record. */
    val selected: Int? = null,
    /** The take being recorded replaces this chunk when it ends. */
    val rerecording: Int? = null,
    /** The take being recorded, until it ends. */
    val live: Chunk? = null,
    val deleted: DeletedChunk? = null,
    private val nextId: Long = 1,
) {
    val isEmpty: Boolean get() = chunks.isEmpty() && live == null

    val isListening: Boolean get() = live != null

    val isTranscribing: Boolean get() = chunks.any { it.state == ChunkState.Transcribing }

    /** Every chunk has its text, so the draft could be sent, asked or set as a goal. */
    val isSettled: Boolean get() = !isListening && !isTranscribing

    /** The chunks as shown, with the live take in place of the chunk it re-records, or after the others. */
    val shown: List<Chunk>
        get() {
            val take = live ?: return chunks
            val at = rerecording
            return if (at != null && at in chunks.indices) chunks.toMutableList().also { it[at] = take } else chunks + take
        }

    /** More than 3 chunks or 220 characters: one row per chunk instead of running text. */
    val isLong: Boolean
        get() = shown.size > LONG_CHUNKS || shown.sumOf { it.text?.length ?: 0 } + (shown.size - 1).coerceAtLeast(0) > LONG_CHARACTERS

    val durationMs: Long get() = chunks.sumOf { it.durationMs }

    val text: String get() = chunks.mapNotNull { it.text }.joinToString(" ")

    /** Starts a take. With a chunk selected, the take re-records it. */
    fun startTake(): Draft {
        if (live != null) return this
        return copy(
            live = Chunk(nextId, ChunkState.Listening),
            nextId = nextId + 1,
            rerecording = selected,
            selected = null,
        )
    }

    /** The take ended: it becomes a chunk waiting for its text, in place of the chunk it re-records. */
    fun endTake(durationMs: Long): Draft {
        val take = live ?: return this
        val chunk = take.copy(state = ChunkState.Transcribing, durationMs = durationMs)
        val at = rerecording
        val next = if (at != null && at in chunks.indices) chunks.toMutableList().also { it[at] = chunk } else chunks + chunk
        return copy(chunks = next, live = null, rerecording = null)
    }

    /** The take is dropped: nothing of it is kept, and a re-recorded chunk keeps its old text. */
    fun dropTake(): Draft = copy(live = null, rerecording = null)

    /** The phone turned chunk [id] into [text]. A take with no speech in it leaves the draft. */
    fun transcribed(
        id: Long,
        text: String?,
    ): Draft {
        val index = chunks.indexOfFirst { it.id == id }
        if (index < 0) return this
        val words = text?.trim().orEmpty()
        if (words.isEmpty()) return copy(chunks = chunks.filterNot { it.id == id }, selected = null)
        return copy(chunks = chunks.toMutableList().also { it[index] = it[index].copy(state = ChunkState.Text(words)) })
    }

    /** Selects a chunk that has its text, or clears the selection when it is tapped again. */
    fun select(index: Int): Draft {
        if (live != null || chunks.getOrNull(index)?.text == null) return this
        return copy(selected = if (selected == index) null else index)
    }

    fun deselect(): Draft = copy(selected = null)

    /** Deletes the selected chunk; [undoDelete] puts it back. */
    fun deleteSelected(): Draft {
        val index = selected ?: return this
        val chunk = chunks.getOrNull(index) ?: return this
        return copy(chunks = chunks - chunk, selected = null, deleted = DeletedChunk(index, chunk))
    }

    fun undoDelete(): Draft {
        val gone = deleted ?: return this
        val next = chunks.toMutableList().also { it.add(gone.index.coerceIn(0, it.size), gone.chunk) }
        return copy(chunks = next, deleted = null)
    }

    /** Empties the draft. Not while a take records or a chunk waits for its text. */
    fun clear(): Draft = if (isSettled) Draft(nextId = nextId) else this

    companion object {
        const val LONG_CHUNKS = 3
        const val LONG_CHARACTERS = 220

        /** Rows a long draft shows at once; earlier ones fold into "↑ N earlier". */
        const val MAX_ROWS = 8
    }
}
