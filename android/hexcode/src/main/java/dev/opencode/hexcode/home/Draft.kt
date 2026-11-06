package dev.opencode.hexcode.home

/**
 * Where one chunk of the draft is: its take is still recording, it waits for the speech model, the phone is turning it
 * into text, or it has text.
 */
sealed interface ChunkState {
    data object Listening : ChunkState

    /** Waiting for speech: the take ended before the speech model was ready; its audio is kept until then. */
    data object Waiting : ChunkState

    data object Transcribing : ChunkState

    data class Text(
        val text: String,
    ) : ChunkState

    /**
     * "Couldn't turn this take into text": the engine failed on the take, or the speech model didn't load. Kept to
     * re-record or delete; with its audio kept, Try again ([retry]) turns the same audio into text.
     */
    data class Failed(
        val retry: Boolean = false,
    ) : ChunkState
}

/** One take in the draft. [id] stays the same while the chunk moves from Listening to text. */
data class Chunk(
    val id: Long,
    val state: ChunkState,
    val durationMs: Long = 0,
) {
    val text: String? get() = (state as? ChunkState.Text)?.text

    /** It can be tapped to re-record or delete it: it has its text, or the engine failed on it. */
    val selectable: Boolean get() = state is ChunkState.Text || state is ChunkState.Failed
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

    /** A take waits for the speech model. */
    val isWaiting: Boolean get() = chunks.any { it.state == ChunkState.Waiting }

    /** A chunk failed with its audio kept, so Try again can turn it into text. */
    val canRetry: Boolean get() = chunks.any { (it.state as? ChunkState.Failed)?.retry == true }

    /** Every chunk has its text, so the draft could be sent, asked or set as a goal. */
    val isSettled: Boolean get() = !isListening && !isTranscribing && !isWaiting

    /** Clear can empty the draft: nothing records or is being turned into text (takes waiting for the model can go). */
    val canClear: Boolean get() = !isListening && !isTranscribing

    /** The chunks as shown, with the live take in place of the chunk it re-records, or after the others. */
    val shown: List<Chunk>
        get() {
            val take = live ?: return chunks
            val at = rerecording
            return if (at != null && at in chunks.indices) chunks.toMutableList().also { it[at] = take } else chunks + take
        }

    /** The characters the running text shows: the chunks' text, a space between them. */
    val characters: Int get() = shown.sumOf { it.text?.length ?: 0 } + (shown.size - 1).coerceAtLeast(0)

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

    /** A chunk with [text] already in it, after the others: a tapped Next (app-plan.md, Tapping Next). */
    fun withText(text: String): Draft {
        val words = text.trim()
        if (words.isEmpty()) return this
        return copy(chunks = chunks + Chunk(nextId, ChunkState.Text(words)), nextId = nextId + 1, selected = null)
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

    /** The engine failed on take [id]: it stays, as a failed chunk, to re-record or delete; [retry] when its audio is kept. */
    fun failed(
        id: Long,
        retry: Boolean = false,
    ): Draft = withState(id, ChunkState.Failed(retry))

    /** Take [id] waits for the speech model. */
    fun waiting(id: Long): Draft = withState(id, ChunkState.Waiting)

    /** Take [id] is being turned into text. */
    fun transcribing(id: Long): Draft = withState(id, ChunkState.Transcribing)

    /** The speech model didn't load: every waiting take fails, keeping its audio for Try again. */
    fun waitingFailed(): Draft =
        copy(
            chunks =
                chunks.map {
                    if (it.state ==
                        ChunkState.Waiting
                    ) {
                        it.copy(state = ChunkState.Failed(retry = true))
                    } else {
                        it
                    }
                },
        )

    /** [earlier] back as it was before Send, followed by anything recorded since. */
    fun restored(earlier: Draft): Draft = copy(chunks = earlier.chunks + chunks, selected = null)

    /** Try again: failed takes with their audio wait for the model again. */
    fun retryFailed(): Draft =
        copy(
            chunks = chunks.map { if ((it.state as? ChunkState.Failed)?.retry == true) it.copy(state = ChunkState.Waiting) else it },
            selected = null,
        )

    /** The waiting takes, oldest first: the order they're turned into text. */
    val waitingIds: List<Long> get() = chunks.filter { it.state == ChunkState.Waiting }.map { it.id }.sorted()

    private fun withState(
        id: Long,
        state: ChunkState,
    ): Draft {
        val index = chunks.indexOfFirst { it.id == id }
        if (index < 0) return this
        return copy(chunks = chunks.toMutableList().also { it[index] = it[index].copy(state = state) })
    }

    /** Selects a chunk that has its text (or failed), or clears the selection when it is tapped again. */
    fun select(index: Int): Draft {
        if (live != null || chunks.getOrNull(index)?.selectable != true) return this
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
    fun clear(): Draft = if (canClear) Draft(nextId = nextId) else this

    companion object {
        /** Rows a long draft shows at once; earlier ones fold into "↑ N earlier". */
        const val MAX_ROWS = 8
    }
}

/** What the draft's label line says (app-plan.md, Dictation and The speech model). */
sealed interface DraftLabel {
    /** "Recording · 0:04". */
    data object Recording : DraftLabel

    /** "Transcribing on this phone…". */
    data object Transcribing : DraftLabel

    /** "Speech model loading…": a take waits. */
    data object SpeechLoading : DraftLabel

    /** "Downloading the speech model · 45%": a take waits for the first download. */
    data class SpeechDownloading(
        val percent: Int,
    ) : DraftLabel

    /** "Speech model didn't load": takes failed for it and can be tried again. */
    data object SpeechFailed : DraftLabel

    /** "Draft · 2 chunks · 0:12". */
    data object Summary : DraftLabel

    companion object {
        /** The first that applies: recording, transcribing, waiting for the model, the model failed, else the summary. */
        fun of(
            draft: Draft,
            speech: SpeechStatus,
        ): DraftLabel =
            when {
                draft.isListening -> Recording
                draft.isTranscribing -> Transcribing
                draft.isWaiting -> {
                    val downloaded = (speech as? SpeechStatus.Loading)?.downloaded
                    if (downloaded != null) SpeechDownloading((downloaded * 100).toInt().coerceIn(0, 99)) else SpeechLoading
                }
                draft.canRetry && speech == SpeechStatus.Failed -> SpeechFailed
                else -> Summary
            }
    }
}

/** Whether takes can be turned into text yet (app-plan.md, The speech model). */
sealed interface SpeechStatus {
    /** Loading, or downloading for the first time with [downloaded] (0 to 1) of it on the phone. */
    data class Loading(
        val downloaded: Double? = null,
    ) : SpeechStatus

    data object Ready : SpeechStatus

    /** The model didn't download or load. */
    data object Failed : SpeechStatus
}
