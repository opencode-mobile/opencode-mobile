package dev.opencode.hexcode.session

import android.util.Log
import dev.opencode.hexcode.firstrun.ModelChoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What home and Settings show about artifacts. */
data class ArtifactsUi(
    val state: ArtifactsState = ArtifactsState.Checking,
    /** The enable or turn-off flow, while one is open or runs. */
    val setup: ArtifactSetup? = null,
    /** The one-time hint shows. */
    val hint: Boolean = false,
)

/** What a summary refresh does with the card's artifact tile. */
sealed interface ArtifactRead {
    /** The list couldn't be read: the tile shown stays. */
    data object Keep : ArtifactRead

    data class Set(
        val tile: ArtifactTile?,
    ) : ArtifactRead
}

/**
 * Artifacts for today's session (app-plan.md, Artifacts (M8)): whether they're on, read from the list route at setup,
 * with each summary refresh and a second after a config change; `artifact-rules` put on today's session while they're
 * on and removed as soon as they're off or changed; the newest artifact of the latest run for the card's tile; the
 * one-time hint; and the enable flow ([setup]). A server that doesn't declare the routes, or answers them in a way the
 * app doesn't know, switches the feature off quietly for the connection.
 */
class ArtifactsFeature(
    private val scope: CoroutineScope,
    private val api: ArtifactsApi,
    private val store: ArtifactsStore,
    directory: () -> String,
    model: () -> ModelChoice?,
    private val configDelayMs: Long = CONFIG_DELAY_MS,
    checkMs: Long = ArtifactSetupFlow.CHECK_MS,
    timeoutMs: Long = ArtifactSetupFlow.TIMEOUT_MS,
) {
    private val known = MutableStateFlow(ArtifactsState.Checking)
    private val hint = MutableStateFlow(false)
    private val directory = directory

    val setup =
        ArtifactSetupFlow(
            scope,
            api,
            store,
            directory,
            model,
            onApplied = { mode -> settle(if (mode == SetupMode.On) ArtifactsState.On else ArtifactsState.Off) },
            onRestored = { check() },
            checkMs = checkMs,
            timeoutMs = timeoutMs,
        )

    val state: StateFlow<ArtifactsUi> =
        combine(known, setup.state, hint) { state, flow, shown -> ArtifactsUi(state, flow, shown) }
            .stateIn(scope, SharingStarted.Eagerly, ArtifactsUi())

    /** Today's session, once it's open. */
    @Volatile
    private var rootId: String? = null

    /** Whether `artifact-rules` is on [rootId] as far as this phone knows; null before the first put or delete. */
    private var ruled: Boolean? = null

    /** Whether the server declares the routes, read once per connection. */
    @Volatile
    private var declared: Boolean? = null

    private val rules = Mutex()
    private var configCheck: Job? = null

    /** Tool names of today's session by call id, for the hint. */
    private val tools = HashMap<String, String>()

    /** Today's session opened (or another took its place): its state is read and its rule put or removed. */
    fun opened(rootId: String) {
        this.rootId = rootId
        ruled = null
        tools.clear()
        scope.launch { check() }
    }

    /** The computer was forgotten or switched: the next one is read afresh. */
    fun closed() {
        rootId = null
        ruled = null
        declared = null
        known.value = ArtifactsState.Checking
        setup.close()
    }

    /** Reads the state now (setup, a config change, after Restore). */
    suspend fun check() {
        if (!isDeclared()) return settle(ArtifactsState.Unavailable)
        settle(read(api.list(directory())))
    }

    /**
     * With each summary refresh: the state, and the card's artifact from the latest run of [rootId] or one of its
     * [children]. Off or changed is no tile and no error.
     */
    suspend fun tile(
        rootId: String,
        children: Set<String>,
    ): ArtifactRead {
        if (!isDeclared()) {
            settle(ArtifactsState.Unavailable)
            return ArtifactRead.Set(null)
        }
        val (answer, runStart) =
            coroutineScope {
                val list = async { api.list(directory()) }
                val start = async { runCatching { api.runStart(rootId) }.getOrNull() }
                list.await() to start.await()
            }
        settle(read(answer))
        return when (answer) {
            is ArtifactListAnswer.On -> ArtifactRead.Set(NewestArtifact.of(answer.items, rootId, children, runStart))
            ArtifactListAnswer.Off, ArtifactListAnswer.Changed -> ArtifactRead.Set(null)
            ArtifactListAnswer.SignedOut, ArtifactListAnswer.Unknown -> ArtifactRead.Keep
        }
    }

    /** One line from the event stream. */
    fun line(line: String) {
        val event = ArtifactEvent.decode(line) ?: return
        setup.event(event)
        when (event) {
            ArtifactEvent.ConfigUpdated -> {
                // A config file changed: read the state again a second later (the watcher reloads within about 0.3 s).
                configCheck?.cancel()
                configCheck =
                    scope.launch {
                        delay(configDelayMs)
                        check()
                    }
            }
            is ArtifactEvent.ToolNamed -> if (event.sessionId == rootId) tools[event.id] = event.name
            is ArtifactEvent.ToolCalled -> if (event.sessionId == rootId) maybeHint(tools[event.id], event.path)
            else -> Unit
        }
    }

    /** The stream reconnected. */
    fun reconnected() = setup.reconnected()

    /**
     * The one-time hint (artifacts-proposal.md §2.5): while artifacts are off and available, the first time today's run
     * writes a file ending `.html`. Once per phone.
     */
    private fun maybeHint(
        tool: String?,
        path: String?,
    ) {
        if (tool != "write" && tool != "edit") return
        if (path?.lowercase()?.endsWith(".html") != true) return
        if (known.value != ArtifactsState.Off) return
        scope.launch {
            if (store.hintSeen()) return@launch
            store.setHintSeen()
            hint.value = true
        }
    }

    /** The hint was tapped, closed or timed out. */
    fun hintDone() {
        hint.value = false
    }

    private suspend fun isDeclared(): Boolean {
        declared?.let { return it }
        val read =
            try {
                api.declared()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
        // Unreadable: try again next time, and don't switch anything off meanwhile.
        if (read != null) declared = read
        return read ?: true
    }

    /** The state an answer means; null keeps the last one. */
    private fun read(answer: ArtifactListAnswer): ArtifactsState? =
        when (answer) {
            is ArtifactListAnswer.On -> ArtifactsState.On
            ArtifactListAnswer.Off -> ArtifactsState.Off
            ArtifactListAnswer.Changed -> {
                // Changed stays for this connection; the next setup checks again.
                declared = false
                ArtifactsState.Unavailable
            }
            ArtifactListAnswer.SignedOut, ArtifactListAnswer.Unknown -> null
        }

    /** Takes [state] and puts or removes the rule to match it. */
    private suspend fun settle(state: ArtifactsState?) {
        state ?: return
        known.value = state
        if (state == ArtifactsState.On) hint.value = false
        val root = rootId ?: return
        val want = state == ArtifactsState.On
        rules.withLock {
            if (ruled == want || rootId != root) return
            try {
                if (want) api.putRules(root) else api.deleteRules(root)
                ruled = want
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "artifact-rules ${if (want) "put" else "delete"}: ${error.message}")
            }
        }
    }

    companion object {
        private const val TAG = "HexCode.Artifacts"

        /** After `config.updated`. */
        const val CONFIG_DELAY_MS = 1_000L
    }
}
