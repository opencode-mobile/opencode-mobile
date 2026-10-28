// P1 scenarios: important behaviour beyond the core list. Format: see scenarios.mjs.

import { subagentTool, path, say, run, subagent, CURRENCY_QUESTION, QUESTION_EVENTS } from "./scenario-kit.mjs";

const PATCH = `*** Begin Patch
*** Add File: src/currency.ts
+export const MINOR_UNITS: Record<string, number> = { USD: 2, EUR: 2, JPY: 0 }
*** Update File: src/format.ts
@@
-const SYMBOLS: Record<string, string> = { USD: "$", EUR: "€", GBP: "£" }
+import { MINOR_UNITS } from "./currency.ts"
+const SYMBOLS: Record<string, string> = { USD: "$", EUR: "€", GBP: "£", JPY: "¥" }
*** Delete File: src/legacy-format.js
*** End Patch`;

const QUESTION_CALLS = [
  { steps: [say("Before I change the API, one question:"), { op: "tool", name: "question", input: { questions: [CURRENCY_QUESTION] } }] },
  { steps: [say("Got it. I'll go with that and fall back to `USD`.")] },
];

export const P1 = [
  {
    id: "permission-reject-in-app",
    priority: "P1",
    title: "Shell permission rejected; turn continues",
    category: "permission",
    prompt: "Clean the stale build output.",
    expect: "Tap Deny on the card. The shell row shows a neutral rejected state (not a red error), Home leaves Needs approval without showing Failed, and dist/ still exists. If the server ends the turn on reject, the second model call stays unserved; note it rather than failing.",
    modelCalls: [
      { steps: [run("rm -rf dist", "Remove stale build output")] },
      { steps: [say("Understood, I won't delete anything. You can remove `dist/` yourself with `rm -rf dist` when you're ready.")] },
    ],
    variants: { "reject-from-outside": [{ on: "permission.asked", delayMs: 1500, do: "replyPermission", reply: "reject" }] },
    checkVariant: "reject-from-outside",
    // Both servers end the turn on reject instead of calling the model again (seen on v1 1.19.1 and v2 dev).
    optionalCalls: 1,
  },
  {
    id: "question-answered-from-outside",
    priority: "P1",
    title: "Question answered by another client",
    category: "question",
    prompt: "Add currency support to formatMoney.",
    expect: "Don't touch the app: the harness answers 'Argument' 3s after the question appears. The card clears by itself, an answered summary shows, and the reply streams.",
    modelCalls: QUESTION_CALLS,
    automation: [{ on: QUESTION_EVENTS, delayMs: 3000, do: "replyQuestion", answers: [["Argument"]] }],
    // Dismissing can end the turn (v2 cancels the step), so the reply call may go unused.
    variants: { "dismiss-from-outside": { automation: [{ on: QUESTION_EVENTS, delayMs: 3000, do: "rejectQuestion" }], optionalCalls: 1 } },
  },
  {
    id: "subagent-background-completion",
    priority: "P1",
    title: "Background subagent wakes the parent when done",
    category: "subagent",
    prompt: "Benchmark formatMoney in the background.",
    expect: "The parent turn ends while the child keeps running (the task card shows running/background, the composer is usable). About 6s after the run starts the child finishes, the server wakes the parent, and a new assistant reply appears without a reload. The child never shows as a top-level Home row.",
    modelCalls: [
      {
        steps: [
          {
            op: "tool",
            name: subagentTool,
            child: "bench",
            input: subagent("Benchmark formatMoney", "Time 1e6 formatMoney calls and report ops/sec.", { background: true }),
          },
        ],
      },
      { steps: [say("Benchmark started in the background. I'll report when it's done.", 12)] },
      { child: "bench", steps: [{ op: "wait", gate: "child-finish" }, say("formatMoney: 2.1M ops/sec (Intl path 0.4M ops/sec).", 12)] },
      { steps: [say("The benchmark finished: the integer path is about 5× faster than Intl.", 12)] },
    ],
    automation: [{ on: "harness.run.started", delayMs: 6000, do: "release", gate: "child-finish" }],
  },
  {
    id: "subagent-open-after-spawn",
    priority: "P1",
    title: "Open a session after its subagent spawned",
    category: "subagent",
    prompt: "Trace how an invoice goes from parse to render.",
    expect: "Press Run with the app on Home, then open the new session within 15s. The task card must be tappable and open the running child even though the app missed the spawn event. The child finishes 15s after the run starts and the parent replies.",
    modelCalls: [
      { steps: [{ op: "tool", name: subagentTool, child: "trace", input: subagent("Map invoice flow", "Trace how an invoice goes from parse to render.") }] },
      { child: "trace", steps: [{ op: "wait", gate: "child-finish" }, say("parse → validate → totalCents → render.", 12)] },
      { steps: [say("Mapped the flow: parse → validate → totalCents → render.", 12)] },
    ],
    automation: [{ on: "harness.run.started", delayMs: 15000, do: "release", gate: "child-finish" }],
    variants: { quick: [{ on: "harness.run.started", delayMs: 3000, do: "release", gate: "child-finish" }] },
    checkVariant: "quick",
  },
  {
    id: "permission-in-child-session",
    priority: "P1",
    title: "Permission raised inside a subagent",
    category: "permission",
    prompt: "Run the tests with a subagent.",
    expect: "The child's `npm test` asks for permission. The parent Session Chat shows the approval card (attributed to the task) and the Home row shows Needs approval. Allow it from the parent: the child resumes, then the parent replies.",
    modelCalls: [
      { steps: [{ op: "tool", name: subagentTool, child: "tests", input: subagent("Run the tests", "Run npm test and summarise failures.") }] },
      { child: "tests", steps: [run("npm test", "Run tests")] },
      { child: "tests", steps: [say("All 3 tests pass.", 12)] },
      { steps: [say("Tests are green.", 12)] },
    ],
  },
  {
    id: "provider-free-limit-retry-after",
    priority: "P1",
    title: "Free usage limit with retry-after",
    category: "error",
    // v1 treats the 429 as a throttle and retries it live instead of failing the turn.
    protocols: ["v2"],
    prompt: "Summarise the README.",
    expect:
      "The provider refuses with a free usage limit (429, retry-after 75 s). The failure mark shows the server's free-limit message with a Try again in N s line counting down under it (gone at zero), and the sheet offers Pick a model.",
    modelCalls: [
      {
        steps: [
          {
            op: "httpError",
            status: 429,
            headers: { "retry-after": "75" },
            body: { type: "error", error: { type: "FreeUsageLimitError", message: "Rate limit exceeded. Please try again later." } },
          },
        ],
      },
    ],
  },
  {
    id: "retry-rate-limit-then-success",
    priority: "P1",
    title: "Rate limit and overload, then recovery",
    category: "error",
    prompt: "Summarise the README.",
    expect: "The provider answers 429 then 503. Home and Session Chat show Retrying · 1, Retrying · 2 with a countdown instead of Working, then the reply streams normally and no error line stays.",
    modelCalls: [
      { steps: [{ op: "httpError", status: 429, headers: { "retry-after": "2" }, body: { error: { message: "Rate limit reached for requests", type: "rate_limit_error" } } }] },
      { steps: [{ op: "httpError", status: 503, body: { error: { message: "The server is overloaded", type: "overloaded_error" } } }] },
      { steps: [say("The README says this is an invoice library with two modules.")] },
    ],
    variants: {
      "midstream-error-retry": {
        modelCalls: [
          {
            steps: [
              { op: "reason", value: "Checking the README structure first.", chunkChars: 8, delayMs: 40 },
              { op: "text", value: "The README", chunkChars: 5, delayMs: 40 },
              { op: "raw", chunk: 'data: {"error":{"message":"server_error","type":"server_error","code":"500"}}\n\n' },
              { op: "streamError" },
            ],
          },
          {
            steps: [
              { op: "reason", value: "Checking the README structure first.", chunkChars: 8, delayMs: 40 },
              say("The README explains installation and the two exported functions."),
            ],
          },
        ],
      },
    },
  },
  {
    id: "abort-while-tool-running",
    priority: "P1",
    title: "Stop while a shell command runs",
    category: "abort",
    prompt: "Run the build.",
    expect: "Allow the shell ask, wait for 'step 4/20' in the row, then tap Stop. The row keeps its partial output and shows interrupted (gray, not red), also after leaving and reopening the session. The model is not called again.",
    modelCalls: [{ steps: [say("Running the build."), run("sh scripts/slow-build.sh", "Run slow build")] }],
    variants: { "abort-from-outside": [{ on: "harness.run.started", delayMs: 5000, do: "abort" }] },
    checkVariant: "abort-from-outside",
  },
  {
    id: "background-foreground-turn-finishes",
    priority: "P1",
    title: "Turn finishes while the app is in the background",
    category: "reconnect",
    appOnly: true,
    prompt: "Explain totalCents in three parts.",
    expect: "2s into the slow stream, send the app to the background; foreground it after the turn ends (about 25s). The full text shows and the Home row is Done/Idle, not stuck Working, with no pull-to-refresh.",
    modelCalls: [
      {
        steps: [
          say("Part 1: `totalCents` walks each invoice line. Part 2: it multiplies quantity by the line's cents and reduces into one integer. Part 3: an empty invoice returns 0, and fractional quantities are rounded per line.", 6, 150),
        ],
      },
    ],
  },
  {
    id: "prompt-from-other-client-into-open-session",
    priority: "P1",
    title: "Another client prompts the open session",
    category: "multi-client",
    prompt: null,
    expect: "Press Run, then open the 'Zero handling' session (it gets one seeded turn). 5s after setup the harness sends 'Does formatMoney handle zero?' into it: the user bubble appears below the earlier turn, the reply streams, and the composer shows Stop while busy.",
    setup: [
      { do: "createSession", title: "Zero handling", as: "A" },
      { do: "seedTurns", session: "A", count: 1, text: "Explain formatMoney" },
    ],
    modelCalls: [{ steps: [say("Yes: `formatMoney(0, \"USD\")` returns `$0.00`.", 8, 60)] }],
    automation: [{ at: 5000, do: "prompt", session: "A", text: "Does formatMoney handle zero?" }],
  },
  {
    id: "queued-prompt-while-busy",
    priority: "P1",
    title: "Second prompt sent while busy",
    category: "session-lifecycle",
    prompt: "Refactor invoice.ts.",
    expect: "While the first reply is held, a second prompt arrives (the harness sends it 1.5s in; from the app, type 'Also rename lines to items' instead). It shows as queued/pending, then delivered; replies render in delivery order with no duplicate or stuck bubble, and Send stays enabled.",
    modelCalls: [
      { steps: [say("Refactoring invoice.ts now…", 6, 80), { op: "wait", gate: "first-turn" }, say(" done.", 6, 40)] },
      { steps: [say("Also renamed `lines` to `items` as you asked.", 8, 40)] },
    ],
    automation: [
      { on: "harness.run.started", delayMs: 1500, do: "prompt", text: "Also rename lines to items." },
      { on: "harness.run.started", delayMs: 3500, do: "release", gate: "first-turn" },
    ],
  },
  {
    id: "multi-step-turn-paragraphs",
    priority: "P1",
    title: "Text of each step stays in its own paragraph",
    category: "streaming",
    prompt: "How big is this codebase?",
    expect: "Allow both shell asks. Three text paragraphs interleave with two shell rows in exact order, never merged across rows, live and after reopening.",
    modelCalls: [
      { steps: [say("First I'll check the tests.", 8), run("ls test", "List tests")] },
      { steps: [say("Two test files. Now the sources.", 8), run("wc -l src/*.ts", "Count lines")] },
      { steps: [say("Small codebase: 2 TypeScript sources and about 40 lines in total.", 8)] },
    ],
  },
  {
    id: "tool-explore-parallel-burst",
    priority: "P1",
    title: "Eight parallel explore tools in one step",
    category: "tools",
    prompt: "Look around the codebase before changing anything.",
    expect: "One grouped Explored chip grows in place without flashing or reordering. The read of the missing src/currency.ts is visible as a failure inside the group.",
    modelCalls: [
      {
        steps: [
          { op: "tool", name: "glob", input: { pattern: "**/*.ts" } },
          { op: "tool", name: "grep", input: { pattern: "export function", include: "*.ts" } },
          { op: "tool", name: "read", input: path("src/invoice.ts") },
          { op: "tool", name: "read", input: path("src/format.ts") },
          { op: "tool", name: "read", input: path("src/currency.ts") },
          { op: "tool", name: "read", input: path("package.json") },
          { op: "tool", name: "grep", input: { pattern: "TODO|FIXME" } },
          { op: "tool", name: "read", input: path("README.md") },
        ],
      },
      { steps: [say("There's no `src/currency.ts` yet. I'll create it for the minor-units table.")] },
    ],
  },
  {
    id: "tool-patch-multifile",
    priority: "P1",
    title: "Patch tool touching three files",
    category: "tools",
    model: "gpt-5-scripted",
    prompt: "Add JPY with a minor-units table and drop the legacy formatter.",
    expect: "Uses the gpt-5-scripted harness model (the servers only offer the patch tool to gpt-* ids; pick it in the app's model picker when typing the prompt yourself). One Patch row lists 3 files: currency.ts added, format.ts +2/-1, legacy-format.js deleted, and all three land in Changes. A generic row instead of a Patch row is an app gap (v1 apply_patch vs v2 patch).",
    modelCalls: [
      { steps: [{ op: "tool", name: { v1: "apply_patch", v2: "patch" }, input: { patchText: PATCH } }] },
      { steps: [say("Added `src/currency.ts`, updated the symbols, and deleted the legacy formatter.")] },
    ],
  },
  {
    id: "tool-bash-streaming-output",
    priority: "P1",
    title: "Shell output grows while the command runs",
    category: "tools",
    prompt: "Render the monthly report.",
    expect: "Allow the shell ask. The row shows frames 1-40, then (3s later) frames 41-48 appear one by one, then (4s later) the command completes with exit 0 and the stderr diagnostics line. Output stays readable and scrollable.",
    modelCalls: [
      { steps: [run("sh scripts/render-report.sh", "Render the monthly report")] },
      { steps: [say("Rendered all 48 frames with no diagnostics errors.")] },
    ],
    automation: [
      { on: "permission.replied", delayMs: 3000, do: "touch", path: ".harness/continue" },
      { on: "permission.replied", delayMs: 7000, do: "touch", path: ".harness/finish" },
    ],
  },
  {
    id: "tool-failure-edit-mismatch",
    priority: "P1",
    title: "Edit fails for real; turn continues",
    category: "tools",
    prompt: "Rename sumLines to totalCents.",
    expect: "The Edit row is a red error with the server's 'oldString not found'-style message (not interrupted), followed by the reply text.",
    modelCalls: [
      {
        steps: [
          {
            op: "tool",
            name: "edit",
            input: {
              v1: { filePath: "${PROJECT}/src/invoice.ts", oldString: "function sumLines(", newString: "function totalCents(" },
              v2: { path: "src/invoice.ts", oldString: "function sumLines(", newString: "function totalCents(" },
            },
          },
        ],
      },
      { steps: [say("That function is already named `totalCents`, so there's nothing to rename.")] },
    ],
  },
  {
    id: "session-renamed-from-outside",
    priority: "P1",
    title: "Session renamed by another client",
    category: "session-lifecycle",
    prompt: null,
    expect: "Press Run and open the 'Fix rounding' session (seeded with one turn). 6s after setup the harness renames it to 'Fix rounding in formatMoney (1.2.0)': the top bar and the Home row update within about 1s, without reordering.",
    setup: [
      { do: "createSession", title: "Fix rounding", as: "A" },
      { do: "seedTurns", session: "A", count: 1, text: "Fix the rounding bug" },
    ],
    automation: [{ at: 6000, do: "rename", session: "A", title: "Fix rounding in formatMoney (1.2.0)" }],
  },
  {
    id: "compaction-manual",
    priority: "P1",
    title: "Manual compaction",
    category: "session-lifecycle",
    prompt: null,
    expect: "Press Run and open the 'Compact me' session (three seeded turns). 4s after setup the harness compacts it (or run /compact from the app first). One compaction row appears after the last reply, runs, then settles; the summary is not shown as reply text. v1 shows only the settled card after a refresh (issue 616).",
    setup: [
      { do: "createSession", title: "Compact me", as: "A" },
      { do: "seedTurns", session: "A", count: 3, text: "Tell me about the invoice code, part" },
    ],
    // Template headings, so v2 accepts the summary (v1 takes any text).
    summary: [say("## Objective\nShip formatMoney 1.2.0.\n\n## Work State\n### Completed\n- Fixed float rounding in formatMoney (integer cents).\n- Added JPY via MINOR_UNITS.\n- Tests: test/rounding.test.mjs passes.\n\n## Next Move\n- Update README.", 12, 40)],
    automation: [{ at: 4000, do: "compact", session: "A" }],
    variants: {
      "compaction-fails": {
        automation: [{ at: 4000, do: "compact", session: "A" }],
        summary: [{ op: "httpError", status: 500, body: { error: { message: "summarizer unavailable", type: "server_error" } } }],
      },
    },
  },
];
