package dev.opencode.hexcode.session

import android.util.Log
import dev.opencode.hexcode.firstrun.ModelChoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Turning artifacts on or off. */
enum class SetupMode { On, Off }

/** One step of the enable sheet's list (variant A): Sent → Agent working → Approval → Checking → On. */
enum class SetupStepState { Todo, Doing, Dimmed, Warn, Done, Failed }

data class SetupSteps(
    val sent: SetupStepState = SetupStepState.Todo,
    val work: SetupStepState = SetupStepState.Todo,
    /** Only once an approval came. */
    val approval: SetupStepState? = null,
    val check: SetupStepState = SetupStepState.Todo,
    val end: SetupStepState = SetupStepState.Todo,
)

/** The agent's live line while it works, from its tool events. */
sealed interface SetupActivity {
    data object Thinking : SetupActivity

    data class Reading(
        val path: String,
    ) : SetupActivity

    data class Editing(
        val path: String,
    ) : SetupActivity

    data class Tool(
        val name: String,
    ) : SetupActivity
}

/** Where the enable (or turn-off) sheet is. */
sealed interface SetupStage {
    /** Reading the config before anything can be shown. */
    data object Reading : SetupStage

    /** What artifacts are, the file, the exact prompt, where it goes; Cancel / Send to the agent. */
    data object Explain : SetupStage

    /** The prompt can't go: only the line to copy. */
    data class Blocked(
        val why: Why,
    ) : SetupStage {
        enum class Why {
            /** `OPENCODE_CONFIG_CONTENT` wins: add the line there and restart OpenCode. */
            Env,

            /** The file is there but OpenCode can't read it (already broken, or empty). */
            Broken,

            /** The config couldn't be read. */
            NoConfig,

            /** This OpenCode doesn't have artifacts, or answers in a way the app doesn't know. */
            Unavailable,
        }
    }

    /** Sent; the steps tick, and the 2 s check runs. */
    data object Running : SetupStage

    data object Done : SetupStage

    /** The line to copy and Try again; the 2 s check keeps running, so adding the line by hand flips it. */
    data class Fallback(
        val why: Why,
        /** The agent's reason after "FAILED:", or the error's message. */
        val detail: String? = null,
    ) : SetupStage {
        enum class Why { Rejected, Failed, FreeLimit, TimedOut, StillOff, Restored }
    }

    /** The edit broke the file, or keys outside `experimental` went: what's missing, and Restore. */
    data class Damaged(
        val damage: ConfigDamage,
    ) : SetupStage
}

/** The enable or turn-off flow, as the sheet and Settings show it. */
data class ArtifactSetup(
    val mode: SetupMode,
    val stage: SetupStage = SetupStage.Reading,
    val target: ConfigTarget? = null,
    /** The exact text sent to the agent. */
    val prompt: String? = null,
    /** The setup session exists already and is reused. */
    val reused: Boolean = false,
    val steps: SetupSteps = SetupSteps(),
    val activity: SetupActivity? = null,
    /** The setup session's approval waiting now. */
    val approval: PendingItem? = null,
    /** Which answer is on its way: true Allow once, false Reject. */
    val answering: Boolean? = null,
    /** The agent's replies so far, for "See what the agent says". */
    val replies: List<String> = emptyList(),
    /** The sheet is hidden; the flow keeps running. */
    val hidden: Boolean = false,
    val restoring: Boolean = false,
    val restoreFailed: Boolean = false,
    /** Send was tapped and the session is being made or prompted. */
    val sending: Boolean = false,
) {
    /** The 2 s check runs. */
    val watching: Boolean get() = stage == SetupStage.Running || stage is SetupStage.Fallback

    /** The person has something to do: an approval, a fallback, a broken config. */
    val needsYou: Boolean get() = approval != null || stage is SetupStage.Fallback || stage is SetupStage.Damaged

    /** Nothing more will happen by itself. */
    val settled: Boolean get() = stage == SetupStage.Done || stage is SetupStage.Blocked || stage is SetupStage.Damaged
}

/**
 * Turning artifacts on or off by asking OpenCode's own agent (app-plan.md, Artifacts (M8)), in a separate, locked-down
 * "Hex Code setup" session that is made once and reused. Before sending it reads which file wins and saves its exact
 * text; after sending it asks the list route every 2 s ([checkMs]) whatever the agent says, then compares the config
 * with the saved copy. A file the edit broke can be restored byte for byte. [onApplied] runs when the change took
 * effect (the feature then puts or removes `artifact-rules`).
 */
class ArtifactSetupFlow(
    private val scope: CoroutineScope,
    private val api: ArtifactsApi,
    private val store: ArtifactsStore,
    private val directory: () -> String,
    private val model: () -> ModelChoice?,
    private val onApplied: suspend (SetupMode) -> Unit = {},
    /** After Restore: the feature reads its state again. */
    private val onRestored: suspend () -> Unit = {},
    private val checkMs: Long = CHECK_MS,
    private val timeoutMs: Long = TIMEOUT_MS,
) {
    private val mutable = MutableStateFlow<ArtifactSetup?>(null)
    val state: StateFlow<ArtifactSetup?> = mutable.asStateFlow()

    /** The config as it was before sending, and the target file's exact bytes (null: it didn't exist). */
    private var before: ConfigDocuments? = null
    private var saved: ByteArray? = null
    private var location: String = ""

    @Volatile
    private var setupId: String? = null
    private var work: Job? = null
    private var loop: Job? = null
    private var timer: Job? = null

    /** Tool names by call id, from `session.tool.input.started`. */
    private val tools = HashMap<String, String>()

    /** The agent wrote a file in this run: the check also looks for a broken config. */
    @Volatile
    private var edited = false

    /** Goes up each time the flow stops, so a check still on its way for an earlier one changes nothing. */
    @Volatile
    private var generation = 0

    private fun set(change: (ArtifactSetup) -> ArtifactSetup) = mutable.update { it?.let(change) }

    private val current: ArtifactSetup? get() = mutable.value

    /**
     * Opens the sheet for [mode]: a flow already running shows again; otherwise the config is read and the sheet
     * explains what will be sent.
     */
    fun start(mode: SetupMode) {
        val now = current
        if (now != null && now.mode == mode && (now.stage == SetupStage.Running || now.watching || now.stage is SetupStage.Damaged)) {
            set { it.copy(hidden = false) }
            return
        }
        stop()
        mutable.value = ArtifactSetup(mode)
        work = scope.launch { prepare(mode) }
    }

    /** Reads the state, which file wins, and that file's exact text. */
    private suspend fun prepare(mode: SetupMode) {
        val dir = directory()
        location = dir
        try {
            when (api.list(dir)) {
                is ArtifactListAnswer.On -> if (mode == SetupMode.On) return already(mode)
                ArtifactListAnswer.Off -> if (mode == SetupMode.Off) return already(mode)
                ArtifactListAnswer.Changed -> return block(SetupStage.Blocked.Why.Unavailable)
                ArtifactListAnswer.SignedOut, ArtifactListAnswer.Unknown -> Unit
            }
            val config = api.config(dir) ?: return block(SetupStage.Blocked.Why.NoConfig)
            val target = ConfigTarget.of(config) ?: return block(SetupStage.Blocked.Why.NoConfig)
            set { it.copy(target = target) }
            if (target !is ConfigTarget.File) return block(SetupStage.Blocked.Why.Env)
            saved =
                when (val read = api.readFile(target.path)) {
                    is FileRead.Exists -> {
                        // A file that's there but parses to nothing is never sent the prompt's "create it".
                        if (config.document(target.path) == null) return block(SetupStage.Blocked.Why.Broken)
                        read.bytes
                    }
                    FileRead.Missing -> null
                    FileRead.Unknown -> return block(SetupStage.Blocked.Why.NoConfig)
                }
            before = config
            val prompt = if (mode == SetupMode.On) ArtifactPrompts.turnOn(target.path) else ArtifactPrompts.turnOff(target.path)
            val reused = store.setupSession()?.second == dir
            set { it.copy(stage = SetupStage.Explain, prompt = prompt, reused = reused) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "couldn't read the config: ${error.message}")
            block(SetupStage.Blocked.Why.NoConfig)
        }
    }

    private suspend fun already(mode: SetupMode) {
        set { it.copy(stage = SetupStage.Done, steps = DONE_STEPS) }
        onApplied(mode)
    }

    private fun block(why: SetupStage.Blocked.Why) = set { it.copy(stage = SetupStage.Blocked(why)) }

    /** Send to the agent: the setup session (made once, then reused) gets the prompt; the check starts. */
    fun send() {
        val flow = current ?: return
        if (flow.stage != SetupStage.Explain || flow.sending) return
        val prompt = flow.prompt ?: return
        set {
            it.copy(
                sending = true,
                stage = SetupStage.Running,
                steps = SetupSteps(sent = SetupStepState.Doing),
                activity = null,
                approval = null,
                replies = emptyList(),
            )
        }
        edited = false
        tools.clear()
        work =
            scope.launch {
                try {
                    val id = setupSession()
                    setupId = id
                    api.prompt(id, prompt)
                    set {
                        it.copy(
                            sending = false,
                            reused = true,
                            steps = it.steps.copy(sent = SetupStepState.Done, work = SetupStepState.Doing, check = SetupStepState.Doing),
                        )
                    }
                    watch()
                    startTimer()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.w(TAG, "couldn't send the setup prompt: ${error.message}")
                    set { it.copy(sending = false, steps = it.steps.copy(sent = SetupStepState.Failed)) }
                    fallback(SetupStage.Fallback.Why.Failed, error.message)
                }
            }
    }

    /** The saved setup session in this project, if it's still there; else a new one with the lockdown rules. */
    private suspend fun setupSession(): String {
        val dir = location
        store.setupSession()?.let { (id, savedDir) ->
            if (savedDir == dir && api.sessionExists(id) == true) return id
        }
        val folder = before?.directory ?: ((current?.target as? ConfigTarget.File)?.path?.substringBeforeLast('/') ?: "")
        val id = api.createSetupSession(dir, model(), ArtifactPrompts.lockdown(folder))
        store.saveSetupSession(id, dir)
        return id
    }

    /** The 2 s check, while the run goes and while a fallback shows. */
    private fun watch() {
        if (loop?.isActive == true) return
        loop =
            scope.launch {
                while (isActive) {
                    delay(checkMs)
                    if (current?.watching != true) break
                    check(final = false)
                }
            }
    }

    /** 60 s of the agent working without a result, not counting the time an approval waits for the person. */
    private fun startTimer() {
        timer?.cancel()
        timer =
            scope.launch {
                var waited = 0L
                while (waited < timeoutMs) {
                    delay(TIMER_STEP_MS)
                    val flow = current ?: return@launch
                    if (flow.stage != SetupStage.Running) return@launch
                    if (flow.approval == null) waited += TIMER_STEP_MS
                }
                if (current?.stage == SetupStage.Running) {
                    set { it.copy(steps = it.steps.copy(work = SetupStepState.Failed, check = SetupStepState.Warn)) }
                    fallback(SetupStage.Fallback.Why.TimedOut)
                }
            }
    }

    /**
     * One check: the list route says whether the change took effect (never the agent's reply); then the config is
     * compared with the saved copy. While still off after an edit, a target that no longer parses counts as broken.
     * [final] is the check when the run ended: still off then is the fallback.
     */
    private suspend fun check(final: Boolean) {
        val flow = current ?: return
        val run = generation
        val wanted = if (flow.mode == SetupMode.On) ArtifactListAnswer.On::class else ArtifactListAnswer.Off::class
        val answer =
            try {
                api.list(location)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                ArtifactListAnswer.Unknown
            }
        if (run != generation || current?.watching != true) return
        if (wanted.isInstance(answer)) return applied()
        if (!(final || edited)) return
        if (damaged()) return
        if (final && current?.stage == SetupStage.Running) {
            val reason =
                current
                    ?.replies
                    ?.lastOrNull()
                    ?.trim()
                    ?.takeIf { it.startsWith("FAILED") }
                    ?.removePrefix("FAILED")
                    ?.trimStart(':', ' ')
            set { it.copy(steps = it.steps.copy(check = SetupStepState.Warn)) }
            if (reason != null) {
                fallback(SetupStage.Fallback.Why.Failed, reason.ifBlank { null })
            } else {
                fallback(SetupStage.Fallback.Why.StillOff)
            }
        }
    }

    /** The change took effect: the config is compared with the saved copy before it counts as done. */
    private suspend fun applied() {
        val flow = current ?: return
        val damage = damage() ?: return
        timer?.cancel()
        if (damage.any) {
            set { it.copy(stage = SetupStage.Damaged(damage), activity = null, steps = it.steps.copy(check = SetupStepState.Warn)) }
        } else {
            set {
                it.copy(
                    stage = SetupStage.Done,
                    activity = null,
                    approval = null,
                    steps =
                        it.steps.copy(
                            sent = SetupStepState.Done,
                            work = if (it.steps.work == SetupStepState.Failed) it.steps.work else SetupStepState.Done,
                            check = SetupStepState.Done,
                            end = SetupStepState.Done,
                        ),
                )
            }
        }
        loop?.cancel()
        onApplied(flow.mode)
    }

    /** Still off after an edit: whether the edit broke the file (then the sheet shows Restore). */
    private suspend fun damaged(): Boolean {
        val damage = damage() ?: return false
        if (!damage.unreadable) return false
        timer?.cancel()
        loop?.cancel()
        set { it.copy(stage = SetupStage.Damaged(damage), activity = null, steps = it.steps.copy(check = SetupStepState.Warn)) }
        return true
    }

    /** The config now against the saved copy; null when it couldn't be read. */
    private suspend fun damage(): ConfigDamage? {
        val old = before ?: return ConfigDamage()
        val path = (current?.target as? ConfigTarget.File)?.path ?: return ConfigDamage()
        val now = api.config(location) ?: return null
        val exists = now.document(path) != null || (old.document(path) == null && api.readFile(path) is FileRead.Exists)
        return ConfigCheck.compare(old, now, path, exists)
    }

    private fun fallback(
        why: SetupStage.Fallback.Why,
        detail: String? = null,
    ) {
        timer?.cancel()
        set { it.copy(stage = SetupStage.Fallback(why, detail), activity = null, sending = false) }
        watch()
    }

    /** One event from the stream: only the setup session's count. */
    fun event(event: ArtifactEvent) {
        val id = setupId ?: return
        if (event.sessionId != id) return
        val flow = current ?: return
        when (event) {
            is ArtifactEvent.Reply -> set { it.copy(replies = it.replies + event.text) }
            is ArtifactEvent.ToolNamed -> tools[event.id] = event.name
            is ArtifactEvent.ToolCalled -> {
                if (flow.stage != SetupStage.Running) return
                val name = tools[event.id].orEmpty()
                val path = event.path
                val activity =
                    when {
                        path != null && name in READ_TOOLS -> SetupActivity.Reading(path)
                        path != null && name in EDIT_TOOLS -> SetupActivity.Editing(path)
                        name.isNotEmpty() -> SetupActivity.Tool(name)
                        else -> SetupActivity.Thinking
                    }
                set { it.copy(activity = activity) }
            }
            is ArtifactEvent.ToolEnded -> if (!event.failed && tools[event.id] in EDIT_TOOLS) edited = true
            is ArtifactEvent.RunStarted -> if (flow.stage == SetupStage.Running) set { it.copy(activity = SetupActivity.Thinking) }
            is ArtifactEvent.Approval -> if (flow.stage == SetupStage.Running) approvalAsked(event.item)
            is ArtifactEvent.ApprovalReplied ->
                if (flow.approval?.id == event.id) {
                    set { it.copy(approval = null, steps = it.steps.copy(approval = SetupStepState.Done, work = SetupStepState.Doing)) }
                }
            is ArtifactEvent.RunEnded -> if (flow.stage == SetupStage.Running) runEnded(event)
            ArtifactEvent.ConfigUpdated -> Unit
        }
    }

    private fun approvalAsked(item: PendingItem) {
        set {
            it.copy(
                approval = item,
                steps = it.steps.copy(approval = SetupStepState.Warn, work = SetupStepState.Dimmed),
            )
        }
    }

    private fun runEnded(event: ArtifactEvent.RunEnded) {
        val error = event.error
        if (error != null) {
            set { it.copy(steps = it.steps.copy(work = SetupStepState.Failed, check = SetupStepState.Todo)) }
            val why = if (error.freeLimit) SetupStage.Fallback.Why.FreeLimit else SetupStage.Fallback.Why.Failed
            fallback(why, error.message.ifBlank { null })
            return
        }
        set { it.copy(activity = null, steps = it.steps.copy(work = if (edited) SetupStepState.Done else SetupStepState.Failed)) }
        // The check once more now the run ended: it may already be on, or broken, or still off.
        scope.launch { check(final = true) }
    }

    /** The stream came back: the setup session's approval may have come or gone meanwhile. */
    fun reconnected() {
        val id = setupId ?: return
        if (current?.stage != SetupStage.Running) return
        scope.launch {
            val waiting =
                try {
                    api.approvals(id)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    return@launch
                }
            val first = waiting.firstOrNull()
            if (first != null && current?.approval == null) approvalAsked(first)
            if (first == null && current?.approval != null) {
                set { it.copy(approval = null, steps = it.steps.copy(approval = SetupStepState.Done, work = SetupStepState.Doing)) }
            }
        }
    }

    /** Allow once: never Always, whose `save: ["*"]` would allow every edit in the project. */
    fun allowOnce() = answer(allow = true)

    /** Reject: the file stays as it was; the sheet shows the line to copy. */
    fun reject() = answer(allow = false)

    private fun answer(allow: Boolean) {
        val flow = current ?: return
        val item = flow.approval ?: return
        if (flow.answering != null) return
        set { it.copy(answering = allow) }
        scope.launch {
            try {
                api.reply(item.sessionId, item.id, allow)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // Gone or answered elsewhere: the run carries on (or ended) without it.
                Log.w(TAG, "approval reply: ${error.message}")
            }
            if (allow) {
                set {
                    it.copy(
                        answering = null,
                        approval = null,
                        steps = it.steps.copy(approval = SetupStepState.Done, work = SetupStepState.Doing),
                    )
                }
            } else {
                set {
                    it.copy(
                        answering = null,
                        approval = null,
                        steps = it.steps.copy(approval = SetupStepState.Failed, work = SetupStepState.Failed, check = SetupStepState.Todo),
                    )
                }
                fallback(SetupStage.Fallback.Why.Rejected)
            }
        }
    }

    /**
     * Restore (app-plan.md, Artifacts (M8) › A broken config): writes the saved text back byte for byte to the same
     * path, then checks again. A file that didn't exist before gets an empty object. The only time the app writes a
     * config file.
     */
    fun restore() {
        val flow = current ?: return
        if (flow.stage !is SetupStage.Damaged || flow.restoring) return
        val path = (flow.target as? ConfigTarget.File)?.path ?: return
        set { it.copy(restoring = true, restoreFailed = false) }
        scope.launch {
            val written =
                try {
                    api.writeFile(path, saved ?: EMPTY_CONFIG, location)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.w(TAG, "restore: ${error.message}")
                    false
                }
            // OpenCode reads the file again about 0.1 s after it changes: check until it has, for a few seconds.
            var damage = if (written) damage() else null
            var waited = 0L
            while (written && (damage == null || damage.any) && waited < RESTORE_SETTLE_MS) {
                delay(RESTORE_STEP_MS)
                waited += RESTORE_STEP_MS
                damage = damage()
            }
            if (damage == null || damage.any) {
                set { it.copy(restoring = false, restoreFailed = true) }
                return@launch
            }
            set { it.copy(restoring = false) }
            fallback(SetupStage.Fallback.Why.Restored)
            onRestored()
        }
    }

    /** Try again: the config is read again and the same prompt goes to the same session. */
    fun tryAgain() {
        val flow = current ?: return
        if (flow.stage !is SetupStage.Fallback) return
        stop()
        mutable.value = ArtifactSetup(flow.mode, reused = true, hidden = flow.hidden)
        work =
            scope.launch {
                prepare(flow.mode)
                if (current?.stage == SetupStage.Explain) send()
            }
    }

    /** The sheet hides; a running flow keeps going and Settings shows its state. */
    fun hide() = set { it.copy(hidden = true) }

    fun show() = set { it.copy(hidden = false) }

    /** Cancel, Done or Close: a running flow only hides; anything else ends here. */
    fun close() {
        val flow = current ?: return
        if (flow.stage == SetupStage.Running) return hide()
        stop()
        mutable.value = null
    }

    private fun stop() {
        generation += 1
        work?.cancel()
        loop?.cancel()
        timer?.cancel()
        setupId = null
        edited = false
        tools.clear()
    }

    companion object {
        private const val TAG = "HexCode.Artifacts"

        /** While the flow runs, and while a fallback shows. */
        const val CHECK_MS = 2_000L

        /** The agent's run, approvals aside. */
        const val TIMEOUT_MS = 60_000L

        private const val TIMER_STEP_MS = 1_000L

        /** After Restore's write, how long the check waits for OpenCode to read the file again. */
        private const val RESTORE_SETTLE_MS = 5_000L
        private const val RESTORE_STEP_MS = 500L

        private val READ_TOOLS = setOf("read", "glob", "grep", "list")
        private val EDIT_TOOLS = setOf("edit", "write", "patch")

        private val EMPTY_CONFIG = "{}\n".toByteArray()

        private val DONE_STEPS =
            SetupSteps(SetupStepState.Done, SetupStepState.Done, null, SetupStepState.Done, SetupStepState.Done)
    }
}
