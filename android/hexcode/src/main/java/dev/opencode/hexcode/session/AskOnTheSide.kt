package dev.opencode.hexcode.session

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable

/** A closed answer, kept on the phone: the question, its answer, and when it was asked. */
@Serializable
data class AskedAnswer(
    val question: String,
    val answer: String,
    /** When it was asked, in [System.currentTimeMillis] time. */
    val askedAt: Long,
)

/** How an Ask stands. */
sealed interface AskReply {
    /** "Reading the session…": the request runs. */
    data object Asking : AskReply

    /** The answer, trimmed. */
    data class Answered(
        val text: String,
    ) : AskReply

    /** "No answer": the reply was empty, which means the model tried to use a tool. */
    data object Empty : AskReply

    /** "Couldn't answer": an error, a `503` or the timeout. */
    data object Failed : AskReply
}

/** The Ask message under the status card: one at a time, folded unless [open]. */
data class AskMessage(
    val question: String,
    val askedAt: Long,
    val reply: AskReply,
    val open: Boolean = false,
) {
    val answer: String? get() = (reply as? AskReply.Answered)?.text

    fun kept(): AskedAnswer? = answer?.let { AskedAnswer(question, it, askedAt) }
}

/** The shown message and the closed answers, newest first. */
data class AskState(
    val message: AskMessage? = null,
    val history: List<AskedAnswer> = emptyList(),
)

/** One session's answers as the phone keeps them: the shown one, if answered, and the closed ones, newest first. */
@Serializable
data class SavedAsks(
    val sessionId: String,
    val shown: AskedAnswer? = null,
    val history: List<AskedAnswer> = emptyList(),
)

/** Where the answers are kept: one session's (today's) at a time, so saving forgets the one before. */
interface AskStore {
    suspend fun loadAsks(): SavedAsks?

    suspend fun saveAsks(saved: SavedAsks)
}

/** Today's session is gone (`404`): the request is dropped and setup runs again. */
class SessionGoneException(
    message: String? = null,
) : Exception(message)

/**
 * Ask on the side (app-plan.md, Ask on the side): a question answered by [generate] (`POST /api/session/{id}/generate`
 * with [SessionPrompts.ask]); nothing is added to the session. Independent of the summary: the two can run together.
 * One message shows at a time; a new Ask moves an answered one into the history (at most [HISTORY] answers, newest
 * first) and abandons an unanswered one, whose reply is then ignored. The shown answer and the history are saved in
 * [store] for today's session, so they survive the app being closed; a new session starts with none.
 */
class AskOnTheSide(
    private val scope: CoroutineScope,
    private val generate: suspend (prompt: String) -> String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val store: AskStore? = null,
    private val timeoutMs: Long = TIMEOUT_MS,
    /** A `404`: today's session is gone, so setup runs again. */
    private val onSessionGone: () -> Unit = {},
) {
    private val mutable = MutableStateFlow(AskState())
    val state: StateFlow<AskState> = mutable.asStateFlow()

    @Volatile
    private var sessionId: String? = null
    private var request: Job? = null

    /** Goes up with every change made here, so a slow load never replaces what was asked meanwhile. */
    @Volatile
    private var changes = 0

    /** Today's session is [id]: its saved answers come back; another session's are not shown. */
    fun open(id: String) {
        if (sessionId == id) return
        sessionId = id
        request?.cancel()
        mutable.value = AskState()
        val at = ++changes
        scope.launch {
            val saved =
                try {
                    store?.loadAsks()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                } ?: return@launch
            if (saved.sessionId != id || sessionId != id || changes != at) return@launch
            mutable.value =
                AskState(
                    saved.shown?.let { AskMessage(it.question, it.askedAt, AskReply.Answered(it.answer)) },
                    saved.history.take(HISTORY),
                )
        }
    }

    /** Asks [question]: the message shows "Reading the session…" until the answer comes. */
    fun ask(question: String) {
        val text = question.trim()
        if (text.isEmpty()) return
        change { it.withShownKept().copy(message = AskMessage(text, clock(), AskReply.Asking)) }
        send(text)
    }

    /** Try again, after "No answer" or "Couldn't answer": the same question again. */
    fun retry() {
        val message = mutable.value.message ?: return
        if (message.reply != AskReply.Failed && message.reply != AskReply.Empty) return
        change { it.copy(message = message.copy(askedAt = clock(), reply = AskReply.Asking, open = false)) }
        send(message.question)
    }

    /** ✕: an answered message goes into the history; an unanswered or failed one is dropped. */
    fun close() {
        request?.cancel()
        change { it.withShownKept().copy(message = null) }
    }

    /** A tap on the folded answer opens it. */
    fun expand() = change { s -> s.copy(message = s.message?.takeIf { it.answer != null }?.copy(open = true) ?: s.message) }

    fun fold() = change { s -> s.copy(message = s.message?.copy(open = false)) }

    /** A row of Details' Asked on the side: it leaves the history and shows, open; the one shown goes into history. */
    fun show(index: Int) {
        val row = mutable.value.history.getOrNull(index) ?: return
        request?.cancel()
        change {
            val rest = it.copy(history = it.history - row)
            rest.withShownKept().copy(message = AskMessage(row.question, row.askedAt, AskReply.Answered(row.answer), open = true))
        }
    }

    private fun AskState.withShownKept(): AskState {
        request?.cancel()
        val kept = message?.kept() ?: return copy(message = null)
        return copy(message = null, history = (listOf(kept) + history).take(HISTORY))
    }

    private fun send(question: String) {
        request?.cancel()
        val asked = mutable.value.message ?: return

        // Still this message, still asking: anything else means it was abandoned, and its reply is ignored.
        fun current() =
            mutable.value.message.let {
                it?.askedAt == asked.askedAt &&
                    it.question == asked.question &&
                    it.reply == AskReply.Asking
            }
        request =
            scope.launch {
                val reply =
                    try {
                        // A timeout counts as a failure, not as a cancellation.
                        val text = withTimeoutOrNull(timeoutMs) { generate(SessionPrompts.ask(question)) }?.trim()
                        when {
                            text == null -> AskReply.Failed
                            text.isEmpty() -> AskReply.Empty
                            else -> AskReply.Answered(text)
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: SessionGoneException) {
                        if (current()) change { it.copy(message = null) }
                        onSessionGone()
                        return@launch
                    } catch (error: Exception) {
                        Log.w(TAG, "couldn't answer: ${error.message}")
                        AskReply.Failed
                    }
                if (!current()) return@launch
                change { s -> s.copy(message = s.message?.copy(reply = reply)) }
            }
    }

    private fun change(how: (AskState) -> AskState) {
        val before = mutable.value
        mutable.update(how)
        if (mutable.value == before) return
        changes++
        save()
    }

    private fun save() {
        val id = sessionId ?: return
        val now = mutable.value
        val saved = SavedAsks(id, now.message?.kept(), now.history)
        scope.launch {
            try {
                store?.saveAsks(saved)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // They show; only the next launch misses them.
            }
        }
    }

    companion object {
        /** An Ask that takes longer fails, as the summary (app-plan.md, Ask on the side › Timeout). */
        const val TIMEOUT_MS = 60_000L

        /** Closed answers kept: Details shows them all. */
        const val HISTORY = 5

        private const val TAG = "HexCode.Ask"
    }
}
