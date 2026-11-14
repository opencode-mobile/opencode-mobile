import Foundation

/// The text Hex Code sends to the agent, versioned in one place (see "The status summary" and
/// "The message rules" in the app plan, and "Ask on the side"). Not UI copy, so English and never
/// translated.
/// Bump a version whenever its text changes, on both platforms.
enum SessionPrompts {
    /// The status summary prompt, sent with `POST /api/session/{id}/generate`.
    enum Summary {
        /// 2: a TL;DR of the substance (the answer, finding or outcome, what's in progress, or
        /// what the user needs to do), Now up to 300 characters, Link lines, backticks for code.
        /// 3: no step lines; the to-do row is the agent's own `todowrite` list.
        /// 4: the latest request, not the whole session; Now may span lines or a short list; Next is
        /// what the user might say next, or "none" (status-summary-proposal.md).
        /// 5: always start the second line with "Now:".
        static let version = 5

        static let text = """
        Say where this session stands, for the user glancing at their phone instead of reading the chat. This message is not part of the session: leave it out when you decide what the latest request is. Don't call any tools. Reply in exactly this shape and nothing else, and always start the second line with "Now:":
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
        """
    }

    /// The Ask on the side prompt, sent with `POST /api/session/{id}/generate` (app plan, "Ask on
    /// the side"). The question goes after "Question: ". Plain text and no tools: `generate` runs no
    /// tool loop, so a tool call comes back empty.
    enum Ask {
        /// 1: the first prompt (M5), tested live on 8 questions; none came back empty.
        static let version = 1

        static let wrapper = """
        The user is asking a quick side question about the conversation so far. This message is not part of the session and isn't a request to act. Don't call any tools and don't take any actions: answer only from what you already know.
        Reply in plain text in at most 2 short sentences (at most 280 characters). No Markdown, except backticks around a file name or command.
        If the conversation doesn't say, say you don't know. If the question asks you to do something, say it needs to be sent as a message instead.
        """

        static func prompt(question: String) -> String {
            wrapper + "\n\nQuestion: " + question
        }
    }

    /// The notes Deny and Dismiss send when nothing was said (app plan, "Needs you and control"):
    /// with a note the agent reads it and carries on; without one the run would end, and Stop is
    /// the way to halt.
    enum Needs {
        /// 1: the first notes (M4).
        static let version = 1

        /// Deny's note, sent as the reject's `message` unless the user said what to do instead.
        static let denyNote = "The user denied this request. Don't try it again; carry on another way, or ask if you're stuck."

        /// Dismiss's note, sent as `DELETE …/form/{id}?message=`.
        static let dismissNote = "The user dismissed these questions without answering. Carry on with your best judgement and say what you chose."
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

    /// Artifacts (app plan, "Artifacts (M8)"): the rule today's session gets while they're on, and
    /// what the separate "Hex Code setup" session is sent to turn them on or off.
    enum Artifacts {
        /// The rule entry, `artifact-rules`, put on today's session only while artifacts are on and
        /// deleted as soon as they're off or not available (artifacts-proposal.md §2.4).
        enum Rules {
            /// 1: the rule tested live in artifacts-proposal.md §1.6 (session D).
            static let version = 1
            static let entryID = "artifact-rules"

            static let text = """
            The user reads this session on a phone, which shows the newest artifact of this run as a tile they can open. When they want to see something you made (a page, a mock-up, a chart, a report), publish it as an artifact: inside `execute`, call `tools.opencode.artifact_create`; for a new version of the same thing, call `tools.opencode.artifact_update` with its id. Never write an artifact's address or URL: the phone opens it. These are OpenCode's own artifact tools, not another publishing tool or skill.
            """
        }

        /// The prompts sent to the setup session (artifacts-enable-spike.md §2.1), with `file` the
        /// absolute path of the config file that wins `experimental`, never `~`.
        enum Setup {
            /// 1: the spike's final prompts.
            static let version = 1
            /// The setup session's title: never the daily "YYYY-MM-DD (n)" pattern.
            static let title = "Hex Code setup"

            static func enable(file: String) -> String {
                "Turn on OpenCode's experimental artifacts for me. In `\(file)`, add `\"artifacts\": true` inside the `\"experimental\"` object. If there's no `\"experimental\"` key, add `\"experimental\": { \"artifacts\": true }`; if the file doesn't exist, create it with just that. Change nothing else: keep every other setting, comment and the formatting as they are. Don't run commands, look anything up or touch any other file. Reply with exactly DONE, or FAILED: <reason>."
            }

            static func disable(file: String) -> String {
                "Turn off OpenCode's experimental artifacts for me. In `\(file)`, remove `\"artifacts\": true` from the `\"experimental\"` object. Change nothing else: keep every other setting, comment and the formatting as they are. Don't run commands, look anything up or touch any other file. Reply with exactly DONE, or FAILED: <reason>."
            }

            /// The setup session's lockdown (spike §2.2), last in the session's rules so they win: no
            /// shell, code mode, web, skills or subagents; outside the project only the global config
            /// folder; every edit asks.
            static func lockdown(globalFolder: String) -> [(action: String, resource: String, effect: String)] {
                [
                    ("shell", "*", "deny"),
                    ("execute", "*", "deny"),
                    ("webfetch", "*", "deny"),
                    ("websearch", "*", "deny"),
                    ("skill", "*", "deny"),
                    ("subagent", "*", "deny"),
                    ("external_directory", "*", "deny"),
                    ("external_directory", globalFolder + "/*", "allow"),
                    ("edit", "*", "ask"),
                ]
            }
        }
    }
}
