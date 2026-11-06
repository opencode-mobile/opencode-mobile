package dev.opencode.hexcode.home

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.opencode.hexcode.firstrun.ModelChoice
import dev.opencode.hexcode.session.TodaySession
import dev.opencode.hexcode.session.TodayState
import dev.opencode.voiceengine.SpeechModels
import dev.opencode.voiceengine.VoiceEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/** The phone's side of dictation: record a take, end it, turn its samples into text. [VoiceEngine] in the app. */
interface Dictation {
    /** Loudness of the take, 0 to 1, for the meter. */
    val level: Float

    /**
     * Starts recording without waiting for the microphone, so the button can answer the touch at once. [onFailed] runs
     * on the main thread if it can't start.
     */
    fun start(onFailed: (Throwable) -> Unit)

    /** Ends the take and returns its samples, or null when nothing was recording. Runs after the start has finished. */
    suspend fun end(): FloatArray?

    fun drop()

    suspend fun transcribe(samples: FloatArray): String
}

/**
 * [VoiceEngine]'s recorder on a thread of its own: opening the microphone takes 30 to 250 ms, which the main thread
 * must not wait for. Start, end and drop run there in order, so a take never ends before it started.
 */
class VoiceEngineDictation(
    private val engine: VoiceEngine,
) : Dictation {
    private val audio = Executors.newSingleThreadExecutor { Thread(it, "hex-audio") }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + audio)

    override val level: Float get() = engine.level

    override fun start(onFailed: (Throwable) -> Unit) {
        val asked = SystemClock.uptimeMillis()
        scope.launch {
            try {
                engine.startRecording()
                Log.i(TalkLog.TAG, "microphone running ${SystemClock.uptimeMillis() - asked} ms after it was asked for")
            } catch (error: Throwable) {
                Log.w(TalkLog.TAG, "microphone didn't start", error)
                withContext(Dispatchers.Main) { onFailed(error) }
            }
        }
    }

    override suspend fun end(): FloatArray? = withContext(audio) { engine.endTake() }

    override fun drop() {
        scope.launch { engine.cancelRecording() }
    }

    override suspend fun transcribe(samples: FloatArray): String = engine.transcribe(samples)
}

/** Where dictation logs why a take didn't become text, and how long the microphone took. */
object TalkLog {
    const val TAG = "HexCode.Talk"
}

/** How the talk button is being used. */
enum class TalkMode {
    Idle,

    /** Held down: letting go ends the take. */
    Holding,

    /** Hands-free: a tap ends the take. */
    Locked,
}

/** One-line messages the home screen shows for a moment. */
enum class HomeNotice { ChunkDeleted, TakeDropped, NewDay, NotInThisVersion, MicUnavailable }

data class HomeUiState(
    val draft: Draft = Draft(),
    val talk: TalkMode = TalkMode.Idle,
    /** When the current take started, for its clock. */
    val takeStartedAt: Long = 0,
    val notice: HomeNotice? = null,
    /** Today's session: the status card, the top bar's state, the waiting line. */
    val today: TodayState = TodayState(),
)

/**
 * The home screen: today's session ([TodaySession], made by [makeSession] in this model's scope) and dictation, where
 * you hold to talk, lock or drop the take, and keep the text as a draft of chunks. Releasing never sends, and there is
 * no Send yet.
 */
class HomeViewModel(
    private val dictation: Dictation,
    makeSession: ((CoroutineScope) -> TodaySession)? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val state = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = state.asStateFlow()

    private val session: TodaySession? = makeSession?.invoke(viewModelScope)

    init {
        session?.let { today ->
            viewModelScope.launch {
                today.state.collect { next ->
                    state.update { it.copy(today = next, notice = if (next.newDayNotice) HomeNotice.NewDay else it.notice) }
                    if (next.newDayNotice) today.newDayShown()
                }
            }
            today.open()
        }
    }

    /** The app came back to the foreground: checks the date, and reconnects at once. */
    fun enteredForeground() {
        session?.enteredForeground()
    }

    /** The app went to the background: the event stream closes after a short grace. */
    fun enteredBackground() {
        session?.enteredBackground()
    }

    fun networkReturned() {
        session?.networkReturned()
    }

    /** Settings › Model changed: today's session follows it. */
    fun modelChanged(model: ModelChoice) {
        session?.switchModel(model)
    }

    /** Settings › Project changed: today's session is the one in the new project. */
    fun projectChanged() {
        session?.open()
    }

    /** Attach (M3) and Stop (M4) aren't built yet: a tap says so. */
    fun notInThisVersion() = state.update { it.copy(notice = HomeNotice.NotInThisVersion) }

    /** Developer settings' "Drop event stream". */
    fun dropStream() {
        session?.dropStream()
    }

    /** ↻ on the status card. */
    fun refreshSummary() {
        session?.refresh()
    }

    /** Try again, after "Couldn't open today's session". */
    fun retrySetup() {
        session?.retry()
    }

    val level: Float get() = dictation.level

    /** One chunk is transcribed at a time; takes keep recording meanwhile. */
    private val transcribing = Mutex()

    /**
     * The talk button went down: the take starts at once, and the microphone alongside it. Returns false when a take
     * is already running.
     */
    fun press(): Boolean {
        val current = state.value
        if (current.talk == TalkMode.Locked) return false
        if (current.draft.isListening) return false
        state.update { it.copy(draft = it.draft.startTake(), talk = TalkMode.Holding, takeStartedAt = clock()) }
        dictation.start { microphoneFailed() }
        return true
    }

    /** The microphone didn't start: the button goes back, and the notice says so. */
    private fun microphoneFailed() {
        if (!state.value.draft.isListening) return
        state.update { it.copy(draft = it.draft.dropTake(), talk = TalkMode.Idle, notice = HomeNotice.MicUnavailable) }
    }

    /** The talk button came up after a hold or drag; see [TalkRelease]. */
    fun release(how: TalkRelease) {
        if (state.value.talk != TalkMode.Holding) return
        when (how) {
            TalkRelease.Lock, TalkRelease.Tap -> state.update { it.copy(talk = TalkMode.Locked) }
            TalkRelease.Drop -> dropTake()
            TalkRelease.End -> endTake()
        }
    }

    /** A tap on a locked take pauses it into the draft. */
    fun tapLocked() {
        if (state.value.talk == TalkMode.Locked) endTake()
    }

    private fun endTake() {
        val started = state.value.takeStartedAt
        val take = state.value.draft.live
        state.update { it.copy(draft = it.draft.endTake(durationMs = clock() - started), talk = TalkMode.Idle) }
        if (take == null) return
        // Undispatched, so the end is queued behind this take's start now, before a next press can start another.
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            val samples = dictation.end()
            if (samples == null || samples.isEmpty()) {
                Log.w(
                    TalkLog.TAG,
                    "take ${take.id} left the draft: ${if (samples == null) "nothing was recording" else "no audio was recorded"}",
                )
                state.update { it.copy(draft = it.draft.transcribed(take.id, null)) }
                return@launch
            }
            val text =
                transcribing.withLock {
                    try {
                        dictation.transcribe(samples)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        // "No speech was detected" and engine failures alike: the take leaves the draft.
                        Log.w(
                            TalkLog.TAG,
                            "take ${take.id} left the draft: ${samples.size} samples didn't become text: ${error.message}",
                            error,
                        )
                        null
                    }
                }
            state.update { it.copy(draft = it.draft.transcribed(take.id, text)) }
        }
    }

    private fun dropTake() {
        dictation.drop()
        state.update { it.copy(draft = it.draft.dropTake(), talk = TalkMode.Idle, notice = HomeNotice.TakeDropped) }
    }

    fun selectChunk(index: Int) = state.update { it.copy(draft = it.draft.select(index)) }

    fun deselectChunk() = state.update { it.copy(draft = it.draft.deselect()) }

    fun deleteChunk() =
        state.update {
            val next = it.draft.deleteSelected()
            if (next === it.draft) it else it.copy(draft = next, notice = HomeNotice.ChunkDeleted)
        }

    fun undoDelete() = state.update { it.copy(draft = it.draft.undoDelete(), notice = null) }

    /** Re-record: a hands-free take that replaces the selected chunk when it ends. */
    fun rerecord() {
        if (state.value.draft.selected == null) return
        press()
        if (state.value.talk == TalkMode.Holding) state.update { it.copy(talk = TalkMode.Locked) }
    }

    fun clearDraft() = state.update { it.copy(draft = it.draft.clear()) }

    fun noticeShown() = state.update { it.copy(notice = null) }

    override fun onCleared() {
        if (state.value.draft.isListening) dictation.drop()
    }
}

/** How a hold of the talk button ended. */
enum class TalkRelease {
    /** Dragged right past the lock point: the take goes on hands-free. */
    Lock,

    /** Dragged left past the drop point: the take is thrown away. */
    Drop,

    /** Let go after a hold: the take ends. */
    End,

    /** A quick tap: the take goes on hands-free, like a lock. */
    Tap,
}

/** Debug builds on an emulator, which has no voice: each take is the next of the speech lab's bundled clips. */
class ClipDictation(
    private val context: Context,
    private val engine: VoiceEngine,
) : Dictation {
    private val clips = SpeechModels.clips(context)
    private var next = 0
    private var recording = false

    override val level: Float get() = if (recording) 0.5f else 0f

    override fun start(onFailed: (Throwable) -> Unit) {
        recording = true
    }

    override suspend fun end(): FloatArray? {
        if (!recording) return null
        recording = false
        val clip = clips[next++ % clips.size]
        return SpeechModels.clipAudio(context, clip)
    }

    override fun drop() {
        recording = false
    }

    override suspend fun transcribe(samples: FloatArray): String = engine.transcribe(samples)
}
