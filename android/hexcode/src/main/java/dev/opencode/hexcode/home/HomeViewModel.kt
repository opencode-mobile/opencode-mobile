package dev.opencode.hexcode.home

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.opencode.hexcode.BuildConfig
import dev.opencode.hexcode.firstrun.ModelChoice
import dev.opencode.hexcode.session.TodaySession
import dev.opencode.hexcode.session.TodayState
import dev.opencode.voiceengine.NoSpeechException
import dev.opencode.voiceengine.SpeechModels
import dev.opencode.voiceengine.VoiceEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.math.sqrt

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

    /** The take's text; empty when there was no speech. Throws when the engine fails on it. */
    suspend fun transcribe(samples: FloatArray): String

    /** Whether takes can be turned into text yet. Recording never waits for it. */
    val speech: StateFlow<SpeechStatus>

    /** Try again: loads the speech model again. */
    fun reload()
}

/** The engine's model state as dictation sees it. */
fun speechStatus(model: VoiceEngine.ModelState): SpeechStatus =
    when (model) {
        VoiceEngine.ModelState.NotLoaded -> SpeechStatus.Loading()
        is VoiceEngine.ModelState.Preparing -> SpeechStatus.Loading(model.fraction.takeIf { it < 1.0 })
        VoiceEngine.ModelState.Ready -> SpeechStatus.Ready
        is VoiceEngine.ModelState.Failed -> SpeechStatus.Failed
    }

/**
 * [VoiceEngine]'s recorder on a thread of its own: opening the microphone takes 30 to 250 ms, which the main thread
 * must not wait for. Start, end and drop run there in order, so a take never ends before it started.
 */
class VoiceEngineDictation(
    private val engine: VoiceEngine,
    loadModel: () -> Unit,
    /** Debug builds (`--ei HexCodeSpeechDelay`): the model counts as ready only this long after launch. */
    holdMs: Long = 0,
    /** Debug builds (`--ez HexCodeSpeechFail`): the first load counts as failed, until Try again. */
    failOnce: Boolean = false,
) : Dictation {
    private val audio = Executors.newSingleThreadExecutor { Thread(it, "hex-audio") }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + audio)
    private val gate = SpeechGate(engine, loadModel, holdMs, failOnce, scope)

    private var loggedAt = 0L

    /** The engine reports RMS × 6 (capped at 1, well above full); the meter shows it in dBFS ([MeterLevel]). */
    override val level: Float
        get() {
            val rms = engine.level / 6f
            if (BuildConfig.DEBUG && engine.isRecording) logLevel(rms)
            return MeterLevel.fromRms(rms)
        }

    /** Debug builds: the take's loudness every quarter second, to tune the meter's mapping on the phone. */
    private fun logLevel(rms: Float) {
        val now = SystemClock.uptimeMillis()
        if (now - loggedAt < 250) return
        loggedAt = now
        Log.d(TalkLog.TAG, "level %.1f dBFS → %.2f".format(MeterLevel.dbfs(rms), MeterLevel.fromRms(rms)))
    }

    override val speech: StateFlow<SpeechStatus> get() = gate.speech

    override fun reload() = gate.reload()

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

    override suspend fun transcribe(samples: FloatArray): String = transcribeWith(engine, samples)
}

/** [VoiceEngine.transcribe], with "no speech" as empty text rather than a failure. */
private suspend fun transcribeWith(
    engine: VoiceEngine,
    samples: FloatArray,
): String =
    try {
        engine.transcribe(samples)
    } catch (_: NoSpeechException) {
        ""
    }

/**
 * The engine's readiness as dictation sees it. Debug builds can hold it back: [holdMs] after it's made, the model only
 * then counts as ready (`--ei HexCodeSpeechDelay`), and with [failOnce] its first load counts as failed until Try again
 * (`--ez HexCodeSpeechFail`).
 */
class SpeechGate(
    engine: VoiceEngine,
    private val loadModel: () -> Unit,
    holdMs: Long,
    failOnce: Boolean,
    scope: CoroutineScope,
) {
    private val released = MutableStateFlow(holdMs <= 0)
    private val failing = MutableStateFlow(failOnce)

    init {
        if (holdMs > 0) scope.launch { delay(holdMs).also { released.value = true } }
    }

    val speech: StateFlow<SpeechStatus> =
        combine(engine.modelState, released, failing) { model, ready, failed ->
            when {
                model == VoiceEngine.ModelState.Ready && failed -> SpeechStatus.Failed
                model == VoiceEngine.ModelState.Ready && !ready -> SpeechStatus.Loading()
                else -> speechStatus(model)
            }
        }.stateIn(
            scope,
            SharingStarted.Eagerly,
            speechStatus(engine.modelState.value).let {
                if (failOnce ||
                    holdMs > 0
                ) {
                    SpeechStatus.Loading()
                } else {
                    it
                }
            },
        )

    /** Try again: clears a held-back failure, and loads the model again. */
    fun reload() {
        failing.value = false
        loadModel()
    }
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
enum class HomeNotice {
    ChunkDeleted,
    TakeDropped,
    NewDay,
    NotInThisVersion,
    MicUnavailable,

    /** "Queued · sends after this turn", with Undo. */
    Queued,

    /** "Sent · starting now". */
    SentIdle,

    /** "Sending now…" with the text and Undo, for the 3 s a Now is held. */
    SendingNow,

    /** "Sent · lands at the next step". */
    SentNow,

    /** "Not sent · It never left the phone.": a held Now undone. */
    Unsent,

    /** "Taken back · It never reached the agent.": a queued message undone. */
    TakenBack,

    /** "Couldn't send · Your draft is kept." */
    SendFailed,
}

data class HomeUiState(
    val draft: Draft = Draft(),
    val talk: TalkMode = TalkMode.Idle,
    /** When the current take started, for its clock. */
    val takeStartedAt: Long = 0,
    val notice: HomeNotice? = null,
    /** Today's session: the status card, the top bar's state, the waiting line. */
    val today: TodayState = TodayState(),
    /** Whether takes can be turned into text yet. */
    val speech: SpeechStatus = SpeechStatus.Loading(),
    /** The After turn / Now switch, back to After turn after every send. */
    val sendMode: SendMode = SendMode.AfterTurn,
    /** The notice's second line when it shows the message, such as "Sending now…". */
    val noticeDetail: String? = null,
    /** Waiting to send rows whose Cancel (the DELETE) is running: their Cancel shows a spinner. */
    val cancelling: Set<String> = emptySet(),
) {
    /** The agent runs or waits for an answer, so Send has a choice to make. */
    val busy: Boolean get() = today.busySince != null || today.pendingInput != null
}

/**
 * The home screen: today's session ([TodaySession], made by [makeSession] in this model's scope) and dictation, where
 * you hold to talk, lock or drop the take, and keep the text as a draft of chunks. Releasing never sends, and there is
 * no Send yet.
 */
class HomeViewModel(
    private val dictation: Dictation,
    makeSession: ((CoroutineScope) -> TodaySession)? = null,
    /** Keeps a take's audio until it has text. */
    private val takes: TakeStore = MemoryTakeStore(),
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val state = MutableStateFlow(HomeUiState(speech = dictation.speech.value))
    val uiState: StateFlow<HomeUiState> = state.asStateFlow()

    private val session: TodaySession? = makeSession?.invoke(viewModelScope)

    /** Turning waiting takes into text, oldest first, once the model is ready. */
    private var draining: Job? = null

    init {
        viewModelScope.launch {
            dictation.speech.collect { speech ->
                state.update { it.copy(speech = speech) }
                when (speech) {
                    SpeechStatus.Ready -> drainWaiting()
                    SpeechStatus.Failed -> state.update { it.copy(draft = it.draft.waitingFailed()) }
                    is SpeechStatus.Loading -> Unit
                }
            }
        }
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

    /** Screen readers' "Drop": forgets the take that's recording, held or hands-free. */
    fun dropTakeNow() {
        if (state.value.talk != TalkMode.Idle) dropTake()
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
            // A take with no audio, or under 0.3 s, leaves the draft quietly.
            if (samples == null || samples.size < MIN_SAMPLES) {
                val why = if (samples == null) "nothing was recording" else "${samples.size} samples, under 0.3 s"
                Log.i(TalkLog.TAG, "take ${take.id} left the draft: $why")
                state.update { it.copy(draft = it.draft.transcribed(take.id, null)) }
                return@launch
            }
            if (state.value.speech != SpeechStatus.Ready) {
                // The model isn't ready: the take waits, its audio kept in a file, and nothing said is lost.
                takes.save(take.id, samples)
                state.update { it.copy(draft = it.draft.waiting(take.id)) }
                when (state.value.speech) {
                    SpeechStatus.Ready -> drainWaiting()
                    SpeechStatus.Failed -> state.update { it.copy(draft = it.draft.waitingFailed()) }
                    is SpeechStatus.Loading -> Unit
                }
                return@launch
            }
            transcribe(take.id, samples)
        }
    }

    /** Turns take [id]'s [samples] into text; a failure keeps the chunk, and its audio for Try again. */
    private suspend fun transcribe(
        id: Long,
        samples: FloatArray,
    ) {
        transcribing.withLock {
            try {
                val text = dictation.transcribe(samples)
                // A silent take leaves quietly too.
                if (text.isBlank()) Log.i(TalkLog.TAG, "take $id left the draft: no speech in ${samples.size} samples")
                state.update { it.copy(draft = it.draft.transcribed(id, text)) }
                takes.delete(id)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // The engine failed: the take stays, reading "Couldn't turn this take into text".
                Log.w(TalkLog.TAG, "take $id failed: ${samples.size} samples didn't become text: ${error.message}", error)
                takes.save(id, samples)
                state.update { it.copy(draft = it.draft.failed(id, retry = true)) }
            }
        }
    }

    /** The model is ready: the waiting takes are turned into text in the order they were recorded. */
    private fun drainWaiting() {
        if (draining?.isActive == true) return
        draining =
            viewModelScope.launch {
                while (state.value.speech == SpeechStatus.Ready) {
                    val id =
                        state.value.draft.waitingIds
                            .firstOrNull() ?: break
                    state.update { it.copy(draft = it.draft.transcribing(id)) }
                    val samples = takes.load(id)
                    if (samples == null) {
                        Log.w(TalkLog.TAG, "take $id left the draft: its audio wasn't kept")
                        state.update { it.copy(draft = it.draft.transcribed(id, null)) }
                    } else {
                        transcribe(id, samples)
                    }
                }
            }
    }

    /** What Undo on the current notice takes back: a Now held on the phone, or a message queued in the inbox. */
    private sealed interface Undoable {
        val draft: Draft

        data class Held(
            val job: Job,
            override val draft: Draft,
        ) : Undoable

        data class Queued(
            val itemId: String,
            override val draft: Draft,
        ) : Undoable
    }

    private var undoable: Undoable? = null

    fun setSendMode(mode: SendMode) = state.update { it.copy(sendMode = mode) }

    /**
     * Send (app-plan.md, Sending): the draft clears at once. An idle agent gets it now (`steer`); a working one after
     * this turn (`queue`), or, with Now, after 3 s held on the phone with Undo, as a correction (`steer`). The switch
     * goes back to After turn. If sending fails, the draft comes back as it was.
     */
    fun send() {
        val current = state.value
        val draft = current.draft
        if (!draft.isSettled || draft.text.isBlank()) return
        val plan = Sending.plan(current.busy, current.sendMode)
        val text = draft.text
        state.update { it.copy(draft = it.draft.clear(), sendMode = SendMode.AfterTurn) }
        when (plan) {
            SendPlan.Idle ->
                viewModelScope.launch {
                    if (deliver(plan, text, draft) != null) notify(HomeNotice.SentIdle, text)
                }
            SendPlan.Queue ->
                viewModelScope.launch {
                    val id = deliver(plan, text, draft) ?: return@launch
                    undoable = Undoable.Queued(id, draft)
                    notify(HomeNotice.Queued, null)
                }
            SendPlan.Now -> {
                val job =
                    viewModelScope.launch {
                        delay(Sending.HOLD_MS)
                        undoable = null
                        // Held through a reconnect, it goes once the stream is back.
                        session?.awaitStream()
                        if (deliver(plan, text, draft) != null) notify(HomeNotice.SentNow, text)
                    }
                undoable = Undoable.Held(job, draft)
                notify(HomeNotice.SendingNow, text)
            }
        }
    }

    /** Sends [text] as [plan] says; on failure the [draft] comes back with "Couldn't send". Returns the inbox item's id. */
    private suspend fun deliver(
        plan: SendPlan,
        text: String,
        draft: Draft,
    ): String? {
        val today = session
        return try {
            today ?: error("No session to send to")
            today.send(Sending.text(plan, text), Sending.delivery(plan))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TalkLog.TAG, "couldn't send: ${error.message}", error)
            restore(draft)
            notify(HomeNotice.SendFailed, null)
            null
        }
    }

    /** The draft as it was before Send, unless a new one has started meanwhile, which then follows it. */
    private fun restore(draft: Draft) = state.update { it.copy(draft = it.draft.restored(draft)) }

    private fun notify(
        notice: HomeNotice,
        detail: String?,
    ) = state.update { it.copy(notice = notice, noticeDetail = detail) }

    /** The notice whose action just ran: its dismissal mustn't clear the notice the action put up instead. */
    private var actioned: HomeNotice? = null

    /** The notice's action: Undo a deleted chunk, a held Now, or a queued message. */
    fun noticeAction() {
        actioned = state.value.notice
        when (state.value.notice) {
            HomeNotice.ChunkDeleted -> undoDelete()
            HomeNotice.SendingNow, HomeNotice.Queued -> undoSend()
            else -> Unit
        }
    }

    private fun undoSend() {
        when (val taking = undoable) {
            is Undoable.Held -> {
                taking.job.cancel()
                restore(taking.draft)
                notify(HomeNotice.Unsent, null)
            }
            is Undoable.Queued ->
                viewModelScope.launch {
                    try {
                        session?.cancelQueued(taking.itemId)
                        restore(taking.draft)
                        notify(HomeNotice.TakenBack, null)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Log.w(TalkLog.TAG, "couldn't take back ${taking.itemId}: ${error.message}", error)
                    }
                }
            null -> Unit
        }
        undoable = null
    }

    /** Cancel, in the Waiting to send sheet: a spinner while the DELETE runs; the row leaves as soon as it works. */
    fun cancelQueued(itemId: String) {
        if (itemId in state.value.cancelling) return
        state.update { it.copy(cancelling = it.cancelling + itemId) }
        viewModelScope.launch {
            try {
                session?.cancelQueued(itemId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // The row stays, with Cancel to try again.
                Log.w(TalkLog.TAG, "couldn't cancel $itemId: ${error.message}", error)
            } finally {
                state.update { it.copy(cancelling = it.cancelling - itemId) }
            }
        }
    }

    /** Try again, on takes that couldn't be turned into text: the model loads again if it has to. */
    fun retryTakes() {
        state.update { it.copy(draft = it.draft.retryFailed()) }
        if (state.value.speech == SpeechStatus.Ready) drainWaiting() else dictation.reload()
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

    fun clearDraft() {
        state.update { it.copy(draft = it.draft.clear()) }
        if (state.value.draft.isEmpty) viewModelScope.launch { takes.clear() }
    }

    /** The notice has gone. After its action, only that notice is cleared, not "Not sent" or another that followed. */
    fun noticeShown() {
        val after = actioned
        actioned = null
        if (after != null && state.value.notice != after) return
        state.update { it.copy(notice = null, noticeDetail = null) }
    }

    override fun onCleared() {
        if (state.value.draft.isListening) dictation.drop()
    }
}

/** 0.3 s at 16 kHz: a shorter take leaves the draft quietly. */
private const val MIN_SAMPLES = 4_800

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
    loadModel: () -> Unit,
    holdMs: Long = 0,
    failOnce: Boolean = false,
) : Dictation {
    private val clips = SpeechModels.clips(context)
    private var next = 0
    private var take: FloatArray? = null
    private var startedAt = 0L

    /** The clip's own loudness as the take goes on, so the meter moves as it would for that voice. */
    override val level: Float
        get() {
            val audio = take ?: return 0f
            val window = VoiceEngine.SAMPLE_RATE / 20
            val at = ((SystemClock.uptimeMillis() - startedAt) * VoiceEngine.SAMPLE_RATE / 1000).toInt()
            if (at + window > audio.size) return 0f
            var sum = 0.0
            for (i in at until at + window) sum += audio[i] * audio[i]
            return MeterLevel.fromRms(sqrt(sum / window).toFloat())
        }

    override fun start(onFailed: (Throwable) -> Unit) {
        val clip = clips[next++ % clips.size]
        take = SpeechModels.clipAudio(context, clip)
        startedAt = SystemClock.uptimeMillis()
    }

    override suspend fun end(): FloatArray? = take.also { take = null }

    override fun drop() {
        take = null
    }

    override suspend fun transcribe(samples: FloatArray): String = transcribeWith(engine, samples)

    private val gate = SpeechGate(engine, loadModel, holdMs, failOnce, CoroutineScope(SupervisorJob()))

    override val speech: StateFlow<SpeechStatus> get() = gate.speech

    override fun reload() = gate.reload()
}
