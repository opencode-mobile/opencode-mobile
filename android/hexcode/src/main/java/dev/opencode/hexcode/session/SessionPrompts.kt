package dev.opencode.hexcode.session

/**
 * The text Hex Code sends to the agent, in one place (app-plan.md, Today's session on screen and The message rules).
 * Not UI copy, so not in the string resources and not translated. Change a text here, raise its version, and make
 * the same change on iOS.
 */
object SessionPrompts {
    /**
     * Raised whenever [SUMMARY] changes. 2: Now is a TL;DR of the substance, up to 300 characters, with Link lines
     * and backticks. 3: no steps (the to-do row comes from the agent's own list). 4: a TL;DR of the latest request,
     * with lines or a short list in Now, and Next in the user's words (status-summary-proposal.md).
     */
    const val SUMMARY_VERSION = 4

    /**
     * The status summary's prompt, exactly as app-plan.md (The status summary), sent to
     * `POST /api/session/{id}/generate`; [StatusSummary.parse] reads the reply.
     */
    val SUMMARY =
        """
        Say where this session stands, for the user glancing at their phone instead of reading the chat. This message is not part of the session: leave it out when you decide what the latest request is. Don't call any tools. Reply in exactly this shape and nothing else:
        ## <the topic of the latest request, at most 5 words>
        Now: <the substance in one sentence, at most 140 characters>
        <optional: one more sentence, or 2 to 4 lines starting "- ">
        Next: <what the user might say next, at most 60 characters, or "none">
        Link: <a short label> | <a URL>

        The latest request is the user's last message, together with the messages it continues or corrects. If the last message is only "ok", "thanks" or similar, take the one before it. Cover that request and what has happened since, and use the rest of the session only to make sense of it. Only if the latest request asks for a summary or recap of the session, cover the whole session.

        Now:
        - If the request is done, give the result itself: the key facts, names, numbers or outcome ("`remove_item` now rejects missing items; 4 tests pass", never "Updated the code"). If something failed or was stopped, say what and why.
        - If it is still in progress, say what is being done for it and anything found so far.
        - If you need the user, say exactly what you need.
        - Lead with the substance. Don't start with "I".
        - Put separate points on separate lines instead of one long sentence: the lead, then at most 2 more lines or the list.
        - Name things in plain words. Use backticks only for one or two short file names or commands that matter, never for lists of names (say "four branches" and list them as "- " lines if they matter).
        - Use "- " lines only when the content is a list (items, files, options, findings, steps done), each at most 50 characters. Then the first sentence says what the list is ("Four ways to make `remove_item` safer:"), not what you did, and doesn't repeat the items.
        - At most 300 characters in all. Shorter is better: this is read at a glance.

        Next is a suggested follow-up: what the user would most likely say to you next, in their words ("Commit it", "Try variant A"). If you asked them something, their most likely answer. Write "Next: none" only while the work is still in progress or when the request was trivial.

        Add at most 2 Link lines, for URLs from the latest request or since that the user may want to open (a preview, a pull request, a page you found). Leave Link lines out when there are none. Never put a URL in Now or Next.
        Use no other formatting.
        """.trimIndent()

    /** The instruction entry's id, in `PUT /api/experimental/session/{id}/instructions/entries/message-rules`. */
    const val MESSAGE_RULES_ID = "message-rules"

    /** Raised whenever [MESSAGE_RULES] changes. Version 2 follows spike S1. */
    const val MESSAGE_RULES_VERSION = 2

    /** Added once to each new session, so the agent knows how the voice app's messages are labelled. */
    val MESSAGE_RULES =
        """
        Messages come from a voice app and may arrive while you work.
        - No label: more detail for the current task. Fold it into the plan.
        - "[Correction]": the user changed a detail mid-task. Keep the work already done, change only what the correction affects (finished steps too), say so in one line, then carry on with the rest.
        - The goal set with /goal is the plan for today. Keep it unless told otherwise.
        """.trimIndent()
}
