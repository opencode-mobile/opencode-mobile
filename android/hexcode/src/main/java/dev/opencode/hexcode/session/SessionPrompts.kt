package dev.opencode.hexcode.session

/**
 * The text Hex Code sends to the agent, in one place (app-plan.md, Today's session on screen and The message rules).
 * Not UI copy, so not in the string resources and not translated. Change a text here, raise its version, and make
 * the same change on iOS.
 */
object SessionPrompts {
    /** Raised whenever [SUMMARY] changes. */
    const val SUMMARY_VERSION = 1

    /** The status summary's prompt, sent to `POST /api/session/{id}/generate`; [StatusSummary.parse] reads the reply. */
    val SUMMARY =
        """
        Summarise this session for a small phone screen. Reply in exactly this shape and nothing else:
        ## <the task, at most 5 words>
        Now: <what you are doing now, one sentence, at most 90 characters>
        Next: <what comes after, one sentence, at most 60 characters>
        - [x] <a finished step>
        - [ ] <a step not done yet>
        List at most 8 steps, in order. Leave out the step lines if there is no plan.
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
