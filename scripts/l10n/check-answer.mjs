#!/usr/bin/env node
// Checks an agent's answer to a hand-off task (translate.mjs --handoff) before the script
// applies it, and writes nothing. Errors: a string the task asks for is missing, or breaks the
// rules check-translations.mjs enforces (placeholders, plural forms, escaping). Hints: things
// worth a second look, such as a translation that doesn't use the language's word for a term
// (l10n/terms.json); fix those only where they are real problems.
//
// Usage: node scripts/l10n/check-answer.mjs <task>.answer.json
// Reads <task>.task.json next to it. Exits 1 when there are errors.

import { readFileSync } from "node:fs";
import { resolveLanguages } from "./languages.mjs";
import { problemsOf } from "./rules.mjs";
import { termMisses } from "./terms.mjs";
import { answerFor, normalizeAnswer, parseAnswerText } from "./answers.mjs";

const path = process.argv[2];
if (!path?.endsWith(".answer.json")) {
  console.error("Usage: node scripts/l10n/check-answer.mjs <task>.answer.json");
  process.exit(2);
}
const task = JSON.parse(readFileSync(path.replace(/\.answer\.json$/, ".task.json"), "utf8"));
let answer;
try {
  answer = parseAnswerText(readFileSync(path, "utf8"));
} catch (e) {
  console.log(`error: ${path} has no valid JSON object (${e.message})`);
  process.exit(1);
}
const reviewing = task.kind.includes("review");
const field = reviewing ? "fixes" : "translations";
const codes = Object.keys(task.languages);
let errors = 0;
let hints = 0;
for (const code of codes) {
  const [language] = resolveLanguages([code]);
  const got = answerFor(answer, field, language, codes.length === 1);
  const asked = new Set(task.languages[code]);
  const lines = [];
  const error = (message) => (errors++, lines.push(`  error ${message}`));
  const hint = (message) => (hints++, lines.push(`  hint  ${message}`));
  for (const id of Object.keys(got)) if (!asked.has(id)) error(`${id}: not in this task`);
  for (const id of asked) {
    const string = task.strings[id];
    if (got[id] === undefined) {
      if (!reviewing) error(`${id}: missing`);
      continue;
    }
    const value = normalizeAnswer(got[id], language, string.plural);
    if (value === null) {
      error(`${id}: expected ${string.plural ? "plural forms" : "text"}`);
      continue;
    }
    const problems = problemsOf(string.english, value, language, string.plural);
    for (const e of problems.errors) error(`${id}: ${e}`);
    for (const w of problems.warnings) hint(`${id}: ${w}`);
    for (const { term, word } of termMisses(string.english, value, language)) {
      hint(`${id}: doesn't use "${word}" for "${term}" (fine where the English means something else)`);
    }
  }
  console.log(lines.length ? `${code}:\n${lines.join("\n")}` : `${code}: ok`);
}
console.log(`${errors} errors, ${hints} hints.`);
process.exit(errors ? 1 : 0);
