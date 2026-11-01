// Scenario catalog for the harness. Each modelCalls entry is one model request;
// `child` marks calls served to a lane (a subagent's child session, or an extra
// session whose first prompt is set in `lanes`). Values written as {v1, v2}
// resolve per protocol; ${PROJECT} is the project root, ${HARNESS_URL} the harness.
//
// Fields: prompt (what Run sends when the harness plays the user), expect,
// protocols, model (a harness model id), setup (steps run before arming),
// automation (steps run as another client), variants ({name: steps | {automation,
// modelCalls, summary, prompt}}), summary (steps for a compaction request),
// appOnly (needs a device to judge), checkVariant (variant check.mjs runs).
//
// Automation steps: { on: "<event type>" | [...], delayMs, do, ... } or { at: ms, do }.
// Actions: createSession{title, as, model}, prompt{text, session, model},
//   seedTurns{session, count, text}, wait{ms}, abort, replyPermission{reply},
//   replyQuestion{answers}, rejectQuestion, release{gate}, touch{path}, rename{title},
//   remove, compact, revert{userTurn}, revertCommit, revertClear, dropStreams,
//   offline{ms}, rejectSseAuth{ms}, stall, unstall, heartbeatOnly{value}, clearFaults.
// session: a name from setup (`as`), "latest", or omitted for the run's session.

import { P1 } from "./scenarios-p1.mjs";
import { P2 } from "./scenarios-p2.mjs";
import { shell, path, say, CLEAN_DIST, CURRENCY_QUESTION } from "./scenario-kit.mjs";

// Twelve options with descriptions: taller than a phone screen.
const MANY_OPTIONS_QUESTION = {
  header: "Currency source",
  question: "Where should formatMoney get the currency from? Pick the source that fits how invoices are created today.",
  multiple: false,
  options: [
    { label: "Argument", description: "Pass a currency code on every call" },
    { label: "Invoice field", description: "Read invoice.currency, default USD" },
    { label: "Env config", description: "Use ACME_CURRENCY for the whole app" },
    { label: "Customer record", description: "Look up the billing currency on the customer" },
    { label: "Account settings", description: "Use the account-wide default currency" },
    { label: "Locale", description: "Derive the currency from the user's locale" },
    { label: "Price list", description: "Take the currency from the active price list" },
    { label: "Tax region", description: "Map the tax region to its local currency" },
    { label: "Payment provider", description: "Ask the payment provider which currency it settles in" },
    { label: "Feature flag", description: "Roll out per-tenant currencies behind a flag" },
    { label: "Request header", description: "Read an X-Currency header on API requests" },
    { label: "Hard-code USD", description: "Keep USD everywhere for now and revisit later" },
  ],
};

// Twelve locales, multi-select: the second page of the two-step variant.
const MANY_LOCALES_QUESTION = {
  header: "Locales",
  question: "Which locales must formatMoney support at launch?",
  multiple: true,
  options: ["en-US", "en-GB", "de-DE", "fr-FR", "es-ES", "it-IT", "nl-NL", "pt-BR", "ja-JP", "ko-KR", "zh-CN", "hi-IN"].map(
    (label) => ({ label, description: `Format amounts the way ${label} expects` }),
  ),
};

const LONG_MARKDOWN = `## How formatting works

\`formatMoney(cents, currency)\` in \`src/format.ts\` divides by 100 and calls \`toFixed(2)\`. Three things go wrong:

1. **Float drift**: \`1005 / 100\` is \`10.049999…\`, so it rounds down.
2. Negative amounts lose the sign before the currency symbol.
3. The currency code is ignored for JPY, which has 0 minor units.

| Input (cents) | Currency | Today | Expected |
|---:|:---:|---|---|
| 1005 | USD | \`$10.04\` | \`$10.05\` |
| -250 | EUR | \`€-2.50\` | \`-€2.50\` |
| 1200 | JPY | \`¥12.00\` | \`¥1,200\` |

### Proposed fix

Use \`Intl.NumberFormat\` with integer math:

\`\`\`ts
const MINOR_UNITS: Record<string, number> = { USD: 2, EUR: 2, JPY: 0 }

export function formatMoney(cents: number, currency = "USD"): string {
  const digits = MINOR_UNITS[currency] ?? 2
  const value = digits === 0 ? cents : cents / 10 ** digits
  return new Intl.NumberFormat("en-US", {
    style: "currency",
    currency,
    minimumFractionDigits: digits,
    maximumFractionDigits: digits,
  }).format(value)
}
\`\`\`

> Note: \`Intl\` rounds half-even on some engines. Add a test for \`1005\` before shipping.

Want me to apply this and run \`npm test\`?`;

const NEW_FORMAT = `  const sign = cents < 0 ? "-" : ""
  const abs = Math.abs(Math.round(cents))
  const whole = Math.floor(abs / 100)
  const minor = String(abs % 100).padStart(2, "0")
  return \`\${sign}\${symbol}\${whole}.\${minor}\``;

const ROUNDING_TEST = `import { test } from "node:test"
import assert from "node:assert/strict"
import { formatMoney } from "../src/format.ts"

test("1005 cents rounds to $10.05", () => {
  assert.equal(formatMoney(1005, "USD"), "$10.05")
})

test("negative keeps sign before symbol", () => {
  assert.equal(formatMoney(-250, "USD"), "-$2.50")
})
`;


const P0 = [
  {
    id: "stream-plain-short",
    title: "Plain short text reply",
    category: "streaming",
    prompt: "What does this project do?",
    expect: "Home: Working, then idle. Chat: one assistant bubble streams in about 2s; inline code renders.",
    modelCalls: [
      {
        steps: [
          { op: "wait", ms: 400 },
          say("This is a small TypeScript library for invoices. `src/invoice.ts` adds up line items in cents, and `src/format.ts` turns cents into display strings. The tests live in `test/format.test.mjs`.", 12, 60),
        ],
      },
    ],
  },
  {
    id: "stream-long-markdown",
    title: "Long markdown reply (table, list, fenced code)",
    category: "streaming",
    prompt: "Explain how money formatting works here and propose a fix for the rounding bug.",
    expect: "Chat: headings, ordered list, table, ts code block and quote render progressively; scroll follows; no raw ``` flash.",
    modelCalls: [{ steps: [say(LONG_MARKDOWN, 5, 25)] }],
  },
  {
    id: "reasoning-then-text",
    title: "Reasoning streams, then the answer",
    category: "streaming",
    prompt: "Is totalCents safe for very large invoices?",
    expect: "Chat: a reasoning/thinking block streams first, then the answer. Reopening the session shows the reasoning from history.",
    modelCalls: [
      {
        steps: [
          { op: "reason", value: "The user asks about overflow. totalCents sums integer cents with reduce. JS numbers are exact up to 2^53, about 9e15 cents. Quantity is typed as number, so 1.5 * 333 = 499.5 is possible and produces fractional cents.", chunkChars: 8, delayMs: 40 },
          { op: "wait", ms: 600 },
          say("Overflow isn't a realistic risk: integer cents stay exact up to about 9×10¹⁵. The real issue is **fractional quantities**. `1.5 × 333` gives `499.5` cents, and that drifts when summed. Round each line with `Math.round(quantity * unitCents)` before adding.", 10, 40),
        ],
      },
    ],
  },
  {
    id: "tool-row-tour",
    title: "Tool loop: glob, grep, read, edit, write, shell",
    category: "tools",
    prompt: "Fix the rounding bug in formatMoney and add a regression test.",
    expect: "Chat: rows for glob, grep, read (may group as Explored), edit (+/- counts), write, shell (asks permission: allow it), then the summary. Home stays Working across steps.",
    modelCalls: [
      { steps: [say("Let me find the formatter."), { op: "tool", name: "glob", input: { pattern: "src/**/*.ts" } }] },
      { steps: [{ op: "tool", name: "grep", input: { pattern: "toFixed", include: "*.ts" } }] },
      { steps: [{ op: "tool", name: "read", input: path("src/format.ts") }] },
      {
        steps: [
          say("The float division is the bug. Switching to integer math:"),
          {
            op: "tool",
            name: "edit",
            input: {
              v1: { filePath: "${PROJECT}/src/format.ts", oldString: "  return `${symbol}${(cents / 100).toFixed(2)}`", newString: NEW_FORMAT },
              v2: { path: "src/format.ts", oldString: "  return `${symbol}${(cents / 100).toFixed(2)}`", newString: NEW_FORMAT },
            },
          },
        ],
      },
      {
        steps: [
          {
            op: "tool",
            name: "write",
            input: {
              v1: { filePath: "${PROJECT}/test/rounding.test.mjs", content: ROUNDING_TEST },
              v2: { path: "test/rounding.test.mjs", content: ROUNDING_TEST },
            },
          },
        ],
      },
      { steps: [{ op: "tool", name: shell, input: { command: "node --experimental-strip-types --test test/*.test.mjs", description: "Run the test suite" } }] },
      { steps: [say("Fixed. `formatMoney` now uses integer cents, so `1005` formats as `$10.05` and negatives keep the sign before the symbol. I added `test/rounding.test.mjs`.", 12, 30)] },
    ],
  },
  {
    id: "permission-allow-in-app",
    title: "Shell permission asked, allow in the app",
    category: "permission",
    prompt: "Clean the stale build output.",
    expect: "Home: Needs approval. Chat: approval card for `rm -rf dist`; tap Allow once; shell row completes; reply streams.",
    modelCalls: CLEAN_DIST,
  },
  {
    id: "permission-answered-from-outside",
    title: "Permission answered by another client",
    category: "permission",
    prompt: "Clean the stale build output.",
    expect: "Do not touch the app: the harness allows the ask 3s after it appears. The card and Needs approval clear by themselves within ~1s.",
    modelCalls: CLEAN_DIST,
    automation: [{ on: "permission.asked", delayMs: 3000, do: "replyPermission", reply: "once" }],
    // A reject ends the turn on both servers, so the reply call goes unused.
    variants: { reject: { automation: [{ on: "permission.asked", delayMs: 3000, do: "replyPermission", reply: "reject" }], optionalCalls: 1 } },
  },
  {
    id: "question-answer-in-app",
    title: "Question tool, answer in the app",
    category: "question",
    prompt: "Add currency support to formatMoney.",
    expect: "Chat: question card with 3 options; pick one; the reply streams. (Known gap: Home row keeps showing Working.) v2 delivers it as a form.",
    modelCalls: [
      {
        steps: [
          say("Before I change the API, one question:"),
          {
            op: "tool",
            name: "question",
            input: {
              questions: [CURRENCY_QUESTION],
            },
          },
        ],
      },
      { steps: [say("Got it. I'll go with that and fall back to `USD`.")] },
    ],
    variants: {
      // More options than fit on a phone screen: the card must scroll and keep Submit reachable.
      "many-options": {
        modelCalls: [
          {
            steps: [
              say("Before I change the API, one question:"),
              { op: "tool", name: "question", input: { questions: [MANY_OPTIONS_QUESTION] } },
            ],
          },
          { steps: [say("Got it. I'll go with that and fall back to `USD`.")] },
        ],
      },
      // Two long questions, the second multi-select: paging opens each one at its top.
      "many-options-two-step": {
        modelCalls: [
          {
            steps: [
              say("Before I change the API, two questions:"),
              { op: "tool", name: "question", input: { questions: [MANY_OPTIONS_QUESTION, MANY_LOCALES_QUESTION] } },
            ],
          },
          { steps: [say("Got it. I'll use that source and support the locales you picked.")] },
        ],
      },
    },
  },
  {
    id: "todo-list-progress",
    title: "Todo list evolves across a turn (v1 only)",
    category: "todo",
    protocols: ["v1"],
    prompt: "Add JPY support end to end.",
    expect: "Chat: todo pill appears with 4 items and advances twice; last state 3 done + 1 cancelled. v2 has no todo tool.",
    modelCalls: [
      { steps: [{ op: "tool", name: "todowrite", input: { todos: todos("in_progress", "pending", "pending", "pending") } }] },
      { steps: [{ op: "wait", ms: 1500 }, { op: "tool", name: "todowrite", input: { todos: todos("completed", "in_progress", "pending", "pending") } }] },
      { steps: [{ op: "wait", ms: 1500 }, { op: "tool", name: "todowrite", input: { todos: todos("completed", "completed", "completed", "cancelled") } }] },
      { steps: [say("JPY is supported. I skipped the README update; say if you want it.", 12, 30)] },
    ],
  },
  {
    id: "subagent-task-foreground",
    title: "Subagent with a child session",
    category: "subagent",
    prompt: "Audit the codebase for other float-money bugs.",
    expect: "Chat: a running task/subagent card; tap it to watch the child session stream its grep and findings; parent resumes with the final text.",
    modelCalls: [
      {
        steps: [
          say("I'll delegate a sweep to a subagent."),
          {
            op: "tool",
            name: { v1: "task", v2: "subagent" },
            child: "sweep",
            input: {
              v1: { description: "Find float money math", prompt: "Search src/ for money values computed with floating point (division by 100, toFixed, parseFloat). Report file:line.", subagent_type: "general" },
              v2: { description: "Find float money math", prompt: "Search src/ for money values computed with floating point (division by 100, toFixed, parseFloat). Report file:line.", agent: "general" },
            },
          },
        ],
      },
      { child: "sweep", steps: [{ op: "tool", name: "grep", input: { pattern: "toFixed|parseFloat|/ 100", include: "*.ts" } }] },
      { child: "sweep", steps: [{ op: "wait", ms: 1500 }, say("Found 2 spots:\n- `src/format.ts:9` divides cents by 100 and calls toFixed\n- `src/invoice.ts:18` uses `parseFloat(line.price)`", 10, 40)] },
      { steps: [say("The subagent found a second bug in `src/invoice.ts:18`: `parseFloat` on prices. I'll fix both next.", 12, 30)] },
    ],
  },
  {
    id: "provider-error-non-retryable",
    title: "Provider returns 401: visible turn error",
    category: "error",
    prompt: "Summarise the README.",
    expect: "Chat: the turn fails with a readable error (invalid API key); Home leaves Working; no endless retry spinner.",
    modelCalls: [
      {
        steps: [
          {
            op: "httpError",
            status: 401,
            body: { error: { message: "Incorrect API key provided: sk-test-****.", type: "invalid_request_error", code: "invalid_api_key" } },
          },
        ],
      },
    ],
  },
  {
    id: "hang-then-abort-in-app",
    title: "Model hangs mid-stream; tap Stop",
    category: "abort",
    prompt: "Write a migration guide for the new formatMoney.",
    expect: "Chat: partial heading streams, then stalls. Tap Stop: the turn ends as interrupted, Stop chrome clears, Home leaves Working.",
    modelCalls: [{ steps: [say("# Migrating to the new formatMoney\n\nThe old signature was", 6, 50), { op: "hang" }] }],
    variants: { "abort-from-outside": [{ on: "harness.run.started", delayMs: 4000, do: "abort" }] },
  },
  {
    id: "sse-drop-midstream-reconnect",
    title: "SSE dropped mid-stream; reconnect catches up",
    category: "reconnect",
    prompt: "Draft release notes for 1.2.0.",
    expect: "The proxy cuts the app's event streams ~3s in and refuses connections for 3s. After reconnect the full text is there, with no duplicate or rewound text; Home ends idle.",
    modelCalls: [
      {
        steps: [
          say("## 1.2.0\n\n### Fixed\n- `formatMoney` rounds 1005 cents to $10.05 instead of $10.04.\n- Negative amounts keep their sign before the currency symbol.\n\n### Added\n- JPY support (zero minor units).\n- `test/rounding.test.mjs` regression tests.\n\n### Internal\n- Integer cents everywhere; no float division.\n", 8, 80),
        ],
      },
    ],
    automation: [{ on: "harness.run.started", delayMs: 3000, do: "offline", ms: 3000 }],
    variants: {
      "finish-while-disconnected": [{ on: "harness.run.started", delayMs: 2000, do: "offline", ms: 9000 }],
      "silent-socket": [
        { on: "harness.run.started", delayMs: 2000, do: "heartbeatOnly", value: true },
        { on: "harness.run.started", delayMs: 9000, do: "heartbeatOnly", value: false },
      ],
    },
  },
  {
    id: "session-created-by-other-client",
    title: "Another client creates and runs a session",
    category: "multi-client",
    prompt: null,
    expect: "Leave the app on Home. The harness creates 'Explain totalCents' and prompts it: the row appears live, shows Working, then idle with the reply.",
    sessionTitle: "Explain totalCents",
    modelCalls: [{ steps: [{ op: "wait", ms: 1000 }, say("Sure — `totalCents` sums `quantity × unitCents` for each line, in integer cents.", 8, 80)] }],
    automation: [
      { at: 0, do: "createSession", title: "Explain totalCents" },
      { at: 1500, do: "prompt", text: "Explain totalCents" },
    ],
  },
];

export const SCENARIOS = [...P0.map((scenario) => ({ priority: "P0", ...scenario })), ...P1, ...P2];

function todos(a, b, c, d) {
  return [
    { content: "Add MINOR_UNITS table", status: a, priority: "high", id: "1" },
    { content: "Use minor units in formatMoney", status: b, priority: "high", id: "2" },
    { content: "Add JPY test", status: c, priority: "medium", id: "3" },
    { content: "Update README", status: d, priority: "low", id: "4" },
  ];
}
