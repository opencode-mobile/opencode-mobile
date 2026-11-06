import Foundation

/// The text Hex Code sends to the agent, versioned in one place (see "The status summary" and
/// "The message rules" in the app plan). Not UI copy, so English and never translated.
/// Bump a version whenever its text changes, on both platforms.
enum SessionPrompts {
    /// The status summary prompt, sent with `POST /api/session/{id}/generate`.
    enum Summary {
        static let version = 1

        static let text = """
        Summarise this session for a small phone screen. Reply in exactly this shape and nothing else:
        ## <the task, at most 5 words>
        Now: <what you are doing now, one sentence, at most 90 characters>
        Next: <what comes after, one sentence, at most 60 characters>
        - [x] <a finished step>
        - [ ] <a step not done yet>
        List at most 8 steps, in order. Leave out the step lines if there is no plan.
        """
    }

    /// The fixed instruction entry added once per session with
    /// `PUT /api/experimental/session/{id}/instructions/entries/message-rules`.
    /// The wording may change after spike S1.
    enum MessageRules {
        /// 2: after spike S1, a correction keeps the work done and changes only what it affects.
        static let version = 2
        static let entryID = "message-rules"

        static let text = """
        Messages come from a voice app and may arrive while you work.
        - No label: more detail for the current task. Fold it into the plan.
        - "[Correction]": the user changed a detail mid-task. Keep the work already done, change only what the correction affects (finished steps too), say so in one line, then carry on with the rest.
        - The goal set with /goal is the plan for today. Keep it unless told otherwise.
        """
    }
}
