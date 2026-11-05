package dev.opencode.hexcode.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Fake sessions for looking at home without a server, the same presets as iOS's `TodaySessionDemo`. Settings ›
 * Developer › Sample session picks one. Nothing here talks to OpenCode.
 */
enum class TodaySessionDemo(
    /** For the developer settings row: English, not translated. */
    val label: String,
) {
    /** Working, with a four-step plan and a message waiting. */
    Card("Card"),

    /** Idle, a card with no steps and no Next line. */
    Plain("Plain"),

    /** The reply didn't match the card shape. */
    Fallback("Fallback"),

    /** Couldn't open today's session. */
    Failed("Setup failed"),

    /** A pending question. */
    Needs("Needs input"),

    /** A pending approval. */
    Approval("Approval"),

    /** The first session after first run: the empty card. */
    Empty("Empty"),

    /** A new day: the empty card and the notice. */
    NewDay("New day"),

    /** The event stream is down. */
    Reconnecting("Reconnecting"),

    /** A summary request on its way. */
    Updating("Updating"),

    /** The last summary request failed. */
    UpdateFailed("Update failed"),
    ;

    /** A session in this state, opening at once. */
    fun start(
        scope: CoroutineScope,
        clock: () -> Long = System::currentTimeMillis,
    ): TodaySession {
        val today = LocalDate.now()
        val now = clock()
        val api =
            DemoApi(
                if (this == Empty ||
                    this == NewDay
                ) {
                    emptyList()
                } else {
                    listOf(SessionListing("ses_demo", DailySession.title(today), DIRECTORY, now))
                },
                this == Failed,
            )
        val memory = DemoMemory(if (this == Empty) null else today.minusDays(1))
        val source = DemoSource(reply())
        val session = TodaySession(scope, DailySessionSetup(api), memory, SummaryRefresher(scope, source, clock), { DIRECTORY }, { today })
        session.updateAgent {
            when (this) {
                Card -> it.copy(busySince = now - 252_000, waiting = 1)
                Needs -> it.copy(pendingInput = PendingInput(PendingInput.Kind.Question, "2 questions · login length and remember-me"))
                Approval -> it.copy(pendingInput = PendingInput(PendingInput.Kind.Approval, "Run bun run db:reset on the dev database"))
                Reconnecting -> it.copy(streamUp = false, busySince = now - 252_000)
                Updating, UpdateFailed -> it.copy(busySince = now - 95_000)
                else -> it
            }
        }
        session.open()
        // The card first, then a second request that hangs or fails.
        if (this == Updating || this == UpdateFailed) {
            scope.launch {
                session.state.first { it.summary.summary != null && !it.summary.loading }
                if (this@TodaySessionDemo == Updating) source.hangs = true else source.reply = null
                session.refresh()
            }
        }
        return session
    }

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
    }
}

private class DemoApi(
    private val listed: List<SessionListing>,
    private val fails: Boolean,
) : DailySessionApi {
    override suspend fun sessions(directory: String): List<SessionListing> {
        delay(300)
        if (fails) error("Demo: the computer can't be reached.")
        return listed
    }

    override suspend fun create(
        title: String,
        directory: String,
    ) = "ses_new"

    override suspend fun putInstruction(
        sessionId: String,
        entryId: String,
        text: String,
    ) = Unit
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
private class DemoSource(
    var reply: String?,
) : SummarySource {
    var hangs = false

    override suspend fun summarise(prompt: String): String {
        if (hangs) awaitCancellation()
        delay(600)
        return reply ?: error("Demo: the summary failed.")
    }
}
