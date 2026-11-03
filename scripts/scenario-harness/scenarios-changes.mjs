// Changes screen scenarios: what Last turn and Working tree show for different kinds of change.
// Format: see scenarios.mjs. Open Changes from the chat menu after each turn and switch sources.

import { path, say, run } from "./scenario-kit.mjs";

const OLD_RETURN = "  return `${symbol}${(cents / 100).toFixed(2)}`";
const NEW_RETURN = `  const sign = cents < 0 ? "-" : ""
  const abs = Math.abs(Math.round(cents))
  return \`\${sign}\${symbol}\${Math.floor(abs / 100)}.\${String(abs % 100).padStart(2, "0")}\``;

const ROUNDING_TEST = `import { test } from "node:test"
import assert from "node:assert/strict"
import { formatMoney } from "../src/format.ts"

test("1005 cents rounds to $10.05", () => {
  assert.equal(formatMoney(1005, "USD"), "$10.05")
})
`;

// invoice.ts as a teammate edited it outside opencode: totalCents reuses lineCents.
const INVOICE_EDITED = `export interface InvoiceLine {
  description: string
  quantity: number
  price: string
}

export interface Invoice {
  id: string
  currency: string
  lines: InvoiceLine[]
}

export function lineCents(line: InvoiceLine): number {
  return Math.round(Number(line.price) * 100) * line.quantity
}

export function totalCents(invoice: Invoice): number {
  return invoice.lines.reduce((sum, line) => sum + lineCents(line), 0)
}
`;

const NOTES = `# Release notes

- Rounding fix for formatMoney is still pending review.
`;

const editFormat = {
  op: "tool",
  name: "edit",
  input: {
    v1: { filePath: "${PROJECT}/src/format.ts", oldString: OLD_RETURN, newString: NEW_RETURN },
    v2: { path: "src/format.ts", oldString: OLD_RETURN, newString: NEW_RETURN },
  },
};

const writeTest = {
  op: "tool",
  name: "write",
  input: {
    v1: { filePath: "${PROJECT}/test/rounding.test.mjs", content: ROUNDING_TEST },
    v2: { path: "test/rounding.test.mjs", content: ROUNDING_TEST },
  },
};

const allowShell = { on: "permission.asked", delayMs: 800, do: "replyPermission", reply: "once" };

export const CHANGES = [
  {
    id: "changes-turn-mixed",
    priority: "P1",
    title: "Turn modifies, adds and deletes a file",
    category: "changes",
    prompt: "Fix rounding in formatMoney, add a test, and drop the legacy formatter.",
    expect:
      "The harness allows the rm ask. Changes, Last turn: 3 files, src/format.ts Modified, test/rounding.test.mjs Added, src/legacy-format.js Deleted. Working tree: the same 3 files.",
    modelCalls: [
      { steps: [say("Switching formatMoney to integer math."), editFormat] },
      { steps: [writeTest] },
      { steps: [run("rm src/legacy-format.js", "Remove the legacy formatter")] },
      { steps: [say("Fixed rounding, added `test/rounding.test.mjs`, and removed `src/legacy-format.js`.")] },
    ],
    automation: [allowShell],
  },
  {
    id: "changes-readonly-dirty-tree",
    priority: "P1",
    title: "Read-only turn while the tree has outside edits",
    category: "changes",
    prompt: "How is the invoice total computed?",
    expect:
      "Before the turn the harness edits src/invoice.ts and adds NOTES.md as if from another editor. Changes, Last turn: no changes (the turn only read). Working tree: src/invoice.ts Modified and NOTES.md Added.",
    setup: [
      { do: "touch", path: "src/invoice.ts", content: INVOICE_EDITED },
      { do: "touch", path: "NOTES.md", content: NOTES },
    ],
    modelCalls: [
      { steps: [{ op: "tool", name: "read", input: path("src/invoice.ts") }] },
      { steps: [say("`totalCents` sums `lineCents(line)` for every line, so each line is rounded to whole cents before adding.")] },
    ],
  },
  {
    id: "changes-turn-committed",
    priority: "P1",
    title: "Turn edits, then the change is committed",
    category: "changes",
    prompt: "Fix rounding in formatMoney.",
    expect:
      "The harness commits everything 1.5s after the turn ends. Changes, Last turn: src/format.ts Modified (the turn's own snapshot diff). Working tree: no changes, because the tree is clean after the commit.",
    modelCalls: [
      { steps: [say("Switching formatMoney to integer math."), editFormat] },
      { steps: [say("Fixed. `formatMoney` now rounds through integer cents.")] },
    ],
    automation: [{ on: "harness.run.finished", delayMs: 1500, do: "gitCommit", message: "Fix rounding" }],
  },
];
