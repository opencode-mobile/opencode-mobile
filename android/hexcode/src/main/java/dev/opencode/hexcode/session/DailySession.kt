package dev.opencode.hexcode.session

import dev.opencode.hexcode.firstrun.ModelChoice
import kotlinx.coroutines.CancellationException
import java.time.LocalDate

/** A session as the server lists it: enough to find today's. */
data class SessionListing(
    val id: String,
    val title: String,
    /** `location.directory`, exactly as the server keeps it. */
    val directory: String,
    /** When it last changed, in [System.currentTimeMillis] time. */
    val updatedAt: Long,
    /** The start of its latest run (`time.busy`); null for a session that never ran. */
    val busySince: Long? = null,
)

/** One page of the session list, and the cursor to the next. */
data class SessionListingPage(
    val sessions: List<SessionListing>,
    val next: String?,
)

/** What to do on open, or on coming back to the foreground on a new date. */
sealed interface DailySessionPlan {
    /** Today's session exists: open it as it is. */
    data class Reuse(
        val session: SessionListing,
    ) : DailySessionPlan

    /** There is none: create one titled [title], and show the new-day notice when [newDay]. */
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
     * Where [title] stands among [date]'s sessions (app-plan.md, The daily session › Finding it): 1 for the plain date
     * (`2026-11-07`), n for the date, a space and n in brackets, 2 or more (`2026-11-07 (2)`); null for any other title
     * (`Notes 2026-11-07`, `2026-11-07 (1)`).
     */
    fun number(
        date: LocalDate,
        title: String,
    ): Int? {
        val plain = title(date)
        if (title == plain) return 1
        if (!title.startsWith("$plain (") || !title.endsWith(")")) return null
        val digits = title.substring(plain.length + 2, title.length - 1)
        if (digits.isEmpty() || digits.startsWith('0') || !digits.all { it in '0'..'9' }) return null
        return digits.toIntOrNull()?.takeIf { it >= 2 }
    }

    /**
     * Today's session among [sessions], in the server's order (newest update first): the one in [directory] with the
     * **highest number** ([number]), and among equal numbers the first, the most recently updated (app-plan.md, More
     * than one match). Never the most recently updated across numbers: an older session still running moves to the
     * top of the list. The server compares directories as exact strings, so the phone does too.
     */
    fun match(
        today: LocalDate,
        directory: String,
        sessions: List<SessionListing>,
    ): SessionListing? {
        var best: SessionListing? = null
        var bestNumber = 0
        for (session in sessions) {
            if (session.directory != directory) continue
            val n = number(today, session.title) ?: continue
            if (n > bestNumber) {
                best = session
                bestNumber = n
            }
        }
        return best
    }

    /** The title Start a new session gives: the date and one more than the highest number found, or the plain date. */
    fun nextTitle(
        today: LocalDate,
        directory: String,
        sessions: List<SessionListing>,
    ): String {
        val highest = match(today, directory, sessions)?.let { number(today, it.title) } ?: return title(today)
        return "${title(today)} (${highest + 1})"
    }

    /** The app last opened a session on an earlier date than [today]. False before the first. */
    fun isNewDay(
        lastOpenedOn: LocalDate?,
        today: LocalDate,
    ): Boolean = lastOpenedOn != null && lastOpenedOn < today

    /**
     * Reuse today's session, or create it. The new-day notice shows only when a session is created and the app last
     * opened one on an earlier date: not on the first day after first run, and not when today's session went missing
     * and is created again.
     */
    fun plan(
        today: LocalDate,
        directory: String,
        lastOpenedOn: LocalDate?,
        sessions: List<SessionListing>,
    ): DailySessionPlan =
        match(today, directory, sessions)?.let { DailySessionPlan.Reuse(it) }
            ?: DailySessionPlan.Create(title(today), newDay = isNewDay(lastOpenedOn, today))
}

/** The server calls the daily session needs, over OpenCode's API in the app ([OpenCodeTodayApi]). */
interface DailySessionApi {
    /**
     * Root, unarchived sessions in [directory] whose title contains [search], newest update first; or the page
     * after [cursor].
     */
    suspend fun sessionsPage(
        directory: String,
        search: String,
        limit: Int,
        cursor: String?,
    ): SessionListingPage

    /** Creates a session in [directory] with the chosen [model] and level. */
    suspend fun create(
        title: String,
        directory: String,
        model: ModelChoice?,
    ): SessionListing

    /** Puts [SessionPrompts.MESSAGE_RULES] on the session. The same text again changes nothing. */
    suspend fun putMessageRules(sessionId: String)
}

/** The last date the app opened a session on, saved on the phone. */
interface DailySessionMemory {
    suspend fun lastOpenedOn(): LocalDate?

    suspend fun setLastOpenedOn(date: LocalDate)

    /** The session the app last opened, so a new day can ask whether yesterday's goal still runs; null before. */
    suspend fun lastSessionId(): String? = null

    suspend fun setLastSessionId(id: String) = Unit
}

/** How opening today's session went. */
sealed interface DailySessionResult {
    /** Open [session]; the caller opens the event stream next. */
    data class Opened(
        val session: SessionListing,
        val created: Boolean,
        val newDay: Boolean,
        /** With [newDay]: the session the app opened before, yesterday's, when it's known. */
        val previousId: String? = null,
    ) : DailySessionResult

    /** Finding, creating or putting the rules failed: "Couldn't open today's session" with Try again. */
    data object Failed : DailySessionResult
}

/** How Start a new session went. */
sealed interface NewSessionResult {
    /** [session] was created and has the rules: open it. */
    data class Opened(
        val session: SessionListing,
    ) : NewSessionResult

    /** Creating it failed: today's session is still open, nothing changed. */
    data object CreateFailed : NewSessionResult

    /** It was created, but its rules failed: "Couldn't open today's session", whose Try again finds it. */
    data object RulesFailed : NewSessionResult
}

/**
 * Opens today's session: finds it, or creates it with the chosen model, and puts the message rules on it every time.
 * Silent: any failure is [DailySessionResult.Failed]. Try again finds the session again, so it never makes a second
 * one, and still shows the new-day notice when it was due. The date is saved as the last one opened once it worked.
 */
class DailySessionSetup(
    private val api: DailySessionApi,
    private val memory: DailySessionMemory,
) {
    /** A session an earlier try created for a new day, before it failed. */
    private var noticeDueFor: String? = null

    suspend fun open(
        today: LocalDate,
        directory: String,
        model: ModelChoice?,
    ): DailySessionResult =
        try {
            val lastOpenedOn = memory.lastOpenedOn()
            val found = find(today, directory)
            val opened =
                if (found != null) {
                    val notice = noticeDueFor == found.id && DailySession.isNewDay(lastOpenedOn, today)
                    DailySessionResult.Opened(found, created = false, newDay = notice)
                } else {
                    val session = api.create(DailySession.title(today), directory, model)
                    val notice = DailySession.isNewDay(lastOpenedOn, today)
                    if (notice) noticeDueFor = session.id
                    DailySessionResult.Opened(session, created = true, newDay = notice)
                }
            api.putMessageRules(opened.session.id)
            val previous = if (opened.newDay) previousOf(opened.session.id) else null
            noticeDueFor = null
            memory.setLastOpenedOn(today)
            remember(opened.session.id)
            opened.copy(previousId = previous)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            DailySessionResult.Failed
        }

    /** The session opened before [current], as the phone saved it; null when it isn't known or is the same one. */
    private suspend fun previousOf(current: String): String? =
        try {
            memory.lastSessionId()?.takeIf { it != current }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }

    /** Saves the session just opened; only a new day's goal check misses it if this fails. */
    private suspend fun remember(id: String) {
        try {
            memory.setLastSessionId(id)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            Unit
        }
    }

    /** Today's session ([DailySession.match]) among every page [list] reads. */
    private suspend fun find(
        today: LocalDate,
        directory: String,
    ): SessionListing? = DailySession.match(today, directory, list(today, directory))

    /**
     * The day's sessions as the server lists them, newest update first: the next page is read while a page comes back
     * full, up to [MAX_PAGES], since a higher number may be on a later page.
     */
    private suspend fun list(
        today: LocalDate,
        directory: String,
    ): List<SessionListing> {
        val all = mutableListOf<SessionListing>()
        var cursor: String? = null
        repeat(MAX_PAGES) {
            val page = api.sessionsPage(directory, DailySession.title(today), PAGE_SIZE, cursor)
            all += page.sessions
            if (page.sessions.size < PAGE_SIZE) return all
            cursor = page.next ?: return all
        }
        return all
    }

    /**
     * Settings › Start a new session (app-plan.md, Settings and finish (M6)): creates the day's next numbered session
     * with [model] and puts the message rules on it. Today's session is never touched. The new session becomes the
     * last one opened; the last date stays, so there's no new-day notice.
     */
    suspend fun startNew(
        today: LocalDate,
        directory: String,
        model: ModelChoice?,
    ): NewSessionResult {
        val session =
            try {
                api.create(DailySession.nextTitle(today, directory, list(today, directory)), directory, model)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return NewSessionResult.CreateFailed
            }
        // Once it exists it's the one the finder opens, so a failure from here on is a setup failure like any other.
        noticeDueFor = null
        remember(session.id)
        return try {
            api.putMessageRules(session.id)
            NewSessionResult.Opened(session)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            NewSessionResult.RulesFailed
        }
    }

    companion object {
        const val PAGE_SIZE = 20

        /** Pages followed at most while looking for today's session. */
        const val MAX_PAGES = 25
    }
}
