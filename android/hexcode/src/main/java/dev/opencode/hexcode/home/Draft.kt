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
    /**
     * A locked take cut at pauses (app-plan.md, Dictation › Locked takes): the text of its parts so far, shown while it
     * is still Listening and, after release, while its last part is transcribed. Empty for every other take.
     */
    val soFar: String = "",
) {
    val text: String? get() = (state as? ChunkState.Text)?.text

    /** The words the draft shows for it: its text, or a locked take's text so far. */
    val shownText: String get() = text ?: soFar

    /** It can be tapped to re-record or delete it: it has its text, or the engine failed on it. */
    val selectable: Boolean get() = state is ChunkState.Text || state is ChunkState.Failed
}

/** A chunk deleted moments ago, kept so Undo can put it back where it was. */
data class DeletedChunk(
    val index: Int,
    val chunk: Chunk,
)

/**
 * The dictation draft: takes become chunks, each Listening → Transcribing → text, and the images that go with them.
 * Releasing the talk button never sends. A re-record replaces its chunk once the new take ends, and keeps the old one if
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
    /** Screenshots and photos that go with the message (app-plan.md, Attach). */
    val images: List<DraftImage> = emptyList(),
    /** Files and videos, uploaded to the computer (app-plan.md, Attach files); with [images], at most [MAX_ATTACHMENTS]. */
    val files: List<DraftFile> = emptyList(),
    private val nextId: Long = 1,
) {
    val isEmpty: Boolean get() = chunks.isEmpty() && live == null && images.isEmpty() && files.isEmpty()

    /** An image is still being prepared: Send waits, reading "Preparing". */
    val isPreparing: Boolean get() = images.any { it.preparing }

    /** A file is still uploading: Send waits, reading "Uploading". */
    val isUploading: Boolean get() = files.any { it.uploading }

    /** Every running upload together, 0 to 100; null when none runs. */
    val uploadPercent: Int?
        get() {
            val running = files.filter { it.uploading }
            if (running.isEmpty()) return null
            val total = running.sumOf { it.total }
            if (total <= 0) return 0
            return (running.sumOf { it.sent.coerceAtMost(it.total) } * 100 / total).toInt().coerceIn(0, 100)
        }

    /** How many more images, videos and files this message can take. */
    val room: Int get() = (MAX_ATTACHMENTS - images.size - files.size).coerceAtLeast(0)

    val isListening: Boolean get() = live != null

    val isTranscribing: Boolean get() = chunks.any { it.state == ChunkState.Transcribing }

    /** A take waits for the speech model. */
    val isWaiting: Boolean get() = chunks.any { it.state == ChunkState.Waiting }

    /** A chunk failed with its audio kept, so Try again can turn it into text. */
    val canRetry: Boolean get() = chunks.any { (it.state as? ChunkState.Failed)?.retry == true }

    /**
     * Every chunk has its text, every image is prepared and every file uploaded, so the draft could be sent, asked or
     * set as a goal.
     */
    val isSettled: Boolean get() = !isListening && !isTranscribing && !isWaiting && !isPreparing && !isUploading

    /**
     * Send can go: everything is settled, and there is text, files (whatever the model), or images a model that
     * [seesImages] gets. A draft of only images sends an empty `text`; to a model that can't see them there would be
     * nothing to send.
     */
    fun canSend(seesImages: Boolean): Boolean =
        isSettled && (text.isNotBlank() || files.isNotEmpty() || (seesImages && images.isNotEmpty()))

    /** Adds [count] images, each preparing, up to the room left; their ids, in order, come back with the draft. */
    fun addImages(count: Int): Pair<Draft, List<Long>> {
        val ids = (0 until count.coerceAtMost(room)).map { nextId + it }
        val added = ids.map { DraftImage(it) }
        return copy(images = images + added, nextId = nextId + ids.size) to ids
    }

    /** Adds [count] files, each uploading, up to the room left; [video] from the photo picker. Their ids come back. */
    fun addFiles(
        count: Int,
        video: Boolean = false,
    ): Pair<Draft, List<Long>> {
        val ids = (0 until count.coerceAtMost(room)).map { nextId + it }
        val added = ids.map { DraftFile(it, video = video) }
        return copy(files = files + added, nextId = nextId + ids.size) to ids
    }

    /** File [id] is in the cache: its [name], type and size are known, and it uploads. */
    fun fileStaged(
        id: Long,
        name: String,
        mime: String,
        size: Long,
    ): Draft =
        withFile(id) {
            it.copy(name = name, mime = mime, total = size, video = it.video || mime.startsWith("video/"))
        }

    /** File [id] has [sent] of [total] bytes on the computer. */
    fun fileProgress(
        id: Long,
        sent: Long,
        total: Long,
    ): Draft = withFile(id) { it.copy(sent = sent, total = total) }

    /** File [id] is on the computer at [path]. */
    fun fileUploaded(
        id: Long,
        path: String,
    ): Draft = withFile(id) { it.copy(path = path, sent = it.total) }

    /** A file's ✕, or one that couldn't be copied or uploaded. */
    fun removeFile(id: Long): Draft = copy(files = files.filterNot { it.id == id })

    fun hasFile(id: Long): Boolean = files.any { it.id == id }

    private fun withFile(
        id: Long,
        change: (DraftFile) -> DraftFile,
    ): Draft = copy(files = files.map { if (it.id == id) change(it) else it })

    /** Image [id] is ready: its JPEG is [file], from a photo or a screenshot ([kind]). */
    fun imagePrepared(
        id: Long,
        file: java.io.File,
        kind: ImageKind,
    ): Draft = copy(images = images.map { if (it.id == id) it.copy(file = file, kind = kind) else it })

    /** The image's ✕, or an image that couldn't be read. */
    fun removeImage(id: Long): Draft = copy(images = images.filterNot { it.id == id })

    fun hasImage(id: Long): Boolean = images.any { it.id == id }

    /** Clear can empty the draft: nothing records or is being turned into text (takes waiting for the model can go). */
    val canClear: Boolean get() = !isListening && !isTranscribing

    /** The chunks as shown, with the live take in place of the chunk it re-records, or after the others. */
    val shown: List<Chunk>
        get() {
            val take = live ?: return chunks
            val at = rerecording
            return if (at != null && at in chunks.indices) chunks.toMutableList().also { it[at] = take } else chunks + take
        }

    /** The characters the running text shows: the chunks' text (a locked take's so far), a space between them. */
    val characters: Int get() = shown.sumOf { it.shownText.length } + (shown.size - 1).coerceAtLeast(0)

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
        return copy(chunks = chunks.toMutableList().also { it[index] = it[index].copy(state = ChunkState.Text(words), soFar = "") })
    }

    /**
     * Locked take [id]'s text so far: the text of its parts that have it, each part only once every part before it has
     * its text. Shown while the take records or, after release, waits for its last part. A take dropped meanwhile is
     * gone, and so is the text.
     */
    fun withSoFar(
        id: Long,
        text: String,
    ): Draft {
        val take = live
        if (take?.id == id) return copy(live = take.copy(soFar = text))
        val index = chunks.indexOfFirst { it.id == id && it.state == ChunkState.Transcribing }
        if (index < 0) return this
        return copy(chunks = chunks.toMutableList().also { it[index] = it[index].copy(soFar = text) })
    }

    /** The engine failed on take [id]: it stays, as a failed chunk, to re-record or delete; [retry] when its audio is kept. */
    fun failed(
        id: Long,
        retry: Boolean = false,
    ): Draft = withState(id, ChunkState.Failed(retry), soFar = "")

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

    /**
     * [earlier] back as it was before Send, followed by anything recorded or attached since. A message holds at most
     * [MAX_ATTACHMENTS]: the earlier draft's images and files come first (files with their uploaded paths), and any past
     * the limit are left out.
     */
    fun restored(earlier: Draft): Draft {
        val kept = (earlier.images + earlier.files + images + files).take(MAX_ATTACHMENTS)
        return copy(
            chunks = earlier.chunks + chunks,
            selected = null,
            images = kept.filterIsInstance<DraftImage>(),
            files = kept.filterIsInstance<DraftFile>(),
        )
    }

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
        soFar: String? = null,
    ): Draft {
        val index = chunks.indexOfFirst { it.id == id }
        if (index < 0) return this
        return copy(
            chunks = chunks.toMutableList().also { it[index] = it[index].copy(state = state, soFar = soFar ?: it[index].soFar) },
        )
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

        /** Images, videos and files per message, together (app-plan.md, Attach files › Limits). */
        const val MAX_ATTACHMENTS = 4
    }
}

/** What the draft's label line says (app-plan.md, Dictation, The speech model, and Speech line and dial). */
sealed interface DraftLabel {
    /** "Recording · 0:04". */
    data object Recording : DraftLabel

    /** "Transcribing on this phone…". */
    data object Transcribing : DraftLabel

    /** "Speech model loading…": a take waits while the model loads or warms up. */
    data object SpeechLoading : DraftLabel

    /** "Speech model not on this phone yet": a take waits for a download that hasn't started. */
    data object SpeechNotOnPhone : DraftLabel

    /**
     * "Speech model · 120 of 640 MB · 2 min left": a take waits for the first download. [minutesLeft] is null until the
     * speed is known, and 0 for "under a minute left".
     */
    data class SpeechDownloading(
        val doneMb: Long,
        val totalMb: Long,
        val minutesLeft: Long? = null,
    ) : DraftLabel {
        companion object {
            fun of(download: SpeechDownload): SpeechDownloading =
                SpeechDownloading(
                    doneMb = download.done / SpeechDownload.MB,
                    totalMb = download.total / SpeechDownload.MB,
                    minutesLeft = download.secondsLeft?.let(SpeechDownload::minutesLeft),
                )
        }
    }

    /** "Speech model · retrying in 4 s", then "· trying again…" ([seconds] 0): the connection dropped. */
    data class SpeechRetrying(
        val seconds: Int,
    ) : DraftLabel

    /** "Speech model didn't load": takes failed for it and can be tried again. */
    data object SpeechFailed : DraftLabel

    /** "Draft · 2 chunks · 0:12". */
    data object Summary : DraftLabel

    /** A line about the speech model: it shows the dial, and tapping it opens the speech model sheet. */
    val isSpeech: Boolean
        get() =
            this is SpeechLoading ||
                this is SpeechNotOnPhone ||
                this is SpeechDownloading ||
                this is SpeechRetrying ||
                this is SpeechFailed

    companion object {
        /** The first that applies: recording, transcribing, waiting for the model, the model failed, else the summary. */
        fun of(
            draft: Draft,
            speech: SpeechStatus,
        ): DraftLabel =
            when {
                draft.isListening -> Recording
                draft.isTranscribing -> Transcribing
                draft.isWaiting -> waiting(speech)
                draft.canRetry && speech == SpeechStatus.Failed -> SpeechFailed
                else -> Summary
            }

        private fun waiting(speech: SpeechStatus): DraftLabel {
            val loading = speech as? SpeechStatus.Loading ?: return SpeechLoading
            val download = loading.download
            return when (loading.stage) {
                SpeechStage.NotOnPhone -> SpeechNotOnPhone
                SpeechStage.Download ->
                    when {
                        download == null -> SpeechLoading
                        download.retryIn != null -> SpeechRetrying(download.retryIn)
                        else -> SpeechDownloading.of(download)
                    }
                SpeechStage.Load, SpeechStage.WarmUp -> SpeechLoading
            }
        }
    }
}

/** Whether takes can be turned into text yet (app-plan.md, The speech model). */
sealed interface SpeechStatus {
    /**
     * Not ready yet, so takes wait: in [stage], with the first download's progress in [download] while it runs. A
     * [download] alone means it's downloading.
     */
    data class Loading(
        val download: SpeechDownload? = null,
        val stage: SpeechStage = if (download != null) SpeechStage.Download else SpeechStage.Load,
    ) : SpeechStatus

    /** Loaded and warmed up. */
    data object Ready : SpeechStatus

    /** The model didn't download or load. */
    data object Failed : SpeechStatus
}

/** The steps to ready (app-plan.md, Speech line and dial): not on the phone yet, then download, load and warm up. */
enum class SpeechStage { NotOnPhone, Download, Load, WarmUp }

/**
 * The speech model's first download: [done] of [total] bytes on the phone, at [bytesPerSecond] with about
 * [secondsLeft] to go once the speed is known. While the connection is down, [retryIn] is the seconds to the next
 * attempt, and 0 while it's made.
 */
data class SpeechDownload(
    val done: Long,
    val total: Long,
    val secondsLeft: Long? = null,
    val bytesPerSecond: Long? = null,
    val retryIn: Int? = null,
) {
    /** The share on the phone, 0 to 1, for the dial. */
    val fraction: Float get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f

    companion object {
        /** Megabytes as the label counts them, as the model list does: 1,000,000 bytes. */
        const val MB = 1_000_000L

        /** Whole minutes rounded up, and 0 for "under a minute left". */
        fun minutesLeft(seconds: Long): Long = if (seconds < 60) 0 else (seconds + 59) / 60
    }
}
