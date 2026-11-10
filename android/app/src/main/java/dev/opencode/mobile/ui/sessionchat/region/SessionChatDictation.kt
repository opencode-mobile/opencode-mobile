package dev.opencode.mobile.ui.sessionchat.region

import dev.opencode.mobile.ui.sessionchat.ComposerDictationPhase
import dev.opencode.mobile.ui.sessionchat.ComposerDictationUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SessionChatDictationRegionState(
    val dictation: ComposerDictationUiState? = null,
)

/**
 * Session Chat region for the composer dictation card: listening, unavailable phases, model
 * download, the partial transcript and level, and the delayed Insert. An active card is
 * cancelled without inserting when the mode, background, or screen goes away.
 *
 * The region never edits composer text: Insert hands the transcript to [onCommit], and every
 * change is reported through [onChange] so the facade republishes.
 */
class SessionChatDictation(
    private val scope: CoroutineScope,
    private val nowMs: () -> Long,
    private val onChange: () -> Unit,
    private val onCommit: (transcript: String) -> Unit,
) {
    private val _state = MutableStateFlow(SessionChatDictationRegionState())
    val state: StateFlow<SessionChatDictationRegionState> = _state.asStateFlow()

    private var generation = 0
    private var insertJob: Job? = null

    /** Pause between Insert and the splice, so the card shows it is finishing. */
    var finishDelayMs: Long = 350

    private var dictation: ComposerDictationUiState?
        get() = _state.value.dictation
        set(value) {
            _state.value = SessionChatDictationRegionState(value)
        }

    fun begin(
        languageLabel: String,
        onDevice: Boolean,
    ) {
        invalidateInsert()
        dictation =
            ComposerDictationUiState(
                phase = ComposerDictationPhase.Listening,
                languageLabel = languageLabel,
                onDevice = onDevice,
                startedAtEpochMs = nowMs(),
            )
        onChange()
    }

    fun beginUnavailable(
        phase: ComposerDictationPhase,
        message: String?,
        languageLabel: String,
    ) {
        if (!phase.isUnavailable) return
        // A recognizer error can arrive after Insert has already been confirmed.
        // Dropping the card here would cancel the splice.
        if (dictation?.phase == ComposerDictationPhase.Finishing) return
        invalidateInsert()
        dictation =
            ComposerDictationUiState(
                phase = phase,
                languageLabel = languageLabel,
                startedAtEpochMs = nowMs(),
                errorMessage = message,
            )
        onChange()
    }

    /** Applies a model download update only while the card still shows the model. */
    fun modelDownload(
        phase: ComposerDictationPhase,
        progress: Float?,
    ) {
        val current = dictation ?: return
        if (!phase.isModelPhase || !current.phase.isModelPhase) return
        dictation = current.copy(phase = phase, downloadProgress = progress)
        onChange()
    }

    fun partial(
        committed: String,
        volatile: String,
    ) {
        val current = dictation ?: return
        if (current.phase != ComposerDictationPhase.Listening) return
        dictation = current.copy(committed = committed, volatile = volatile)
        onChange()
    }

    fun level(level: Float) {
        val current = dictation ?: return
        if (current.phase != ComposerDictationPhase.Listening) return
        dictation = current.copy(level = level.coerceIn(0f, 1f))
        onChange()
    }

    /** Listening ended but the transcript stays on the card so Insert still works. */
    fun stopped() {
        val current = dictation ?: return
        if (current.phase != ComposerDictationPhase.Listening) return
        dictation = current.copy(phase = ComposerDictationPhase.Stopped, level = 0f)
        onChange()
    }

    fun insert() {
        val current = dictation ?: return
        if (
            current.phase != ComposerDictationPhase.Listening &&
            current.phase != ComposerDictationPhase.Stopped &&
            current.phase != ComposerDictationPhase.Finishing
        ) {
            return
        }
        val transcript = current.committed + current.volatile
        if (transcript.trim().isEmpty()) return
        if (current.phase == ComposerDictationPhase.Finishing) return
        val generation = ++this.generation
        dictation = current.copy(phase = ComposerDictationPhase.Finishing)
        onChange()
        insertJob?.cancel()
        insertJob =
            scope.launch {
                delay(finishDelayMs)
                if (generation != this@SessionChatDictation.generation) return@launch
                val latest = dictation ?: return@launch
                if (latest.phase != ComposerDictationPhase.Finishing) return@launch
                onCommit(latest.committed + latest.volatile)
                dictation = null
                onChange()
            }
    }

    fun cancel() {
        invalidateInsert()
        if (dictation == null) return
        dictation = null
        onChange()
    }

    /** Mode change, background, or dismiss: cancels only a card that is up. */
    fun cancelIfActive() {
        if (dictation != null) cancel()
    }

    /** The screen goes away: drops the card and any pending Insert without republishing. */
    fun discardInsert() {
        invalidateInsert()
        dictation = null
    }

    private fun invalidateInsert() {
        generation += 1
        insertJob?.cancel()
        insertJob = null
    }
}
