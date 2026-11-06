import Foundation

/// The text Hex Code sends to the agent, versioned in one place (see "The status summary" and
/// "The message rules" in the app plan). Not UI copy, so English and never translated.
/// Bump a version whenever its text changes, on both platforms.
enum SessionPrompts {
    /// The status summary prompt, sent with `POST /api/session/{id}/generate`.
    enum Summary {
        /// 2: a TL;DR of the substance (the answer, finding or outcome, what's in progress, or
        /// what the user needs to do), Now up to 300 characters, Link lines, backticks for code.
        static let version = 2

        static let text = """
        Summarise this session for someone glancing at their phone. Reply in exactly this shape and nothing else:
        ## <the topic, at most 5 words>
        Now: <the TL;DR, in plain words, at most 300 characters. Lead with the substance, not with what you did. If you have just answered, found or decided something, give that itself: the key facts, names, numbers or outcome ("Three apps share one API client: …", never "Explained the repo"). If you are still working, say what you are on and anything already found. If you need the user, say exactly what you need from them.>
        Next: <what happens next, or what the user could do, one sentence, at most 60 characters>
        - [x] <a finished step>
        - [ ] <a step not done yet>
        Link: <a short label> | <a URL the user may want to open>
        List at most 8 steps, in order. Leave out the step lines if there is no plan.
        Add at most 2 Link lines, only for URLs from this session, and none if there are none. Never put a URL in Now or Next.
        Put file names, commands and code in backticks. Use no other formatting.
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
