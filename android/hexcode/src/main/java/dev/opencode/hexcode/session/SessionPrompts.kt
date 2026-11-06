package dev.opencode.hexcode.session

/**
 * The text Hex Code sends to the agent, in one place (app-plan.md, Today's session on screen and The message rules).
 * Not UI copy, so not in the string resources and not translated. Change a text here, raise its version, and make
 * the same change on iOS.
 */
object SessionPrompts {
    /**
     * Raised whenever [SUMMARY] changes. 2: Now is a TL;DR of the substance, up to 300 characters, with Link lines
     * and backticks. 3: no steps (the to-do row comes from the agent's own list).
     */
    const val SUMMARY_VERSION = 3

    /**
     * The status summary's prompt, exactly as app-plan.md (The status summary), sent to
     * `POST /api/session/{id}/generate`; [StatusSummary.parse] reads the reply.
     */
    val SUMMARY =
        """
        Summarise this session for someone glancing at their phone. Reply in exactly this shape and nothing else:
        ## <the topic, at most 5 words>
        Now: <the TL;DR, in plain words, at most 300 characters. Lead with the substance, not with what you did. If you have just answered, found or decided something, give that itself: the key facts, names, numbers or outcome ("Three apps share one API client: …", never "Explained the repo"). If you are still working, say what you are on and anything already found. If you need the user, say exactly what you need from them.>
        Next: <what happens next, or what the user could do, one sentence, at most 60 characters>
        Link: <a short label> | <a URL the user may want to open>
        Add at most 2 Link lines, only for URLs from this session, and none if there are none. Never put a URL in Now or Next.
        Put file names, commands and code in backticks. Use no other formatting.
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
