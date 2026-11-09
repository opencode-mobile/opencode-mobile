package dev.opencode.hexcode.home

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.opencode.hexcode.BuildConfig
import dev.opencode.hexcode.firstrun.ModelChoice
import dev.opencode.hexcode.session.AgentStatus
import dev.opencode.hexcode.session.ApprovalDecision
import dev.opencode.hexcode.session.GoalAction
import dev.opencode.hexcode.session.GoalCommand
import dev.opencode.hexcode.session.GoalOutcome
import dev.opencode.hexcode.session.GoalRefusal
import dev.opencode.hexcode.session.GoalText
import dev.opencode.hexcode.session.NewSessionOutcome
import dev.opencode.hexcode.session.PendingItem
import dev.opencode.hexcode.session.PromptFiles
import dev.opencode.hexcode.session.PromptImage
import dev.opencode.hexcode.session.QuestionAnswers
import dev.opencode.hexcode.session.RecentStep
import dev.opencode.hexcode.session.RequestGoneException
import dev.opencode.hexcode.session.SessionSetup
import dev.opencode.hexcode.session.StopOutcome
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/** The phone's side of dictation: record a take, end it, turn its samples into text. [VoiceEngine] in the app. */
interface Dictation {
    /** Loudness of the take, 0 to 1, for the meter; null while a take waits for its first audio. */
    val level: Float?

    /** Home is on screen: get the microphone ready, so a press only has to start it. */
    fun warmUp() = Unit

    /** Home left the screen: let go of what [warmUp] got ready. */
    fun coolDown() = Unit

    /**
     * Starts recording without waiting for the microphone, so the button can answer the touch at once. [onFailed] runs
     * on the main thread if it can't start.
     */
    fun start(onFailed: (Throwable) -> Unit)

    /** Ends the take and returns its samples, or null when nothing was recording. Runs after the start has finished. */
    suspend fun end(): FloatArray?

    fun drop()

    /**
     * Finds the pauses in the take just started (app-plan.md, Dictation › Locked takes): it is cut at the speaker's
     * pauses while it records, and each part goes to [parts], in order. When the take ends, the audio since the last
     * cut goes too (if the take was cut, and there was speech in it), and then [parts] closes; it closes with an error
     * if the cutting broke. A dropped take's [parts] are left to the caller. False when pauses can't be found yet: the
     * take is then turned into text whole after release.
     */
    fun split(parts: SendChannel<TakePart>): Boolean = false

    /** The take's text; empty when there was no speech. Throws when the engine fails on it. */
    suspend fun transcribe(samples: FloatArray): String

    /** Whether takes can be turned into text yet. Recording never waits for it. */
    val speech: StateFlow<SpeechStatus>

    /** Try again: loads the speech model again. */
    fun reload()

    /**
     * The talk button was pressed: the speech model starts loading now if it hasn't yet, instead of about 1 s after
     * the first frame (app-plan.md, Dictation › Load). The take waits for it as for any load.
     */
    fun loadNow() = Unit
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
 * Takes from [mic] on a thread of its own, turned into text by [engine]: starting the microphone takes time the main
 * thread must not wait for. Warm-up, start, split, end and drop run there in order, so a take never ends before it
 * started. A locked take is fed to the pause detector on another thread, `hex-vad`, as its audio arrives: never on the
 * microphone's own thread, which only reads.
 */
class VoiceEngineDictation(
    private val engine: VoiceEngine,
    private val mic: Microphone,
    loadModel: () -> Unit,
    /** Debug builds (`--ei HexCodeSpeechDelay`): the model counts as ready only this long after launch. */
    holdMs: Long = 0,
    /** Debug builds (`--ez HexCodeSpeechFail`): the first load counts as failed, until Try again. */
    failOnce: Boolean = false,
    /** Starts the speech model's first load now, if it hasn't started (a talk press before its delay ran out). */
    private val startLoad: () -> Unit = {},
) : Dictation {
    private val audio = Executors.newSingleThreadExecutor { Thread(it, "hex-audio") }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + audio)
    private val gate = SpeechGate(engine, loadModel, holdMs, failOnce, scope)
    private val vad = Executors.newSingleThreadExecutor { Thread(it, "hex-vad") }.asCoroutineDispatcher()
    private val vadScope = CoroutineScope(SupervisorJob() + vad)

    /** The locked take being cut at pauses: its job on `hex-vad`, and whether it has ended. Audio thread only. */
    private class Splitting(
        val job: Job,
        val ended: AtomicBoolean,
    )

    private var splitting: Splitting? = null

    private var loggedAt = 0L

    /** Home is on screen: after a take ends, the next recorder is made ahead too. */
    @Volatile
    private var warm = false

    /** The take's RMS in dBFS ([MeterLevel]); null until its first audio, so the meter runs its listening wave. */
    override val level: Float?
        get() {
            if (!mic.hasAudio) return null
            val rms = mic.rms
            if (BuildConfig.DEBUG) logLevel(rms)
            return MeterLevel.fromRms(rms)
        }

    override fun warmUp() {
        warm = true
        scope.launch { if (warm) mic.prepare() }
    }

    override fun coolDown() {
        warm = false
        scope.launch { if (!warm) mic.release() }
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

    override fun loadNow() = startLoad()

    override fun start(onFailed: (Throwable) -> Unit) {
        val asked = SystemClock.uptimeMillis()
        scope.launch {
            try {
                mic.start(pressedAt = asked)
            } catch (error: Throwable) {
                Log.w(TalkLog.TAG, "microphone didn't start", error)
                withContext(Dispatchers.Main) { onFailed(error) }
            }
        }
    }

    override suspend fun end(): FloatArray? =
        withContext(audio) {
            mic.end().also {
                // The take's audio is all in: the splitter takes its last part and closes the parts.
                splitting?.ended?.set(true)
                splitting = null
                readyForNext()
            }
        }

    override fun drop() {
        scope.launch {
            mic.end()
            splitting?.job?.cancel()
            splitting = null
            readyForNext()
        }
    }

    override fun split(parts: SendChannel<TakePart>): Boolean {
        if (!engine.pausesReady) return false
        scope.launch {
            val take = mic.take
            if (take == null || splitting != null) {
                parts.close()
                return@launch
            }
            val ended = AtomicBoolean(false)
            splitting = Splitting(vadScope.launch { cut(take, ended, parts) }, ended)
        }
        return true
    }

    /**
     * On `hex-vad`: feeds the take to a pause detector every [VAD_POLL_MS] as its audio arrives (spike S3 fed 50 ms at
     * a time), and sends each part as it's cut. Once the take has [ended], the last part, then [parts] closes.
     */
    private suspend fun cut(
        take: RecordedAudio,
        ended: AtomicBoolean,
        parts: SendChannel<TakePart>,
    ) {
        val detector =
            try {
                engine.pauseDetector() ?: error("The pause detector isn't ready.")
            } catch (error: Throwable) {
                Log.w(TalkLog.TAG, "couldn't cut the take at pauses: ${error.message}", error)
                parts.close(error)
                return
            }
        try {
            val splitter =
                TakeSplitter(take, detector.asPauses(), { part ->
                    Log.i(TalkLog.TAG, "part ${part.number} cut: %.1f s from %.1f s".format(part.seconds, part.start / 16_000.0))
                    parts.trySend(part)
                }, SystemClock::uptimeMillis)
            while (!ended.get()) {
                splitter.step()
                delay(VAD_POLL_MS)
            }
            splitter.finish()
            parts.close()
        } catch (error: CancellationException) {
            parts.close()
            throw error
        } catch (error: Throwable) {
            Log.w(TalkLog.TAG, "cutting the take at pauses broke: ${error.message}", error)
            parts.close(error)
        } finally {
            detector.close()
        }
    }

    /** Made ahead for the next press, after this take's work on the audio thread. */
    private fun readyForNext() {
        scope.launch { if (warm) mic.prepare() }
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

/** Where attaching logs images that couldn't be prepared, and what the model's image input was. */
object AttachLog {
    const val TAG = "HexCode.Attach"
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

    /** "4 attachments is the most per message". */
    AttachLimit,

    /** "Couldn't add this image". */
    AttachFailed,

    /** "Couldn't add this file": it couldn't be read. */
    FileFailed,

    /**
     * "Couldn't upload <name> · Check the connection to your computer, then attach it again.", the name in
     * [HomeUiState.noticeDetail].
     */
    UploadFailed,

    /** "Sent without the images · <Model> can't see them" ([HomeUiState.imageInput]). */
    SentNoImages,

    /** The same, for a message queued after this turn: with Undo, as "Queued" has. */
    QueuedNoImages,

    /** "No longer needed · It was answered elsewhere or the run ended." */
    NeedsGone,

    /** "Couldn't send · Try again.": a reply to a question or approval failed. */
    NeedsFailed,

    /** "Hold to stop · Keep pressing for 3 seconds. A tap does nothing." */
    StopTap,

    /** "Stop cancelled · The agent keeps running." */
    StopCancelled,

    /** "Stopped · Talk to start again." */
    Stopped,

    /** "Stopped · Waiting messages send after your next one." */
    StoppedWaiting,

    /** "Nothing to stop · The agent had already finished." */
    NothingToStop,

    /** "Couldn't stop · Check the connection and hold again." */
    StopFailed,

    /** "Goal set · The agent keeps going until it's done." (idle) */
    GoalSetIdle,

    /** "Goal set · The agent starts on it at its next step." (busy) */
    GoalSetBusy,

    /** "Goal replaced · …", idle and busy. */
    GoalReplacedIdle,
    GoalReplacedBusy,

    /** "Goal paused · The agent finishes this turn, then stops." */
    GoalPaused,

    /** "Goal resumed · The agent carries on." */
    GoalResumed,

    /** "Goal cleared", or while busy "Goal cleared · The agent finishes this turn." */
    GoalCleared,
    GoalClearedBusy,

    /** "The goal had already changed": a `500`. */
    GoalStale,

    /** "Couldn't change the goal · Try again." */
    GoalFailed,

    /** "Say a longer goal · A single word is read as a goal command." */
    GoalRefusedWord,

    /** "That's too long for a goal · Goals are limited to 4,000 characters." */
    GoalRefusedLong,

    /** "Answer copied" (iOS, and Android below 13, where the system doesn't confirm copies). */
    AskCopied,

    /** "New session · The earlier one is kept on your computer.": Settings › Start a new session. */
    NewSession,

    /** "Paired again · Signed in to <name>.", the name in [HomeUiState.noticeDetail]. */
    PairedAgain,
}

/** A take for the goal sheet's box: recording while the button is held, then being turned into text. */
enum class GoalTake { Listening, Transcribing }

/**
 * The goal sheet's box as a take (app-plan.md, The goal sheet › Saying it): [text] once said (null before), and the
 * [take] while one runs.
 */
data class GoalEdit(
    val text: String? = null,
    val take: GoalTake? = null,
)

/** The open goal sheet: its box as a take while one is being said ([edit]), and which button waits for the server. */
data class GoalSheet(
    val edit: GoalEdit? = null,
    val busy: GoalAction? = null,
)

/** The open approval or question ([itemId]), and which of its buttons waits for the server. */
data class NeedsSheet(
    val itemId: String,
    val busy: NeedsAction? = null,
)

/** A button of the approval or question sheet. */
enum class NeedsAction { Deny, Always, Once, Submit, Dismiss }

/** Where a take on a sheet goes: an approval's Say why, or a question's own answer for field [key]. */
sealed interface SheetTarget {
    val itemId: String

    data class Note(
        override val itemId: String,
    ) : SheetTarget

    data class Own(
        override val itemId: String,
        val key: String,
    ) : SheetTarget

    /** The goal sheet's box. */
    data object Goal : SheetTarget {
        override val itemId = ""
    }
}

/** A take on a sheet: recording while the row is held, then being turned into text. */
data class SheetTake(
    val target: SheetTarget,
    val transcribing: Boolean = false,
)

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
    /** A message of only images: the notice's second line says how many ("2 images") in place of its text. */
    val noticeImages: Int = 0,
    /** The same for its files ("1 file", "2 images, 1 file"). */
    val noticeFiles: Int = 0,
    /** Waiting to send rows whose Cancel (the DELETE) is running: their Cancel shows a spinner. */
    val cancelling: Set<String> = emptySet(),
    /** Whether today's session's model can see images; null until known, which counts as yes. */
    val imageInput: ImageInput? = null,
    /** The phone's copy of the images each queued message took, by inbox item, for the Waiting to send sheet. */
    val queuedImages: Map<String, List<DraftImage>> = emptyMap(),
    /** The files each queued message took, by inbox item, for their tiles in Waiting to send. */
    val queuedFiles: Map<String, List<DraftFile>> = emptyMap(),
    /**
     * Files can be attached: the server declares the upload route and gives `paths.tmp`, and no upload has found the
     * route changed. Null until it's known, which doesn't dim the row.
     */
    val filesAvailable: Boolean? = null,
    /** The approval or question sheet, while open. */
    val needs: NeedsSheet? = null,
    /** What was said for each approval's Say why, by request id: Deny's note. */
    val notes: Map<String, String> = emptyMap(),
    /** Each question's answers so far, by form id: kept while it's hidden, and when going Back. */
    val answers: Map<String, QuestionAnswers> = emptyMap(),
    /** A take on a sheet (Say why, an own answer). */
    val sheetTake: SheetTake? = null,
    /** Details is open. */
    val detailsOpen: Boolean = false,
    /**
     * Details' Recent: read in the background when today's session opens and a second after each run ends, again when
     * Details opens, and after each tool while it's open.
     */
    val recent: List<RecentStep> = emptyList(),
    /** The first read of Recent for today's session has returned (or failed). */
    val recentRead: Boolean = false,
    /** The first read of Running now for today's session has returned (or failed). */
    val runningRead: Boolean = false,
    /** Stop is held: the warning, the screen edge and the ring show, and toasts are hidden. */
    val stopHolding: Boolean = false,
    /** When the hold started ([clock] time), for the ring, the bar and the count. */
    val stopHeldAt: Long = 0,
    /** The 3 s were reached: the interrupt is on its way. */
    val stopping: Boolean = false,
    /** The goal sheet, while open. */
    val goalSheet: GoalSheet? = null,
    /** The draft's ⋯ menu is open. */
    val draftMenu: Boolean = false,
) {
    /** Details' first reads haven't all returned: with nothing else to show it has a spinner, not the empty line. */
    val detailsReading: Boolean get() = !recentRead || !runningRead

    /** The draft can be asked on the side or set as the goal: every chunk has text, and there is some. */
    val draftReady: Boolean get() = draft.isSettled && draft.text.isNotBlank()

    /** A take records, on the talk button or a sheet. */
    val recording: Boolean get() = talk != TalkMode.Idle || draft.isListening

    /** Stop is lit (the agent works or waits for an answer); dimmed, a hold does nothing. */
    val stopLive: Boolean get() = today.agent.let { it is AgentStatus.Working || it == AgentStatus.NeedsInput }

    /** The open sheet's item, while it's still pending. */
    val needsItem: PendingItem? get() = needs?.let { open -> today.pending.firstOrNull { it.id == open.itemId } }

    /** The agent runs or waits for an answer, so Send has a choice to make. */
    val busy: Boolean get() = today.busySince != null || today.pendingInput != null

    /** Images go with the message: the model sees them, or it isn't known (app-plan.md, Attach). */
    val seesImages: Boolean get() = imageInput?.sees != false

    /** Send can go now. */
    val canSend: Boolean get() = draft.canSend(seesImages)
}

/**
 * The home screen: today's session ([TodaySession], made by [makeSession] in this model's scope) and dictation, where
 * you hold to talk, lock or drop the take, and keep the text as a draft of chunks, with any screenshots and photos
 * attached. Releasing never sends; Send does.
 */
class HomeViewModel(
    private val dictation: Dictation,
    makeSession: ((CoroutineScope) -> TodaySession)? = null,
    /** Keeps a take's audio until it has text. */
    private val takes: TakeStore = MemoryTakeStore(),
    /** Prepares picked and taken images, and keeps them in the cache until they're sent or cleared. */
    private val images: ImagePreparer = NoImagePreparer,
    /** Whether today's session's model can see images; null when that isn't known (it then counts as yes). */
    private val imageInput: suspend () -> ImageInput? = { null },
    /** Copies picked files into the cache and uploads them to the computer (app-plan.md, Attach files). */
    private val files: FileAttacher = NoFileAttacher,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val state = MutableStateFlow(HomeUiState(speech = dictation.speech.value))
    val uiState: StateFlow<HomeUiState> = state.asStateFlow()

    private val session: TodaySession? = makeSession?.invoke(viewModelScope)

    /** Turning waiting takes into text, oldest first, once the model is ready. */
    private var draining: Job? = null

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

    /** Inbox items the queued images map has seen in today's inbox: once one leaves it, its images can go. */
    private val seenQueued = mutableSetOf<String>()

    /** The same for the queued files map. */
    private val seenQueuedFiles = mutableSetOf<String>()

    /** Each file's copy and upload, by draft file id, so its ✕ can cancel it. */
    private val uploads = mutableMapOf<Long, Job>()

    /** Drafts being sent, which come back if sending fails. */
    private val inFlight = mutableListOf<Draft>()

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
                var previous: TodayState? = null
                today.state.collect { next ->
                    val before = state.value.queuedImages
                    state.update {
                        it.copy(
                            today = next,
                            notice = if (next.newDayNotice) HomeNotice.NewDay else it.notice,
                            queuedImages = keptQueued(it.queuedImages, next, seenQueued),
                            queuedFiles = keptQueued(it.queuedFiles, next, seenQueuedFiles),
                        )
                    }
                    if (state.value.queuedImages.size != before.size) sweepImages()
                    if (next.newDayNotice) today.newDayShown()
                    watchNeeds(next)
                    watchDetails(previous, next)
                    previous = next
                }
            }
            today.open()
        }
        // Images prepared and files copied before the app last closed went with no draft.
        sweepImages()
        viewModelScope.launch { files.discardAll() }
        checkImageInput()
        checkFiles()
    }

    /**
     * The phone's copy of each queued message's images, until the message is delivered or cancelled: it leaves today's
     * inbox after being in it, or nothing is waiting any more while the agent is idle.
     */
    private fun <T> keptQueued(
        kept: Map<String, List<T>>,
        today: TodayState,
        seen: MutableSet<String>,
    ): Map<String, List<T>> {
        val waiting = today.queued.map { it.id }.toSet()
        seen += kept.keys.filter { it in waiting }
        val idle = today.busySince == null && today.pendingInput == null
        return kept.filterKeys { id -> id in waiting || (id !in seen && !idle) }.also {
            seen.retainAll(it.keys)
        }
    }

    /** Asks whether files can be uploaded; a failed check leaves it unknown. */
    private fun checkFiles() {
        viewModelScope.launch {
            val available =
                try {
                    files.available()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.w(AttachLog.TAG, "couldn't check the upload route: ${error.message}")
                    return@launch
                }
            state.update { it.copy(filesAvailable = if (it.filesAvailable == false) false else available) }
        }
    }

    /** Asks whether the session's model can see images; a failed check keeps what was known. */
    private fun checkImageInput() {
        viewModelScope.launch {
            val known =
                try {
                    imageInput()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.w(AttachLog.TAG, "couldn't read the model's image input: ${error.message}")
                    return@launch
                }
            state.update { it.copy(imageInput = known) }
        }
    }

    /**
     * The paperclip: opens the Attach sheet (true), or with [Draft.MAX_ATTACHMENTS] on the draft shows "4 attachments is
     * the most per message" (false).
     */
    fun openAttach(): Boolean {
        if (state.value.draft.room == 0) {
            notify(HomeNotice.AttachLimit, null)
            return false
        }
        return true
    }

    /**
     * Picked or taken images, as `content://` URIs: each goes on the draft at once with a spinner and is prepared off
     * the main thread. Past the room left they're left out, with the limit notice; one that can't be read leaves the
     * draft with "Couldn't add this image".
     */
    fun attach(sources: List<String>) = attachMedia(sources.map { PickedMedia(it, null) })

    /**
     * Picks from Photos and videos (app-plan.md, Attach files): images are prepared and sent inline as [attach] does;
     * videos are uploaded as files. Past the room left they're left out, with the limit notice.
     */
    fun attachMedia(picked: List<PickedMedia>) {
        if (picked.isEmpty()) return
        val taken = picked.take(state.value.draft.room)
        if (taken.size < picked.size) notify(HomeNotice.AttachLimit, null)
        val (videos, images) = taken.partition { it.video }
        addImages(images.map { it.uri })
        addFiles(videos.map { it.uri }, video = true)
    }

    /**
     * Picks from Files (app-plan.md, Attach files): each goes on the draft at once with its ring, is copied into the
     * cache and uploaded with its exact bytes, whatever it is. Past the room left they're left out, with the limit
     * notice.
     */
    fun attachFiles(sources: List<String>) {
        if (sources.isEmpty()) return
        val taken = sources.take(state.value.draft.room)
        if (taken.size < sources.size) notify(HomeNotice.AttachLimit, null)
        addFiles(taken, video = false)
    }

    private fun addFiles(
        sources: List<String>,
        video: Boolean,
    ) {
        if (sources.isEmpty()) return
        var ids: List<Long> = emptyList()
        state.update {
            val (draft, added) = it.draft.addFiles(sources.size, video)
            ids = added
            it.copy(draft = draft)
        }
        ids.zip(sources).forEach { (id, source) ->
            uploads[id] = viewModelScope.launch { upload(id, source) }
        }
    }

    /**
     * Copies file [id] from [source] and uploads it; the cached copy goes once it's done, however it ends. One that
     * can't be read leaves with "Couldn't add this file"; a failed upload with "Couldn't upload <name>", and a route that
     * has changed also dims Files.
     */
    private suspend fun upload(
        id: Long,
        source: String,
    ) {
        val staged =
            try {
                files.stage(source)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(AttachLog.TAG, "couldn't copy a file: ${error.message}", error)
                uploads.remove(id)
                state.update { it.copy(draft = it.draft.removeFile(id)) }
                notify(HomeNotice.FileFailed, null)
                return
            }
        try {
            if (!state.value.draft.hasFile(id)) return
            state.update { it.copy(draft = it.draft.fileStaged(id, staged.name, staged.mime, staged.size)) }
            var shown = -1L
            val path =
                files.upload(staged) { sent, total ->
                    // A state change per whole percent, not per buffer written.
                    val percent = if (total > 0) sent * 100 / total else 0
                    if (percent != shown) {
                        shown = percent
                        state.update { it.copy(draft = it.draft.fileProgress(id, sent, total)) }
                    }
                }
            state.update { it.copy(draft = it.draft.fileUploaded(id, path)) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(AttachLog.TAG, "couldn't upload a file: ${error.message}", error)
            val unavailable = error is UploadUnavailableException
            state.update {
                it.copy(draft = it.draft.removeFile(id), filesAvailable = if (unavailable) false else it.filesAvailable)
            }
            notify(HomeNotice.UploadFailed, staged.name)
        } finally {
            uploads.remove(id)
            withContext(NonCancellable) { files.discard(staged) }
        }
    }

    /** A file's ✕: while it uploads the upload is cancelled; after, it leaves the draft (the computer's copy stays). */
    fun removeFile(id: Long) {
        uploads.remove(id)?.cancel()
        state.update { it.copy(draft = it.draft.removeFile(id)) }
    }

    /** Cancels the uploads of files no longer on the draft. */
    private fun cancelUploadsOffDraft() {
        val onDraft =
            state.value.draft.files
                .map { it.id }
                .toSet()
        uploads.keys.filter { it !in onDraft }.forEach { uploads.remove(it)?.cancel() }
    }

    private fun addImages(sources: List<String>) {
        if (sources.isEmpty()) return
        var ids: List<Long> = emptyList()
        state.update {
            val (draft, added) = it.draft.addImages(sources.size)
            ids = added
            it.copy(draft = draft)
        }
        ids.zip(sources).forEach { (id, source) ->
            viewModelScope.launch {
                try {
                    val prepared = images.prepare(source)
                    var kept = false
                    state.update {
                        kept = it.draft.hasImage(id)
                        if (kept) it.copy(draft = it.draft.imagePrepared(id, prepared.file, prepared.kind)) else it
                    }
                    // Removed, or the draft cleared, while it was being prepared.
                    if (!kept) sweepImages()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.w(AttachLog.TAG, "couldn't prepare an image: ${error.message}", error)
                    state.update { it.copy(draft = it.draft.removeImage(id)) }
                    notify(HomeNotice.AttachFailed, null)
                }
            }
        }
    }

    /** An image's ✕. */
    fun removeImage(id: Long) {
        state.update { it.copy(draft = it.draft.removeImage(id)) }
        sweepImages()
    }

    /**
     * Deletes every prepared image nothing refers to any more: not on the draft, not being sent, not held for Undo and
     * not waiting to send.
     */
    private fun sweepImages() {
        // What's kept is decided now, as things stand at the call, not when the deleting runs.
        val current = state.value
        val drafts = listOf(current.draft) + inFlight + listOfNotNull(undoable?.draft)
        val files =
            (drafts.flatMap { it.images } + current.queuedImages.values.flatten())
                .mapNotNull { it.file }
                .toSet()
        viewModelScope.launch { images.keepOnly(files) }
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

    /** Settings › Model changed: today's session follows it, and whether it can see images is asked again. */
    fun modelChanged(model: ModelChoice) {
        session?.switchModel(model)
        checkImageInput()
    }

    /** Settings › Project changed: today's session is the one in the new project. */
    fun projectChanged() {
        session?.open()
    }

    // --- Needs you (app-plan.md, Needs you and control › Approvals and Questions)

    /** Items being answered from this phone: their leaving the queue isn't "No longer needed". */
    private val settling = mutableSetOf<String>()

    /** Opening the next pending item 0.3 s after a reply. */
    private var nextNeeds: Job? = null

    /**
     * The amber hint, the top bar's Needs input, or a Needs you row in Details ([itemId]): opens the sheet for that
     * item, else the oldest pending one. Approvals and questions share one queue.
     */
    fun openNeeds(itemId: String? = null) {
        nextNeeds?.cancel()
        val pending = state.value.today.pending
        val item = pending.firstOrNull { it.id == itemId } ?: pending.firstOrNull() ?: return
        state.update {
            val form = item.form
            val answers =
                if (form != null && item.id !in it.answers) it.answers + (item.id to QuestionAnswers(form)) else it.answers
            it.copy(needs = NeedsSheet(item.id), answers = answers, detailsOpen = false)
        }
    }

    /** Hide: the sheet closes unanswered; the hint stays. */
    fun hideNeeds() {
        nextNeeds?.cancel()
        state.update { it.copy(needs = null) }
    }

    /** Allow once, Always, or Deny (with the spoken note, else the default one) on the open approval. */
    fun approve(decision: ApprovalDecision) {
        val item = state.value.needsItem ?: return
        if (state.value.needs?.busy != null) return
        val note = state.value.notes[item.id]
        val action =
            when (decision) {
                ApprovalDecision.Once -> NeedsAction.Once
                ApprovalDecision.Always -> NeedsAction.Always
                ApprovalDecision.Reject -> NeedsAction.Deny
            }
        settle(item, action) { it.reply(item, decision, note) }
    }

    /** Submit, on the open question's last page. */
    fun submitAnswers() {
        val item = state.value.needsItem ?: return
        val answers = state.value.answers[item.id] ?: return
        if (state.value.needs?.busy != null || state.value.sheetTake != null) return
        if (!answers.last || !answers.answer.answered) return
        settle(item, NeedsAction.Submit) { it.answer(item, answers.body()) }
    }

    /** Dismiss: the form closes with the note, so the run goes on. */
    fun dismissQuestion() {
        val item = state.value.needsItem ?: return
        if (state.value.needs?.busy != null) return
        settle(item, NeedsAction.Dismiss) { it.dismiss(item) }
    }

    private fun settle(
        item: PendingItem,
        action: NeedsAction,
        call: suspend (TodaySession) -> Unit,
    ) {
        val today = session ?: return
        settling += item.id
        state.update { it.copy(needs = it.needs?.copy(busy = action)) }
        viewModelScope.launch {
            try {
                call(today)
                closeNeeds(item.id, gone = false)
            } catch (error: CancellationException) {
                throw error
            } catch (_: RequestGoneException) {
                closeNeeds(item.id, gone = true)
            } catch (error: Exception) {
                Log.w(NEEDS_TAG, "couldn't answer ${item.id}: ${error.message}")
                state.update { it.copy(needs = it.needs?.copy(busy = null), notice = HomeNotice.NeedsFailed) }
            } finally {
                settling -= item.id
            }
        }
    }

    /**
     * The sheet closes at once and the item leaves the queue (with "No longer needed" when it was [gone]); if another
     * is pending, its sheet opens 0.3 s later.
     */
    private fun closeNeeds(
        itemId: String,
        gone: Boolean,
    ) {
        state.update {
            it.copy(
                needs = if (it.needs?.itemId == itemId) null else it.needs,
                notes = it.notes - itemId,
                answers = it.answers - itemId,
                notice = if (gone) HomeNotice.NeedsGone else it.notice,
            )
        }
        if (gone) return
        nextNeeds?.cancel()
        nextNeeds =
            viewModelScope.launch {
                delay(NEXT_NEEDS_MS)
                val left =
                    state.value.today.pending
                        .filterNot { it.id == itemId || it.id in settling }
                // Not over Details: it shows what's left.
                if (state.value.needs == null && !state.value.detailsOpen && left.isNotEmpty()) openNeeds(left.first().id)
            }
    }

    /** The open item left the queue without this phone answering it: answered elsewhere, or its run ended. */
    private fun watchNeeds(today: TodayState) {
        val open = state.value.needs ?: return
        if (open.itemId in settling || today.pending.any { it.id == open.itemId }) return
        state.update { it.copy(needs = null, notice = HomeNotice.NeedsGone) }
    }

    /** A tap on an option of the open question: chooses it, or in a multiselect toggles it. */
    fun chooseOption(value: String) = changeAnswers { it.choose(value) }

    /** A tap on the own answer that has text: chooses it again (multiselect: toggles it). */
    fun toggleOwnAnswer() = changeAnswers { it.toggleOwn() }

    fun nextQuestion() {
        if (state.value.sheetTake != null) return
        changeAnswers { it.next() }
    }

    fun previousQuestion() = changeAnswers { it.back() }

    private fun changeAnswers(change: (QuestionAnswers) -> QuestionAnswers) {
        val id = state.value.needs?.itemId ?: return
        state.update { s -> s.answers[id]?.let { s.copy(answers = s.answers + (id to change(it))) } ?: s }
    }

    /**
     * Say why, or Say your own answer, is pressed: a take starts, as the talk button's (app-plan.md, Approvals › Say
     * why). False when one is running already, here or on the talk button.
     */
    fun sheetTakeStart(target: SheetTarget): Boolean {
        val current = state.value
        if (current.sheetTake != null || current.talk != TalkMode.Idle || current.draft.isListening) return false
        sheetPressedAt = clock()
        state.update { it.copy(sheetTake = SheetTake(target)) }
        dictation.start {
            if (state.value.sheetTake?.target == target) {
                state.update { it.copy(sheetTake = null, notice = HomeNotice.MicUnavailable).withGoalTake(null) }
            }
        }
        return true
    }

    /** When the spoken row was pressed: under [SHEET_TAP_MS] it was a tap. */
    private var sheetPressedAt = 0L

    /**
     * The row is released: the take ends and is turned into text, which becomes Deny's note or the own answer (chosen
     * once spoken). A take with no speech keeps what was said before. Pressed for under [SHEET_TAP_MS] it was a tap and
     * records nothing: on the own answer a tap chooses it (multiselect: toggles it); on Say why it does nothing.
     */
    fun sheetTakeEnd() {
        val take = state.value.sheetTake ?: return
        if (take.transcribing) return
        if (clock() - sheetPressedAt < SHEET_TAP_MS) {
            dictation.drop()
            state.update { it.copy(sheetTake = null).withGoalTake(null) }
            if (take.target is SheetTarget.Own) ownTapped(take.target)
            return
        }
        state.update { it.copy(sheetTake = take.copy(transcribing = true)).withGoalTake(GoalTake.Transcribing) }
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            val samples = dictation.end()
            val text =
                if (samples == null || samples.size < MIN_SAMPLES) {
                    null
                } else {
                    sheetText(samples)
                }
            state.update { s -> s.withSheetText(take.target, text?.trim()?.ifEmpty { null }).copy(sheetTake = null).withGoalTake(null) }
        }
    }

    /** A sheet take's text, once the speech model is ready; null when it can't be had. */
    private suspend fun sheetText(samples: FloatArray): String? {
        if (dictation.speech.first { it !is SpeechStatus.Loading } != SpeechStatus.Ready) return null
        return transcribing.withLock {
            try {
                dictation.transcribe(samples)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TalkLog.TAG, "a sheet take didn't become text: ${error.message}", error)
                null
            }
        }
    }

    private fun HomeUiState.withSheetText(
        target: SheetTarget,
        text: String?,
    ): HomeUiState =
        when {
            text == null -> this
            target is SheetTarget.Note -> copy(notes = notes + (target.itemId to text))
            target is SheetTarget.Own -> onField(target) { it.own(text) }
            target is SheetTarget.Goal -> copy(goalSheet = goalSheet?.copy(edit = GoalEdit(text)))
            else -> this
        }

    /** A tap on the own answer of [target]'s field. */
    private fun ownTapped(target: SheetTarget.Own) = state.update { it.onField(target) { answers -> answers.toggleOwn() } }

    /** [change] applied on [target]'s page, whichever page shows. */
    private fun HomeUiState.onField(
        target: SheetTarget.Own,
        change: (QuestionAnswers) -> QuestionAnswers,
    ): HomeUiState {
        val answers = answers[target.itemId] ?: return this
        val index =
            answers.form.fields
                .indexOfFirst { it.key == target.key }
                .takeIf { it >= 0 } ?: return this
        val changed = change(answers.copy(page = index))
        return copy(answers = this.answers + (target.itemId to changed.copy(page = answers.page)))
    }

    // --- The goal (app-plan.md, Goal and Ask › The goal sheet)

    /** The take on the goal sheet's box: [take] while it runs, null once it's done (a box that got no text closes). */
    private fun HomeUiState.withGoalTake(take: GoalTake?): HomeUiState {
        val sheet = goalSheet ?: return this
        if (sheetTake != null && sheetTake.target != SheetTarget.Goal && take != null) return this
        val edit = sheet.edit
        val next =
            when {
                take != null -> (edit ?: GoalEdit()).copy(take = take)
                edit?.text != null -> edit.copy(take = null)
                else -> null
            }
        return copy(goalSheet = sheet.copy(edit = next))
    }

    /** ◎ or the goal line: the goal sheet opens. */
    fun openGoal() = state.update { it.copy(goalSheet = GoalSheet(), draftMenu = false) }

    /** Done, or a tap outside: the sheet closes and a take's text is let go. */
    fun closeGoal() {
        if (state.value.sheetTake?.target == SheetTarget.Goal) return
        state.update { it.copy(goalSheet = null) }
    }

    /** "Say the goal", Change, "New goal" or Again is held: a take into the box, as Say why (false when it can't). */
    fun goalTakeStart(): Boolean {
        if (state.value.goalSheet?.busy != null) return false
        if (!sheetTakeStart(SheetTarget.Goal)) return false
        state.update { it.withGoalTake(GoalTake.Listening) }
        return true
    }

    /** Cancel: the box shows the goal again. */
    fun cancelGoalEdit() {
        if (state.value.sheetTake?.target == SheetTarget.Goal) return
        state.update { it.copy(goalSheet = it.goalSheet?.copy(edit = null)) }
    }

    /** "Set goal" or "Replace goal": the take's text, unless it's refused; the sheet closes once it's set. */
    fun setGoal() {
        val sheet = state.value.goalSheet ?: return
        val text =
            sheet.edit
                ?.takeIf { it.take == null }
                ?.text
                ?.trim()
                ?.ifEmpty { null } ?: return
        if (sheet.busy != null) return
        refusal(text)?.let {
            notify(it, null)
            return
        }
        runGoal(GoalAction.Change, GoalCommand.Set(text))
    }

    fun pauseGoal() = runGoal(GoalAction.Pause, GoalCommand.Pause)

    fun resumeGoal() = runGoal(GoalAction.Resume, GoalCommand.Resume)

    fun clearGoal() = runGoal(GoalAction.Clear, GoalCommand.Clear)

    private fun refusal(text: String): HomeNotice? =
        when (GoalText.refusal(text)) {
            GoalRefusal.Word -> HomeNotice.GoalRefusedWord
            GoalRefusal.TooLong -> HomeNotice.GoalRefusedLong
            null -> null
        }

    /** A goal button: its spinner while the request runs, then the notice; Set and Clear close the sheet once done. */
    private fun runGoal(
        action: GoalAction,
        command: GoalCommand,
    ) {
        val today = session ?: return
        if (state.value.goalSheet?.busy != null || state.value.sheetTake != null) return
        val replacing = state.value.today.goal != null
        state.update { it.copy(goalSheet = it.goalSheet?.copy(busy = action)) }
        viewModelScope.launch {
            val outcome = today.goal(command)
            val busy = state.value.busy
            val closes = outcome == GoalOutcome.Done && (command is GoalCommand.Set || command == GoalCommand.Clear)
            state.update {
                it.copy(
                    goalSheet = if (closes) null else it.goalSheet?.copy(busy = null),
                    notice = goalNotice(command, outcome, replacing, busy),
                    noticeDetail = null,
                )
            }
        }
    }

    private fun goalNotice(
        command: GoalCommand,
        outcome: GoalOutcome,
        replacing: Boolean,
        busy: Boolean,
    ): HomeNotice =
        when (outcome) {
            GoalOutcome.Stale -> HomeNotice.GoalStale
            GoalOutcome.Failed -> HomeNotice.GoalFailed
            GoalOutcome.Done ->
                when (command) {
                    is GoalCommand.Set ->
                        when {
                            replacing && busy -> HomeNotice.GoalReplacedBusy
                            replacing -> HomeNotice.GoalReplacedIdle
                            busy -> HomeNotice.GoalSetBusy
                            else -> HomeNotice.GoalSetIdle
                        }
                    GoalCommand.Pause -> HomeNotice.GoalPaused
                    GoalCommand.Resume -> HomeNotice.GoalResumed
                    GoalCommand.Clear -> if (busy) HomeNotice.GoalClearedBusy else HomeNotice.GoalCleared
                }
        }

    // --- The draft's ⋯ (app-plan.md, Set as goal from a draft and Ask on the side)

    fun toggleDraftMenu() = state.update { it.copy(draftMenu = !it.draftMenu && !it.draft.isEmpty) }

    fun closeDraftMenu() = state.update { it.copy(draftMenu = false) }

    /**
     * Set as (or Replace) today's goal: the draft's text, as Send makes it, with its images as `files` unless the model
     * can't see them. Refused for a lone goal command word or over 4,000 characters, keeping the draft; on success the
     * draft clears with the notice Set gives; if it fails the draft comes back.
     */
    fun goalFromDraft() {
        val current = state.value
        if (!current.draftReady) return
        val draft = current.draft
        val objective = draft.text.trim()
        // The draft's files go as path lines after the objective (app-plan.md, Attach files › Set as goal).
        val text = PromptFiles.text(objective, draft.files.mapNotNull { it.prompt })
        (refusal(objective) ?: refusal(text))?.let {
            state.update { s -> s.copy(draftMenu = false) }
            notify(it, null)
            return
        }
        val today = session ?: return
        val images = if (current.seesImages) AttachNames.of(draft.images) else emptyList()
        val replacing = current.today.goal != null
        state.update { it.copy(draft = it.draft.clear(), draftMenu = false, sendMode = SendMode.AfterTurn) }
        inFlight += draft
        viewModelScope.launch {
            val outcome =
                try {
                    today.goal(GoalCommand.Set(text), images)
                } finally {
                    inFlight -= draft
                }
            if (outcome != GoalOutcome.Done) restore(draft)
            state.update {
                it.copy(notice = goalNotice(GoalCommand.Set(text), outcome, replacing, it.busy), noticeDetail = null)
            }
            sweepImages()
        }
    }

    /** Ask on the side: the draft's text is asked; the draft clears and the answer shows under the status card. */
    fun askFromDraft() {
        val current = state.value
        if (!current.draftReady) return
        val text = current.draft.text.trim()
        state.update { it.copy(draft = it.draft.clear(), draftMenu = false, sendMode = SendMode.AfterTurn) }
        sweepImages()
        session?.ask(text)
    }

    fun retryAsk() {
        session?.retryAsk()
    }

    fun closeAsk() {
        session?.closeAsk()
    }

    fun openAsk() {
        session?.openAsk()
    }

    fun foldAsk() {
        session?.foldAsk()
    }

    /** Copy put the answer on the clipboard; [confirm] where the system doesn't say so itself. */
    fun askCopied(confirm: Boolean) {
        if (confirm) notify(HomeNotice.AskCopied, null)
    }

    /** A row of Details' Asked on the side: Details closes and it shows as the open Ask message. */
    fun showAsked(index: Int) {
        closeDetails()
        session?.showAsked(index)
    }

    // --- Details (app-plan.md, Needs you and control › Details)

    /** Reading Recent: at once, or a second after a tool or a run finishes. */
    private var recentJob: Job? = null

    /** Reading Running now a second after a run finishes. */
    private var runningLater: Job? = null

    /**
     * "Details ›", the to-do row, or the waiting line: opens Details, with what was read in the background, and reads
     * Running now and Recent again quietly.
     */
    fun openDetails() {
        state.update { it.copy(detailsOpen = true) }
        readRunning()
        readRecent()
    }

    fun closeDetails() {
        state.update { it.copy(detailsOpen = false) }
    }

    private fun readRunning(after: Long = 0) {
        val today = session ?: return
        runningLater?.cancel()
        val job =
            viewModelScope.launch {
                delay(after)
                try {
                    today.readRunning()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.w(NEEDS_TAG, "couldn't read Running now: ${error.message}")
                }
                state.update { it.copy(runningRead = true) }
            }
        if (after > 0) runningLater = job
    }

    private fun readRecent(after: Long = 0) {
        val today = session ?: return
        recentJob?.cancel()
        recentJob =
            viewModelScope.launch {
                delay(after)
                try {
                    val steps = today.recent()
                    state.update { it.copy(recent = steps, recentRead = true) }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.w(NEEDS_TAG, "couldn't read Recent: ${error.message}")
                    state.update { it.copy(recentRead = true) }
                }
            }
    }

    /**
     * Details' reads (app-plan.md, Details › Size and first load): in the background when today's session opens (the
     * old one's rows go) and a second after each run ends, so the sheet usually opens with them in place; while it's
     * open, Recent again 1 s after each tool, and Running now again after a reconnect.
     */
    private fun watchDetails(
        before: TodayState?,
        now: TodayState,
    ) {
        val opened = (now.setup as? SessionSetup.Ready)?.sessionId
        if (opened != null && opened != (before?.setup as? SessionSetup.Ready)?.sessionId) {
            state.update { it.copy(recent = emptyList(), recentRead = false, runningRead = false) }
            readRunning()
            readRecent()
            return
        }
        if (before == null) return
        if (before.busySince != null && now.busySince == null) {
            readRecent(after = RECENT_DELAY_MS)
            readRunning(after = RECENT_DELAY_MS)
            return
        }
        if (!state.value.detailsOpen) return
        if (now.toolEvents != before.toolEvents) readRecent(after = RECENT_DELAY_MS)
        if (now.streamUp && !before.streamUp) readRunning()
    }

    // --- Stop (app-plan.md, Needs you and control › Stop)

    private var stopTimer: Job? = null

    /** Stop is pressed: the hold starts when it's lit; a dimmed stop does nothing (false). */
    fun stopPressed(): Boolean {
        val current = state.value
        if (!current.stopLive || current.stopHolding || current.stopping) return false
        val at = clock()
        state.update { it.copy(stopHolding = true, stopHeldAt = at) }
        stopTimer =
            viewModelScope.launch {
                delay(STOP_HOLD_MS)
                stopHeld()
            }
        return true
    }

    /** Let go before 3 s, or slid off the button: under 0.35 s "Hold to stop", else "Stop cancelled". */
    fun stopReleased() {
        val current = state.value
        if (!current.stopHolding) return
        stopTimer?.cancel()
        val held = clock() - current.stopHeldAt
        state.update { it.copy(stopHolding = false, notice = if (held < STOP_TAP_MS) HomeNotice.StopTap else HomeNotice.StopCancelled) }
    }

    /**
     * Stop through a screen reader's confirmation ("Stop the agent?" › Stop, app-plan.md, Accessibility): the same steps
     * as at 3 s, without the hold. Nothing while stop is dimmed.
     */
    fun stopConfirmed() {
        val current = state.value
        if (!current.stopLive || current.stopHolding || current.stopping) return
        stopHeld()
    }

    /** 3 s held: the warning goes as the interrupt is sent, and the notice says how it went. */
    private fun stopHeld() {
        val today = session
        state.update { it.copy(stopHolding = false, stopping = true) }
        viewModelScope.launch {
            val outcome = today?.stop() ?: StopOutcome.Failed
            val notice =
                when (outcome) {
                    is StopOutcome.Stopped -> if (outcome.waiting) HomeNotice.StoppedWaiting else HomeNotice.Stopped
                    StopOutcome.NothingToStop -> HomeNotice.NothingToStop
                    StopOutcome.Failed -> HomeNotice.StopFailed
                }
            state.update { it.copy(stopping = false, notice = notice) }
        }
    }

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

    /**
     * Settings › Start a new session (app-plan.md, Settings and finish (M6)): the day's next session replaces today's on
     * screen. What belonged to the old one closes (its sheets, Details); the draft stays, since it never left the
     * phone. Started shows "New session" on home.
     */
    suspend fun startNewSession(): NewSessionOutcome {
        val today = session ?: return NewSessionOutcome.Failed
        val outcome = today.startNew()
        if (outcome != NewSessionOutcome.Failed) {
            nextNeeds?.cancel()
            state.update {
                it.copy(
                    needs = null,
                    detailsOpen = false,
                    goalSheet = null,
                    draftMenu = false,
                    notice = if (outcome == NewSessionOutcome.Started) HomeNotice.NewSession else it.notice,
                )
            }
        }
        return outcome
    }

    /** Pair again with the same computer: today's session opens again with the new sign-in. */
    fun pairedAgain(name: String) {
        session?.open()
        notify(HomeNotice.PairedAgain, name)
    }

    /** The computer was forgotten or switched: today's session there is no longer followed. */
    fun shutDown() {
        session?.close()
    }

    val level: Float? get() = dictation.level

    /** Home is on screen (and the app in front): the microphone gets ready for a press. */
    fun homeShown() = dictation.warmUp()

    /** Home left the screen, or the app went to the background. */
    fun homeHidden() = dictation.coolDown()

    /** One chunk, or one part of a locked take, is transcribed at a time; takes keep recording meanwhile. */
    private val transcribing = Mutex()

    /**
     * A take's parts (app-plan.md, Dictation › Locked takes). The pause detector listens from the start of every take,
     * so a take locked after a few seconds keeps the cuts already found; its parts are only turned into text once it's
     * locked, one at a time, in order. A take released while held is turned into text whole, and its cuts are let go.
     */
    private class Split(
        val id: Long,
        val incoming: Channel<TakePart>,
    ) {
        class Part(
            val audio: TakePart,
            var text: String? = null,
            var failed: Boolean = false,
        )

        /** The parts received since the take was locked, in order, with their text once they have it. */
        val parts = mutableListOf<Part>()

        /** Turning the parts into text, from the lock on. */
        var job: Job? = null

        /** The cutting broke: the take is turned into text whole after release, as one not cut. */
        var broken = false

        val failed: Boolean get() = parts.any { it.failed }

        /** The text so far: a part's text only shows once every part before it has text. */
        val soFar: String get() = joinParts(parts.takeWhile { it.text != null }.map { it.text.orEmpty() })

        fun stop() {
            job?.cancel()
            incoming.cancel()
        }
    }

    /** The take recording now, while its pauses are found. */
    private var split: Split? = null

    /** Takes whose parts failed, by chunk, kept until Try again gives every part its text. */
    private val failedParts = mutableMapOf<Long, Split>()

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
        dictation.loadNow()
        listenForPauses()
        return true
    }

    /** From the start of every take, its pauses are found as it records (app-plan.md, Dictation › Locked takes). */
    private fun listenForPauses() {
        val take = state.value.draft.live ?: return
        split?.stop()
        val incoming = Channel<TakePart>(Channel.UNLIMITED)
        split = if (dictation.split(incoming)) Split(take.id, incoming) else null
    }

    /**
     * Debug builds (`--ei HexCodePressProbe`): [cycles] times, the talk button goes down, is held 1 s and comes up,
     * through [press] and [release] as a finger would, 1.5 s apart. Dictation logs how soon audio came after each press.
     */
    suspend fun pressProbe(cycles: Int) {
        repeat(cycles) { i ->
            Log.i(TalkLog.TAG, "press probe ${i + 1}/$cycles")
            if (press()) {
                delay(PROBE_HOLD_MS)
                release(TalkRelease.End)
            }
            delay(PROBE_GAP_MS)
        }
        Log.i(TalkLog.TAG, "press probe done")
    }

    /**
     * Debug builds (`--ez HexCodeTakeProbe` with `--es HexCodeTakeAudio`): once speech is ready, one locked take as
     * long as the file plus [tail], started with a tap and finished with one, as a finger would. Dictation logs when
     * each part's text came, and how long after release the take had its text.
     */
    suspend fun takeProbe(
        seconds: Double,
        tail: Long = PROBE_TAIL_MS,
    ) {
        dictation.speech.first { it == SpeechStatus.Ready }
        // The pause detector loads just after the model.
        delay(PROBE_SETTLE_MS)
        Log.i(TalkLog.TAG, "take probe: a locked take of %.1f s".format(seconds + tail / 1000.0))
        if (!press()) return
        release(TalkRelease.Tap)
        delay((seconds * 1000).toLong() + tail)
        tapLocked()
        val done = uiState.first { it.draft.isSettled }
        Log.i(TalkLog.TAG, "take probe done: ${done.draft.text.length} characters")
    }

    /** The microphone didn't start: the button goes back, and the notice says so. */
    private fun microphoneFailed() {
        if (!state.value.draft.isListening) return
        stopSplit()
        state.update { it.copy(draft = it.draft.dropTake(), talk = TalkMode.Idle, notice = HomeNotice.MicUnavailable) }
    }

    /** The talk button came up after a hold or drag; see [TalkRelease]. */
    fun release(how: TalkRelease) {
        if (state.value.talk != TalkMode.Holding) return
        when (how) {
            TalkRelease.Lock, TalkRelease.Tap -> {
                state.update { it.copy(talk = TalkMode.Locked) }
                transcribeParts()
            }
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

    /**
     * The take went hands-free: its parts are turned into text from now on, the ones cut before the lock first, if the
     * speech model is ready (app-plan.md, Dictation › Locked takes). Otherwise, or without the pause detector, it's
     * turned into text whole after release, as before.
     */
    private fun transcribeParts() {
        val take = state.value.draft.live ?: return
        val cutting = split?.takeIf { it.id == take.id }
        if (cutting == null) {
            Log.i(TalkLog.TAG, "take ${take.id} is locked, but pauses can't be found yet: it's transcribed whole")
            return
        }
        if (cutting.job != null) return
        if (state.value.speech != SpeechStatus.Ready) {
            Log.i(TalkLog.TAG, "take ${take.id} is locked before the speech model is ready: it waits whole")
            cutting.stop()
            split = null
            return
        }
        cutting.job =
            viewModelScope.launch {
                try {
                    for (audio in cutting.incoming) {
                        val part = Split.Part(audio)
                        cutting.parts += part
                        transcribePart(cutting, part)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    // The parts closed with an error: the cutting broke.
                    cutting.broken = true
                }
            }
    }

    /**
     * Turns one part of a locked take into text, and shows the take's text so far. A part that fails keeps its audio
     * for Try again; the parts after it still go on.
     */
    private suspend fun transcribePart(
        cutting: Split,
        part: Split.Part,
    ) {
        val audio = part.audio
        transcribing.withLock {
            val began = SystemClock.uptimeMillis()
            try {
                part.text = dictation.transcribe(audio.samples)
                part.failed = false
                state.update { it.copy(draft = it.draft.withSoFar(cutting.id, cutting.soFar)) }
                val now = SystemClock.uptimeMillis()
                Log.i(
                    TalkLog.TAG,
                    "take ${cutting.id} part ${audio.number}${if (audio.last) " (after release)" else ""}: %.1f s of audio, "
                        .format(audio.seconds) +
                        "text %d ms after its speech ended, %d ms after the cut, transcribed in %d ms"
                            .format(now - audio.speechEndedAt, now - audio.cutAt, now - began),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TalkLog.TAG, "take ${cutting.id} part ${audio.number} failed: ${error.message}", error)
                part.failed = true
            }
        }
    }

    /** A dropped take: a part being transcribed stops, the ones waiting aren't, and their text goes with the take. */
    private fun stopSplit() {
        val cutting = split ?: return
        split = null
        cutting.stop()
        Log.i(TalkLog.TAG, "take ${cutting.id} dropped after ${cutting.parts.count { it.text != null }} parts had text")
    }

    private fun endTake() {
        val started = state.value.takeStartedAt
        val releasedAt = SystemClock.uptimeMillis()
        val take = state.value.draft.live
        state.update { it.copy(draft = it.draft.endTake(durationMs = clock() - started), talk = TalkMode.Idle) }
        if (take == null) return
        val found = split?.takeIf { it.id == take.id }
        split = null
        // Only a locked take's parts are used; a take released while held is turned into text whole.
        val cutting = found?.takeIf { it.job != null }
        if (cutting == null) found?.stop()
        // Undispatched, so the end is queued behind this take's start now, before a next press can start another.
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            val samples = dictation.end()
            // A take with no audio, or under 0.3 s, leaves the draft quietly.
            if (samples == null || samples.size < MIN_SAMPLES) {
                cutting?.stop()
                val why = if (samples == null) "nothing was recording" else "${samples.size} samples, under 0.3 s"
                Log.i(TalkLog.TAG, "take ${take.id} left the draft: $why")
                state.update { it.copy(draft = it.draft.transcribed(take.id, null)) }
                return@launch
            }
            if (cutting != null) {
                finishSplit(cutting, samples, releasedAt)
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
            transcribe(take.id, samples, releasedAt)
        }
    }

    /**
     * A locked take ended: once its last part has text, the take has its parts' text, joined. Send waits for that, as
     * the chunk is Transcribing until then. A take with no cut at all, or whose cutting broke, is turned into text
     * whole. If a part failed, the chunk reads "Couldn't turn this take into text", and Try again redoes only the
     * failed parts.
     */
    private suspend fun finishSplit(
        cutting: Split,
        samples: FloatArray,
        releasedAt: Long,
    ) {
        cutting.job?.join()
        val id = cutting.id
        when {
            cutting.broken || cutting.parts.isEmpty() -> {
                if (cutting.broken) Log.i(TalkLog.TAG, "take $id: the cutting broke, so it's transcribed whole")
                transcribe(id, samples, releasedAt)
            }
            cutting.failed -> {
                failedParts[id] = cutting
                state.update { it.copy(draft = it.draft.failed(id, retry = true)) }
            }
            else -> {
                partsDone(cutting)
                Log.i(
                    TalkLog.TAG,
                    "take $id: text %d ms after release, %d parts, %.1f s in all"
                        .format(SystemClock.uptimeMillis() - releasedAt, cutting.parts.size, samples.size / 16_000.0),
                )
            }
        }
    }

    /** Every part of take [cutting] has text: the chunk has their text, joined (or leaves, with no words). */
    private fun partsDone(cutting: Split) {
        val text = cutting.soFar
        if (text.isBlank()) Log.i(TalkLog.TAG, "take ${cutting.id} left the draft: no speech in its parts")
        failedParts -= cutting.id
        state.update { it.copy(draft = it.draft.transcribed(cutting.id, text)) }
    }

    /** Try again on a take whose parts failed: only those parts are turned into text again, in order. */
    private suspend fun retryParts(cutting: Split) {
        for (part in cutting.parts) if (part.failed) transcribePart(cutting, part)
        if (cutting.failed) {
            state.update { it.copy(draft = it.draft.failed(cutting.id, retry = true)) }
        } else {
            partsDone(cutting)
        }
    }

    /** Turns take [id]'s [samples] into text; a failure keeps the chunk, and its audio for Try again. */
    private suspend fun transcribe(
        id: Long,
        samples: FloatArray,
        releasedAt: Long? = null,
    ) {
        transcribing.withLock {
            try {
                val text = dictation.transcribe(samples)
                // A silent take leaves quietly too.
                if (text.isBlank()) Log.i(TalkLog.TAG, "take $id left the draft: no speech in ${samples.size} samples")
                state.update { it.copy(draft = it.draft.transcribed(id, text)) }
                if (releasedAt != null) {
                    Log.i(
                        TalkLog.TAG,
                        "take $id: text %d ms after release, whole, %.1f s"
                            .format(SystemClock.uptimeMillis() - releasedAt, samples.size / 16_000.0),
                    )
                }
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
                    val cutting = failedParts[id]
                    if (cutting != null) {
                        retryParts(cutting)
                        continue
                    }
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

    fun setSendMode(mode: SendMode) = state.update { it.copy(sendMode = mode) }

    /**
     * Send (app-plan.md, Sending): the draft clears at once. An idle agent gets it now (`steer`); a working one after
     * this turn (`queue`), or, with Now, after 3 s held on the phone with Undo, as a correction (`steer`). The switch
     * goes back to After turn. If sending fails, the draft comes back as it was, images and all.
     *
     * Its images go in the same request (app-plan.md, Attach › Sending), unless the model can't see them: then only the
     * text goes, with "Sent without the images".
     */
    fun send() {
        val current = state.value
        val draft = current.draft
        if (!current.canSend) return
        val plan = Sending.plan(current.busy, current.sendMode)
        val text = draft.text
        val withImages = if (current.seesImages) AttachNames.of(draft.images) else emptyList()
        val dropped = draft.images.isNotEmpty() && withImages.isEmpty()
        // Uploaded files go as path lines after the text, and in `metadata` (app-plan.md, Attach files › The prompt).
        val withFiles = draft.files.mapNotNull { it.prompt }
        val message = Message(PromptFiles.text(text, withFiles), withImages, PromptFiles.metadata(text, withFiles))
        val files = withFiles.size
        state.update { it.copy(draft = it.draft.clear(), sendMode = SendMode.AfterTurn) }
        when (plan) {
            SendPlan.Idle ->
                viewModelScope.launch {
                    if (deliver(plan, message, draft) != null) {
                        notify(if (dropped) HomeNotice.SentNoImages else HomeNotice.SentIdle, text, withImages.size, files)
                        sweepImages()
                    }
                }
            SendPlan.Queue ->
                viewModelScope.launch {
                    val id = deliver(plan, message, draft) ?: return@launch
                    if (withImages.isNotEmpty()) {
                        state.update { it.copy(queuedImages = it.queuedImages + (id to draft.images)) }
                    }
                    if (draft.files.isNotEmpty()) {
                        state.update { it.copy(queuedFiles = it.queuedFiles + (id to draft.files)) }
                    }
                    undoable = Undoable.Queued(id, draft)
                    notify(if (dropped) HomeNotice.QueuedNoImages else HomeNotice.Queued, null)
                    // Held for Waiting to send and for Undo; anything else can go.
                    sweepImages()
                }
            SendPlan.Now -> {
                val job =
                    viewModelScope.launch {
                        delay(Sending.HOLD_MS)
                        undoable = null
                        // Held through a reconnect, it goes once the stream is back.
                        session?.awaitStream()
                        if (deliver(plan, message, draft) != null) {
                            notify(if (dropped) HomeNotice.SentNoImages else HomeNotice.SentNow, text, withImages.size, files)
                            sweepImages()
                        }
                    }
                undoable = Undoable.Held(job, draft)
                notify(HomeNotice.SendingNow, text, withImages.size, files)
            }
        }
    }

    /** What a Send puts in the request: the text with its path lines, the images, and the files' `metadata`. */
    private class Message(
        val text: String,
        val images: List<PromptImage>,
        val metadata: JsonObject?,
    )

    /**
     * Sends [message] as [plan] says; on failure the [draft] comes back with "Couldn't send", its files already
     * uploaded. Returns the inbox item's id. The caller lets the images go ([sweepImages]) once it has kept what it
     * needs of them.
     */
    private suspend fun deliver(
        plan: SendPlan,
        message: Message,
        draft: Draft,
    ): String? {
        val today = session
        inFlight += draft
        return try {
            today ?: error("No session to send to")
            today.send(Sending.text(plan, message.text), Sending.delivery(plan), message.images, message.metadata)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TalkLog.TAG, "couldn't send: ${error.message}", error)
            restore(draft)
            notify(HomeNotice.SendFailed, null)
            null
        } finally {
            inFlight -= draft
        }
    }

    /** The draft as it was before Send, unless a new one has started meanwhile, which then follows it. */
    private fun restore(draft: Draft) = state.update { it.copy(draft = it.draft.restored(draft)) }

    private fun notify(
        notice: HomeNotice,
        detail: String?,
        images: Int = 0,
        files: Int = 0,
    ) = state.update {
        it.copy(notice = notice, noticeDetail = detail?.ifBlank { null }, noticeImages = images, noticeFiles = files)
    }

    /** The notice whose action just ran: its dismissal mustn't clear the notice the action put up instead. */
    private var actioned: HomeNotice? = null

    /** The notice's action: Undo a deleted chunk, a held Now, or a queued message. */
    fun noticeAction() {
        actioned = state.value.notice
        when (state.value.notice) {
            HomeNotice.ChunkDeleted -> undoDelete()
            HomeNotice.SendingNow, HomeNotice.Queued, HomeNotice.QueuedNoImages -> undoSend()
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
                        // Its images come back onto the draft with the text, so they can be sent again.
                        restore(taking.draft)
                        state.update {
                            it.copy(queuedImages = it.queuedImages - taking.itemId, queuedFiles = it.queuedFiles - taking.itemId)
                        }
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

    /** Tapping the card's Next: its text becomes a chunk of the draft, opening the draft if there's none. Never sends. */
    fun addToDraft(text: String) {
        // While a take records, the tap does nothing: the draft is busy taking that take.
        if (state.value.draft.isListening) return
        state.update { it.copy(draft = it.draft.withText(text)) }
    }

    /** Try again, on takes that couldn't be turned into text: the model loads again if it has to. */
    fun retryTakes() {
        state.update { it.copy(draft = it.draft.retryFailed()) }
        if (state.value.speech == SpeechStatus.Ready) drainWaiting() else dictation.reload()
    }

    private fun dropTake() {
        stopSplit()
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
        if (state.value.talk == TalkMode.Holding) {
            state.update { it.copy(talk = TalkMode.Locked) }
            transcribeParts()
        }
    }

    fun clearDraft() {
        state.update { it.copy(draft = it.draft.clear()) }
        cancelUploadsOffDraft()
        failedParts.keys.retainAll(
            state.value.draft.chunks
                .map { it.id }
                .toSet(),
        )
        if (state.value.draft.isEmpty) viewModelScope.launch { takes.clear() }
        sweepImages()
    }

    /** The notice has gone. After its action, only that notice is cleared, not "Not sent" or another that followed. */
    fun noticeShown() {
        val after = actioned
        actioned = null
        if (after != null && state.value.notice != after) return
        // A queued message's Undo goes with its notice; the images it held for that can go too.
        if (state.value.notice in setOf(HomeNotice.Queued, HomeNotice.QueuedNoImages) && undoable is Undoable.Queued) {
            undoable = null
            sweepImages()
        }
        state.update { it.copy(notice = null, noticeDetail = null, noticeImages = 0, noticeFiles = 0) }
    }

    override fun onCleared() {
        stopSplit()
        if (state.value.draft.isListening) dictation.drop()
    }
}

/** 0.3 s at 16 kHz: a shorter take leaves the draft quietly. */
private const val MIN_SAMPLES = 4_800

/** A spoken row (Say why, an own answer) pressed for less than this is a tap: it records nothing. */
const val SHEET_TAP_MS = 380L

/** Where the sheets and Details log what went wrong. */
private const val NEEDS_TAG = "HexCode.Needs"

/** After a reply, the next pending item's sheet opens this much later. */
const val NEXT_NEEDS_MS = 300L

/** Details reads Recent again this long after a tool finishes, and Recent and Running now after a run ends. */
private const val RECENT_DELAY_MS = 1_000L

/** Stop needs this long a hold; a hold shorter than [STOP_TAP_MS] is a tap ("Hold to stop"). */
const val STOP_HOLD_MS = 3_000L
const val STOP_TAP_MS = 350L

/** How often a locked take's new audio goes to the pause detector. */
private const val VAD_POLL_MS = 50L

/** The take probe's silence after the file, and its wait for the pause detector after the model is ready. */
private const val PROBE_TAIL_MS = 500L
private const val PROBE_SETTLE_MS = 2_000L

/** The press probe holds the button this long, and waits this long between presses. */
private const val PROBE_HOLD_MS = 1_000L
private const val PROBE_GAP_MS = 1_500L

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
    private val startLoad: () -> Unit = {},
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

    override fun loadNow() = startLoad()
}
