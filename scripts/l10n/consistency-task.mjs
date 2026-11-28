// Review tasks written by the one-off consistency and term-sweep passes, in the shape
// `node scripts/translate.mjs apply <dir>` reads.

import { existsSync, writeFileSync } from "node:fs";
import { join } from "node:path";

// The task.json and plan.json `translate.mjs apply` reads, for a one-language review task.
export function writeReviewTask(dir, base, language, rows) {
  const strings = Object.fromEntries(rows.map((r) => [r.id, { english: r.english, plural: typeof r.english !== "string" }]));
  writeFileSync(`${base}.task.json`, `${JSON.stringify({ kind: "review", strings, languages: { [language.ios]: Object.keys(strings) } })}\n`);
  const planPath = join(dir, "plan.json");
  if (!existsSync(planPath)) writeFileSync(planPath, `${JSON.stringify({ kind: "review", languages: [], full: false, stale: [] }, null, 2)}\n`);
}
