// P2 scenarios: edge cases and polish. Format: see scenarios.mjs.

import { say, run, path } from "./scenario-kit.mjs";

// About 6 KB of migration guide, so write arguments stream for several seconds.
const MIGRATION_GUIDE = [
  "# Migrating to formatMoney 2",
  "",
  "formatMoney now takes integer cents and a currency code, and it never uses floating-point division.",
  "",
  ...Array.from({ length: 24 }, (_, i) =>
    [
      `## Step ${i + 1}: update call site group ${i + 1}`,
      "",
      `Find calls that pass dollars (for example \`formatMoney(price)\`) in module group ${i + 1} and convert them to cents with \`Math.round(price * 100)\` before calling.`,
      "Add a regression test for an amount ending in 5 cents, such as 1005, so rounding stays correct.",
      "",
    ].join("\n"),
  ),
].join("\n");

export const P2 = [
  {
    id: "question-multi-select-custom",
    priority: "P2",
    title: "Two questions: multi-select and a custom answer",
    category: "question",
    prompt: "Add locale support to formatMoney.",
    expect: "Both questions are reachable. On the first, select en-US and ja-JP (multi-select toggles). On the second, type the custom answer 'Half up, but log a warning'. The reply streams after Submit, and the answered summary lists both answers.",
    modelCalls: [
      {
        steps: [
          {
            op: "tool",
            name: "question",
            input: {
              questions: [
                {
                  header: "Locales",
                  question: "Which locales must formatMoney support?",
                  multiple: true,
                  options: [
                    { label: "en-US", description: "US English" },
                    { label: "de-DE", description: "German" },
                    { label: "ja-JP", description: "Japanese" },
                  ],
                },
                {
                  header: "Rounding",
                  question: "Which rounding mode for half cents?",
                  options: [
                    { label: "Half up", description: "0.5 rounds away from zero" },
                    { label: "Banker's", description: "Half to even" },
                  ],
                },
              ],
            },
          },
        ],
      },
      { steps: [say("Supporting the locales you picked, with the rounding you chose.", 12)] },
    ],
  },
  {
    id: "content-filter-and-length-finish",
    priority: "P2",
    title: "Length stop, content filter, empty reply",
    category: "error",
    prompt: "Rewrite format.ts in full.",
    expect: "Default (length): the partial reply stays with a cut-off note, live and after reopening. Variant content-filter: the partial text stays, an error line shows without a retry button, Home shows Failed. Variant empty-reply: the turn settles with no blank bubble and no loop (exactly one model call).",
    modelCalls: [{ steps: [say("Here is the full rewrite of format.ts:\n\n```ts\nexport function formatMoney(", 8), { op: "finish", reason: "length" }] }],
    variants: {
      "content-filter": { modelCalls: [{ steps: [say("Sure, here is", 6), { op: "finish", reason: "content_filter" }] }] },
      "empty-reply": { modelCalls: [{ steps: [{ op: "finish", reason: "stop" }] }] },
    },
  },
  {
    id: "sse-auth-error-no-hot-loop",
    priority: "P2",
    title: "Event stream rejects auth for 30s",
    category: "reconnect",
    appOnly: true,
    prompt: null,
    expect: "With the app on Home, press Run: the proxy drops the app's event streams and answers reconnects with 401 for 30s (REST still works). The app shows an auth/unavailable connection state and retries with backoff; the Faults panel's attempt counter should stay at a handful, not one per second. After 30s it reconnects.",
    automation: [{ at: 0, do: "rejectSseAuth", ms: 30000 }],
  },
  {
    id: "tool-input-streaming-pending",
    priority: "P2",
    title: "Write row while large arguments stream",
    category: "tools",
    prompt: "Write a migration guide to docs/MIGRATION.md.",
    expect: "A Write row appears as soon as the tool input starts (pending, with the path once known), not only after ~8s of argument streaming. Servers that stream tool input (v2 beta) show the path from the first delta; v1 and older v2 show it once the call settles. Variant pending-then-hang: arguments stop half way; tap Stop and the row ends interrupted. Variant shell-command: a long Shell command streams in slowly; on v2 beta the pending Shell row shows \"Writing command…\" and then the command growing until the call settles.",
    modelCalls: [
      {
        steps: [
          {
            op: "tool",
            name: "write",
            argChunkChars: 40,
            argDelayMs: 50,
            input: { v1: { filePath: "${PROJECT}/docs/MIGRATION.md", content: MIGRATION_GUIDE }, v2: { path: "docs/MIGRATION.md", content: MIGRATION_GUIDE } },
          },
        ],
      },
      { steps: [say("Wrote `docs/MIGRATION.md`.")] },
    ],
    variants: {
      "shell-command": {
        modelCalls: [
          {
            steps: [
              {
                ...run(
                  "git log --oneline --max-count=20 -- src/ && git status --short && npm test",
                  "Check history, status and tests",
                ),
                argChunkChars: 4,
                argDelayMs: 150,
              },
            ],
          },
          { steps: [say("Checked history, status and tests.")] },
        ],
      },
      "pending-then-hang": {
        modelCalls: [
          {
            steps: [
              {
                op: "pendingTool",
                name: "write",
                argChunkChars: 40,
                argDelayMs: 20,
                input: { v1: { filePath: "${PROJECT}/docs/MIGRATION.md", content: MIGRATION_GUIDE }, v2: { path: "docs/MIGRATION.md", content: MIGRATION_GUIDE } },
              },
            ],
          },
        ],
      },
    },
  },
  {
    id: "tool-webfetch-local",
    priority: "P2",
    title: "Fetch row for a local page",
    category: "tools",
    prompt: "How do I avoid US$ when formatting dollars?",
    expect: "A Fetch row with the URL subtitle and expandable markdown output from the harness page, then the reply.",
    modelCalls: [
      { steps: [{ op: "tool", name: "webfetch", input: { url: "${HARNESS_URL}/docs/intl.md", format: "markdown" } }] },
      { steps: [say("Per the doc, `currencyDisplay: \"narrowSymbol\"` avoids `US$`.")] },
    ],
  },
  {
    id: "session-deleted-from-outside",
    priority: "P2",
    title: "Open session deleted by another client",
    category: "session-lifecycle",
    prompt: null,
    expect: "Press Run, then open 'Delete me' (the newer of two seeded sessions). 6s after setup the harness deletes it: its Home row disappears, Session Chat shows a not-found/removed state or pops back to Home, and nothing crashes or leaves a zombie row. 'Keep me' stays.",
    setup: [
      { do: "createSession", title: "Keep me", as: "A" },
      { do: "seedTurns", session: "A", count: 1, text: "First session" },
      { do: "createSession", title: "Delete me", as: "B" },
      { do: "seedTurns", session: "B", count: 1, text: "Second session" },
    ],
    automation: [{ at: 6000, do: "remove", session: "B" }],
  },
  {
    id: "compaction-auto-overflow",
    priority: "P2",
    title: "Context overflow triggers auto compaction",
    category: "session-lifecycle",
    prompt: "Analyse the whole invoice module.",
    expect: "Step 1 reads the README while reporting about 190k input tokens against the 200k context, so before step 2 the server compacts on its own: a compaction row appears without user action, the context/token indicator resets (issue 613), then the reply streams.",
    // Both servers measure context from the last reported usage and compact before the next step.
    modelCalls: [
      { steps: [say("Reading the module first…", 10, 20), { op: "tool", name: "read", input: path("README.md") }, { op: "usage", input: 190000, output: 400 }] },
      { steps: [say("Here is the analysis: the invoice module is small and the rounding fix is the main risk.", 10, 20)] },
    ],
    summary: [say("## Objective\nAnalyse the invoice module.\n\n## Work State\n### Completed\n- Long analysis of the invoice code.\n\n## Next Move\n- Answer follow-ups.", 12)],
  },
  {
    id: "revert-from-outside",
    priority: "P2",
    title: "Another client reverts the open session",
    category: "session-lifecycle",
    prompt: null,
    expect: "Press Run and open 'Revert me' (three seeded turns). 5s after setup the harness reverts to turn 2 (v2 stages it; v1 applies it): record whether turns 2-3 are hidden (staged reverts are open issue 619). 4s later v2 commits: turns 2-3 are removed. Variant clear: the revert is undone instead and all turns return.",
    setup: [
      { do: "createSession", title: "Revert me", as: "A" },
      { do: "seedTurns", session: "A", count: 3, text: "Turn" },
    ],
    automation: [
      { at: 5000, do: "revert", session: "A", userTurn: 2 },
      { at: 9000, do: "revertCommit", session: "A" },
    ],
    variants: {
      clear: [
        { at: 5000, do: "revert", session: "A", userTurn: 2 },
        { at: 9000, do: "revertClear", session: "A" },
      ],
    },
  },
  {
    id: "concurrent-sessions-home-order",
    priority: "P2",
    title: "Three sessions run at once",
    category: "multi-client",
    prompt: null,
    lanes: { S1: { prompt: "S1: write the docs" }, S2: { prompt: "S2: review the tests" }, S3: { prompt: "S3: clean the build" } },
    expect: "Watch Home. The harness creates and prompts S1, S2 and S3 about 0.8s apart: S1 and S2 stream (Working), S3 asks to run rm and shows Needs approval. Streaming alone must not reorder rows (issue 523), attention changes may; no duplicate rows (issue 548). Allow S3 to finish.",
    modelCalls: [
      { child: "S1", steps: [say("S1 reply: drafting the docs section by section…", 4, 200)] },
      { child: "S2", steps: [{ op: "wait", ms: 2000 }, say("S2 reply: the tests cover formatting only…", 4, 200)] },
      { child: "S3", steps: [run("rm -rf dist", "cleanup")] },
      { child: "S3", steps: [say("S3: removed dist/.", 8)] },
    ],
    automation: [
      { at: 0, do: "createSession", title: "S1 docs", as: "S1" },
      { at: 400, do: "prompt", session: "S1", text: "S1: write the docs" },
      { at: 800, do: "createSession", title: "S2 tests", as: "S2" },
      { at: 1200, do: "prompt", session: "S2", text: "S2: review the tests" },
      { at: 1600, do: "createSession", title: "S3 cleanup", as: "S3" },
      { at: 2000, do: "prompt", session: "S3", text: "S3: clean the build" },
    ],
  },
  {
    id: "edge-unicode-large-output",
    priority: "P2",
    title: "Unicode across chunks and a huge shell output",
    category: "edge",
    prompt: "Print a big list and summarise in a few scripts.",
    expect: "Allow the shell ask. The 200k-line output is truncated with a marker and the row stays scrollable. The reply streams one code point per chunk with frames split mid-byte: no mojibake or broken emoji/flags/RTL mid-stream or at the end. Reopening doesn't stall on a multi-MB payload (issue 524).",
    modelCalls: [
      { steps: [run("seq 1 200000", "Print many lines")] },
      { steps: [{ op: "text", value: "Done ✅ — 合計 ¥1,200 · المجموع ١٢٠٠ · 👩‍💻🇯🇵 · ½¢ rounding.", chunkChars: 1, delayMs: 30, splitBytes: true }] },
    ],
  },
];
