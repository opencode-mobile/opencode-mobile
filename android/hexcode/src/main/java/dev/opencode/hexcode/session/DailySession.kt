package dev.opencode.hexcode.session

import kotlinx.coroutines.CancellationException
import java.time.LocalDate

/** A session as the server lists it: enough to find today's. */
data class SessionListing(
    val id: String,
    val title: String,
    val directory: String,
    /** When it last changed, in [System.currentTimeMillis] time. */
    val updatedAt: Long,
)

/** What to do on open, or on coming back to the foreground. */
sealed interface DailySessionPlan {
    /** Today's session exists: open it as it is. */
    data class Reuse(
        val sessionId: String,
    ) : DailySessionPlan

    /** There is none: create one titled [title], add the message rules, and show the new-day notice when [newDay]. */
    data class Create(
        val title: String,
        val newDay: Boolean,
    ) : DailySessionPlan
}

/**
 * The daily session (app-plan.md, The daily session): one session a day per project, titled with the local date.
 * Yesterday's session is never changed.
 */
object DailySession {
    /** Today's title: the local date, `2026-11-05`. */
    fun title(date: LocalDate): String = date.toString()

    /**
     * Finds [today]'s session in [directory] among [sessions]: the most recently updated one titled with the date.
     * When there is none, it is created, and the new-day notice shows only when the date changed since the session
     * the app last opened ([lastOpenedOn]), so not on the first day after first run.
     */
    fun plan(
        today: LocalDate,
        directory: String,
        lastOpenedOn: LocalDate?,
        sessions: List<SessionListing>,
    ): DailySessionPlan {
        val title = title(today)
        val folder = normalise(directory)
        val match = sessions.filter { it.title == title && normalise(it.directory) == folder }.maxByOrNull { it.updatedAt }
        if (match != null) return DailySessionPlan.Reuse(match.id)
        return DailySessionPlan.Create(title, newDay = lastOpenedOn != null && lastOpenedOn != today)
    }

    /**
     * Whether coming back to the foreground needs a look at the sessions: the open session is from another day, or
     * none is open. A date that changes while the app is in front waits for this.
     */
    fun needsCheck(
        openedOn: LocalDate?,
        today: LocalDate,
    ): Boolean = openedOn != today

    /** The same folder written with or without a trailing slash. */
    private fun normalise(directory: String): String = directory.trimEnd('/').ifEmpty { directory }
}

/** The server calls the daily session needs. Round 2 implements them over OpenCode's API. */
interface DailySessionApi {
    /** The sessions in [directory]. */
    suspend fun sessions(directory: String): List<SessionListing>

    /** Creates a session and returns its id. */
    suspend fun create(
        title: String,
        directory: String,
    ): String

    /** `PUT /api/experimental/session/{id}/instructions/entries/{entryId}`. */
    suspend fun putInstruction(
        sessionId: String,
        entryId: String,
        text: String,
    )
}

/** How opening today's session went. */
sealed interface DailySessionResult {
    /** Open [sessionId] (the caller opens the event stream next, then saves today as the last date opened). */
    data class Opened(
        val sessionId: String,
        val created: Boolean,
        val newDay: Boolean,
    ) : DailySessionResult

    /** Finding, creating or adding the rules failed: "Couldn't open today's session" with Try again. */
    data object Failed : DailySessionResult
}

/**
 * Opens today's session: finds it, or creates it and adds the message rules ([SessionPrompts.MESSAGE_RULES]).
 * Silent: any failure is [DailySessionResult.Failed]. When the rules fail after the session was created, Try again
 * finds that session and adds them then, and still shows the new-day notice if it was due.
 */
class DailySessionSetup(
    private val api: DailySessionApi,
) {
    /** A session this created whose rules aren't in yet, and whether it was a new day. */
    private var unfinished: DailySessionResult.Opened? = null

    suspend fun open(
        today: LocalDate,
        directory: String,
        lastOpenedOn: LocalDate?,
    ): DailySessionResult =
        try {
            when (val plan = DailySession.plan(today, directory, lastOpenedOn, api.sessions(directory))) {
                is DailySessionPlan.Reuse -> {
                    val pending = unfinished?.takeIf { it.sessionId == plan.sessionId }
                    if (pending != null) {
                        addRules(pending.sessionId)
                        unfinished = null
                        pending
                    } else {
                        DailySessionResult.Opened(plan.sessionId, created = false, newDay = false)
                    }
                }
                is DailySessionPlan.Create -> {
                    val id = api.create(plan.title, directory)
                    val opened = DailySessionResult.Opened(id, created = true, newDay = plan.newDay)
                    unfinished = opened
                    addRules(id)
                    unfinished = null
                    opened
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            DailySessionResult.Failed
        }

    private suspend fun addRules(sessionId: String) =
        api.putInstruction(sessionId, SessionPrompts.MESSAGE_RULES_ID, SessionPrompts.MESSAGE_RULES)
}
