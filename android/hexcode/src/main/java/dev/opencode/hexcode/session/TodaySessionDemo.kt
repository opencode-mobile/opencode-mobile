package dev.opencode.hexcode.session

import dev.opencode.hexcode.firstrun.ModelChoice
import dev.opencode.mobile.data.remote.SseAttemptOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Fake sessions for looking at home without a server, the same presets as iOS's `TodaySessionDemo`. Settings ›
 * Developer › Sample session picks one; they run the real [TodaySession] with fakes behind it. Nothing here talks to
 * OpenCode.
 */
enum class TodaySessionDemo(
    /** For the developer settings row: the preset's name on both platforms. */
    val label: String,
) {
    /** Working, with a four-step plan and a message waiting. */
    Card("card"),

    /** Idle, a card with no to-do list and no Next line. */
    Plain("plain"),

    /** Idle, a Now of 150 characters (`nowLarge`). */
    Long("long"),

    /** Idle, a Now of about 300 characters (`nowSmall`, or smaller if the card doesn't fit). */
    Longest("longest"),

    /** Idle, with code, bold, a `Link:` line and a localhost link in Now: formatting and link tiles. */
    Formatted("formatted"),

    /** Idle, a list answer: a lead and four bullets (status-summary-proposal.md §5 C). */
    Bullets("bullets"),

    /** Idle, a long Now full of code spans, which are wider than letters: the fit has to measure, not count. */
    Branches("branches"),

    /** The reply didn't match the card shape. */
    Fallback("fallback"),

    /** Couldn't open today's session. */
    Failed("failed"),

    /** A pending question. */
    Needs("needs"),

    /** A pending approval. */
    Approval("approval"),

    /** The first session after first run: the empty card. */
    Empty("empty"),

    /** A new day: the empty card and the notice. */
    NewDay("newday"),

    /** The event stream is down: the card saved on the last launch. */
    Reconnecting("reconnecting"),

    /** A summary request on its way. */
    Updating("updating"),

    /** The last summary request failed. */
    UpdateFailed("updatefailed"),
    ;

    /** A session in this state; home opens it. */
    fun start(
        scope: CoroutineScope,
        clock: () -> Long = System::currentTimeMillis,
    ): TodaySession {
        val today = LocalDate.now()
        val now = clock()
        val fresh = this == Empty || this == NewDay
        val existing = SessionListing(SESSION, DailySession.title(today), DIRECTORY, now, busySince = now - 600_000)
        val api = DemoApi(if (fresh) emptyList() else listOf(existing), fails = this == Failed)
        val memory = DemoMemory(if (this == Empty) null else today.minusDays(1))
        val live = DemoLive(reply(), snapshot(now))
        val transport = if (this == Reconnecting) DemoTransport(up = false) else DemoTransport(up = true)
        // While the session is busy, open asks for nothing (the run's end does): the card saved 2 minutes ago shows. The
        // Reconnecting stream never comes up, so its card is 4 minutes old.
        val saved =
            when (this) {
                Reconnecting -> SavedSummary(SESSION, StatusSummary.parse(REPLY), now - 240_000, today.toString(), DIRECTORY)
                Card, Needs, Approval, Updating, UpdateFailed ->
                    SavedSummary(SESSION, StatusSummary.parse(reply()), now - 120_000, today.toString(), DIRECTORY)
                else -> null
            }
        val session =
            TodaySession(
                scope,
                DailySessionSetup(api, memory),
                live,
                transport,
                { DIRECTORY },
                { null },
                { today },
                clock,
                summaryStore = DemoSummaryStore(saved),
            )
        // The card first, then a second request that hangs or fails.
        if (this == Updating || this == UpdateFailed) {
            scope.launch {
                session.state.first { it.summary.summary != null && !it.summary.loading }
                if (this@TodaySessionDemo == Updating) live.hangs = true else live.reply = null
                session.refresh()
            }
        }
        return session
    }

    private fun snapshot(now: Long): LiveSnapshot {
        val busy = LiveSnapshot(now - 252_000, hasRun = true, children = emptyList(), pending = emptyList(), inbox = emptyList())
        val idle = busy.copy(busySince = null)
        return when (this) {
            // Working, with a subagent running, a message waiting and the agent's list half done.
            Card ->
                busy.copy(
                    children = listOf(CHILD),
                    runningChildren = listOf(CHILD),
                    inbox = listOf(InboxEntry("msg_demo", "queue", "Also keep the old error messages")),
                    todos = todos(DONE, DONE, NOW, TODO),
                )
            Needs ->
                busy.copy(
                    pending = listOf(pending("frm_demo", PendingItem.Kind.Question, "2 questions · login length and remember-me")),
                    todos = todos(DONE, DONE, DONE, TODO),
                )
            Approval ->
                busy.copy(
                    pending = listOf(pending("per_demo", PendingItem.Kind.Approval, "Run bun run db:reset on the dev database")),
                    todos = todos(DONE, DONE, DONE, TODO),
                )
            Reconnecting -> busy.copy(todos = todos(DONE, NOW, TODO, TODO))
            Updating, UpdateFailed -> busy.copy(busySince = now - 95_000, todos = todos(DONE, DONE, NOW, TODO))
            Empty, NewDay -> idle.copy(hasRun = false)
            // Every item done.
            Long -> idle.copy(todos = todos(DONE, DONE, DONE, DONE))
            Formatted ->
                idle.copy(
                    todos =
                        listOf(
                            TodoItem("Find the 401", DONE),
                            TodoItem("Retry once in `client.ts`", NOW),
                            TodoItem("Run `bun test auth`", TODO),
                        ),
                )
            // Plain, Fallback and Longest keep no list: no row.
            else -> idle
        }
    }

    /** The login rewrite's four items, with these statuses. */
    private fun todos(vararg statuses: TodoItem.Status): List<TodoItem> =
        listOf("Remove old session helper", "New login form", "Token refresh", "Auth tests")
            .zip(statuses.toList()) { content, status -> TodoItem(content, status) }

    private fun pending(
        id: String,
        kind: PendingItem.Kind,
        text: String,
    ) = PendingItem(id, SESSION, kind, text)

    private fun reply(): String =
        when (this) {
            Fallback -> FALLBACK_REPLY
            Plain -> "## Login screen rewrite\nNow: Reading the auth tests to see what the old helper covers."
            Long -> "## API client\nNow: $LONG_NOW\nNext: Fix token refresh in the shared client."
            Longest -> "## Read-only mode first\nNow: $LONGEST_NOW\nNext: Add a read-only flag to the router."
            Formatted -> FORMATTED_REPLY
            Bullets -> BULLETS_REPLY
            Branches -> "## Mobile work in the repo\nNow: $BRANCHES_NOW\nNext: Check out `origin/ios-pwa-shell`"
            Needs ->
                "## Login screen rewrite\nNow: Token refresh is done. Paused before changing login length.\n" +
                    "Next: Your answers, then the auth tests."
            Approval ->
                "## Login screen rewrite\nNow: The auth tests need a clean database. Waiting for your approval.\n" +
                    "Next: Then run the auth tests."
            else -> REPLY
        }

    companion object {
        const val REPLY =
            "## Login screen rewrite\nNow: Wiring the new token refresh into the API client.\n" +
                "Next: Run the auth tests, then fix whatever breaks."

        /** 150 characters. */
        const val LONG_NOW =
            "Three apps share one API client in packages/api: the web app, the phone app and the admin tool. Token " +
                "refresh lives there, so one fix covers them all."

        /** 298 characters. */
        const val LONGEST_NOW =
            "Mapping which screens need a signed-in user and which can open read-only. Settings, the project list and " +
                "the session view can all be read without logging in. Billing and team admin both write data, so they " +
                "stay behind login. This list becomes the read-only flag in the router next. Edits hide later."

        const val BULLETS_REPLY =
            "## Safer remove_item ideas\nNow: Four one-line ways to make `remove_item` safer, without changing code.\n" +
                "- Validate non-negative `qty`\n- Treat missing item as zero\n- Clamp result to zero\n- Return a copy of stock\n" +
                "Next: Apply one of these"

        /** From a real phone: code spans make it wider than its 300 characters suggest. */
        const val BRANCHES_NOW =
            "Found real mobile work across branches: `origin/ios-pwa-shell`, `origin/ios-titlebar-safe-area`, " +
                "`origin/mobile-bottom-nav`, `origin/pwa-push`, plus a since-deleted `packages/mobile-voice` (iOS " +
                "`Info.plist`) and Capacitor-style Android/iOS icon sets under `packages/desktop/icons`. No mobile app " +
                "source in the main tree."

        const val FORMATTED_REPLY =
            "## Login `401` fix\nNow: The **token refresh** in `client.ts` now retries once; the login page at " +
                "http://localhost:5173/login no longer shows a 401.\nNext: Run `bun test auth`, then open a PR.\n" +
                "Link: Issue 412 | https://github.com/example/app/issues/412"

        const val FALLBACK_REPLY =
            "Okay, I finished the token refresh and now I am going through the auth tests one at a time. Two of them " +
                "still lean on the old session helper, so I am rewriting those to use the new client first, which should not " +
                "take long, and after that I will run the whole suite again and see what else is left over."

        private const val DIRECTORY = "/Users/example/Projects/hexcode"
        private const val SESSION = "ses_demo"
        private const val CHILD = "ses_demo_child"
        private val DONE = TodoItem.Status.Completed
        private val NOW = TodoItem.Status.InProgress
        private val TODO = TodoItem.Status.Pending
    }
}

private class DemoApi(
    private val listed: List<SessionListing>,
    private val fails: Boolean,
) : DailySessionApi {
    override suspend fun sessionsPage(
        directory: String,
        search: String,
        limit: Int,
        cursor: String?,
    ): SessionListingPage {
        delay(300)
        if (fails) error("Demo: the computer can't be reached.")
        return SessionListingPage(listed, next = null)
    }

    override suspend fun create(
        title: String,
        directory: String,
        model: ModelChoice?,
    ) = SessionListing("ses_new", title, directory, updatedAt = System.currentTimeMillis())

    override suspend fun putMessageRules(sessionId: String) = Unit
}

private class DemoMemory(
    private var last: LocalDate?,
) : DailySessionMemory {
    override suspend fun lastOpenedOn() = last

    override suspend fun setLastOpenedOn(date: LocalDate) {
        last = date
    }
}

/** Answers after 0.6 s; a null [reply] fails, and [hangs] never answers. */
private class DemoLive(
    var reply: String?,
    private val snapshot: LiveSnapshot,
) : LiveSessionApi {
    var hangs = false

    override suspend fun snapshot(rootId: String) = snapshot

    override suspend fun summarise(
        sessionId: String,
        prompt: String,
    ): String {
        if (hangs) awaitCancellation()
        delay(600)
        return reply ?: error("Demo: the summary failed.")
    }

    override suspend fun switchModel(
        sessionId: String,
        model: ModelChoice,
    ) = Unit

    override suspend fun send(
        sessionId: String,
        text: String,
        delivery: String,
        images: List<PromptImage>,
    ) = "msg_sample"

    override suspend fun cancelQueued(
        sessionId: String,
        itemId: String,
    ) = Unit
}

/** Says it connected and then stays open; or, when not [up], fails every attempt. */
private class DemoSummaryStore(
    private var saved: SavedSummary?,
) : SummaryStore {
    override suspend fun loadSummary(): SavedSummary? = saved

    override suspend fun saveSummary(saved: SavedSummary) {
        this.saved = saved
    }
}

private class DemoTransport(
    private val up: Boolean,
) : EventTransport {
    override suspend fun connect(
        onActivity: () -> Unit,
        onLine: suspend (String) -> Unit,
    ): SseAttemptOutcome {
        if (!up) {
            delay(200)
            return SseAttemptOutcome.Transport(null)
        }
        onActivity()
        onLine("""{"id":"evt_demo","type":"server.connected","data":{}}""")
        while (true) {
            delay(15_000)
            onActivity()
        }
    }
}
