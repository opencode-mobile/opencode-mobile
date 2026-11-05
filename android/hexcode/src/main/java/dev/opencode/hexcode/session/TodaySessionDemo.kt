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

    /** Idle, a card with no steps and no Next line. */
    Plain("plain"),

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

    /** The event stream is down. */
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
        val session = TodaySession(scope, DailySessionSetup(api, memory), live, transport, { DIRECTORY }, { null }, { today }, clock)
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
            Card -> busy.copy(inbox = listOf(InboxEntry("msg_demo", "queue")))
            Needs ->
                busy.copy(
                    pending = listOf(pending("frm_demo", PendingItem.Kind.Question, "2 questions · login length and remember-me")),
                )
            Approval ->
                busy.copy(
                    pending = listOf(pending("per_demo", PendingItem.Kind.Approval, "Run bun run db:reset on the dev database")),
                )
            Reconnecting -> busy
            Updating, UpdateFailed -> busy.copy(busySince = now - 95_000)
            Empty, NewDay -> idle.copy(hasRun = false)
            else -> idle
        }
    }

    private fun pending(
        id: String,
        kind: PendingItem.Kind,
        text: String,
    ) = PendingItem(id, SESSION, kind, text)

    private fun reply(): String =
        when (this) {
            Fallback -> FALLBACK_REPLY
            Plain -> "## Login screen rewrite\nNow: Reading the auth tests to see what the old helper covers."
            Needs ->
                "## Login screen rewrite\nNow: Token refresh is done. Paused before changing login length.\n" +
                    "Next: Your answers, then the auth tests.\n- [x] Remove old session helper\n- [x] New login form\n" +
                    "- [x] Token refresh\n- [ ] Auth tests"
            Approval ->
                "## Login screen rewrite\nNow: The auth tests need a clean database. Waiting for your approval.\n" +
                    "Next: Then run the auth tests.\n- [x] Remove old session helper\n- [x] New login form\n" +
                    "- [x] Token refresh\n- [ ] Auth tests"
            else -> REPLY
        }

    companion object {
        const val REPLY =
            "## Login screen rewrite\nNow: Wiring the new token refresh into the API client.\n" +
                "Next: Run the auth tests, then fix whatever breaks.\n- [x] Remove old session helper\n- [x] New login form\n" +
                "- [ ] Token refresh\n- [ ] Auth tests"

        const val FALLBACK_REPLY =
            "Okay, I finished the token refresh and now I am going through the auth tests one at a time. Two of them " +
                "still lean on the old session helper, so I am rewriting those to use the new client first, which should not " +
                "take long, and after that I will run the whole suite again and see what else is left over."

        private const val DIRECTORY = "/Users/example/Projects/hexcode"
        private const val SESSION = "ses_demo"
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
}

/** Says it connected and then stays open; or, when not [up], fails every attempt. */
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
