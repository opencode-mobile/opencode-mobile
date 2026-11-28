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
import { languageByCode, resolveLanguages } from "./languages.mjs";
import { loadUnits, textsOf, readCatalog, readAndroidNeutral, readIosNeutral } from "./strings.mjs";
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
// Language keys the task doesn't have ("jp" for ja) would otherwise only show as missing strings.
const top = Object.keys(answer[field] ?? {});
const strayCodes = codes.length === 1 && !top.some((k) => k in task.strings || languageByCode(k)) ? [] : top.filter((k) => !(k in task.strings) && !codes.includes(k));
for (const code of strayCodes) {
  errors++;
  console.log(`error "${code}": not a language of this task (${codes.join(", ")})`);
}

// How the app already translates each English text elsewhere, per language, so a new string
// with the same English can match it.
const units = loadUnits();
const catalog = readCatalog();
function translationsByEnglish(language) {
  const android = readAndroidNeutral(language);
  const byEnglish = new Map();
  for (const unit of units) {
    for (const text of textsOf(unit)) {
      if (text.id in task.strings) continue;
      const value = (text.platform !== "ios" && unit.android ? android.get(unit.android.name) : undefined) ?? (text.platform !== "android" && unit.ios ? readIosNeutral(catalog, unit, language)?.text : undefined);
      if (value === undefined || value === null) continue;
      const key = JSON.stringify(text.english);
      if (!byEnglish.has(key)) byEnglish.set(key, new Set());
      byEnglish.get(key).add(JSON.stringify(value));
    }
  }
  return byEnglish;
}
const flat = (v) => (typeof v === "string" ? [v] : Object.values(v));
for (const code of codes) {
  const [language] = resolveLanguages([code]);
  const got = answerFor(answer, field, language, codes.length === 1);
  const asked = new Set(task.languages[code]);
  const elsewhere = translationsByEnglish(language);
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
    for (const { term, word, source } of termMisses(string.english, value, language)) {
      hint(`${id}: doesn't use ${source === "desktop" ? "the desktop app's" : "the chosen"} word "${word}" for "${term}" (fine where the English means something else)`);
    }
    const english = flat(string.english);
    const texts = flat(value);
    if (texts.some((t) => t !== t.trim())) hint(`${id}: leading or trailing spaces`);
    for (const mark of ["…", "·"]) if (english.some((e) => e.includes(mark)) && !texts.some((t) => t.includes(mark))) hint(`${id}: the English has ${mark}, the translation doesn't`);
    const same = elsewhere.get(JSON.stringify(string.english));
    if (same && !same.has(JSON.stringify(value))) hint(`${id}: the same English is translated elsewhere as ${[...same].slice(0, 3).join(" or ")}`);
  }
  console.log(lines.length ? `${code}:\n${lines.join("\n")}` : `${code}: ok`);
}
console.log(`${errors} errors, ${hints} hints.`);
process.exit(errors ? 1 : 0);
