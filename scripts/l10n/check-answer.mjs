#!/usr/bin/env node
// Checks answers to translation tasks (scripts/translate.mjs tasks) and writes nothing.
// Errors: a string the task asks for is missing, or breaks the rules check-translations.mjs
// enforces (placeholders, plural forms, escaping). Hints: worth a second look, such as a term
// translated with another word than the language's (l10n/terms.json) or a different translation
// of the same English elsewhere in the app. A hint can be dismissed when the string means
// something else there.
//
// Usage: node scripts/l10n/check-answer.mjs <task folder | <task>.answer.json>
// A folder checks every task in it. Exits 1 when there are errors.

import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import { languageByCode, resolveLanguages } from "./languages.mjs";
import { problemsOf } from "./rules.mjs";
import { termMisses } from "./terms.mjs";
import { answerFor, normalizeAnswer, parseAnswerText } from "./answers.mjs";
import { loadUnits, textsOf, readCatalog, readAndroidNeutral, readIosNeutral } from "./strings.mjs";

const target = process.argv[2];
if (!target || !existsSync(target)) {
  console.error("Usage: node scripts/l10n/check-answer.mjs <task folder | <task>.answer.json>");
  process.exit(2);
}

// Answer files to check: every task's in a folder, or the one given.
function answerPaths(path) {
  if (!statSync(path).isDirectory()) return [path];
  const found = [];
  for (const folder of readdirSync(path)) {
    if (!statSync(join(path, folder)).isDirectory()) continue;
    for (const name of readdirSync(join(path, folder)).filter((n) => n.endsWith(".task.json")).sort()) {
      found.push(join(path, folder, name.replace(/\.task\.json$/, ".answer.json")));
    }
  }
  return found;
}

const units = loadUnits();
const catalog = readCatalog();
const flat = (v) => (typeof v === "string" ? [v] : Object.values(v));

// How the app already translates each English text, per language: English → translation → id.
const elsewhereCache = new Map();
function translationsByEnglish(language, skip) {
  if (!elsewhereCache.has(language.ios)) {
    const android = readAndroidNeutral(language);
    const byEnglish = new Map();
    for (const unit of units) {
      for (const text of textsOf(unit)) {
        const value = (text.platform !== "ios" && unit.android ? android.get(unit.android.name) : undefined) ?? (text.platform !== "android" && unit.ios ? readIosNeutral(catalog, unit, language)?.text : undefined);
        if (value === undefined || value === null) continue;
        const key = JSON.stringify(text.english);
        if (!byEnglish.has(key)) byEnglish.set(key, new Map());
        byEnglish.get(key).set(JSON.stringify(value), text.id);
      }
    }
    elsewhereCache.set(language.ios, byEnglish);
  }
  const byEnglish = elsewhereCache.get(language.ios);
  return (english) => [...(byEnglish.get(JSON.stringify(english)) ?? [])].filter(([, id]) => !skip.has(id));
}

let errors = 0;
let hints = 0;

function check(path) {
  const task = JSON.parse(readFileSync(path.replace(/\.answer\.json$/, ".task.json"), "utf8"));
  const reviewing = task.kind.includes("review");
  const field = reviewing ? "fixes" : "translations";
  const codes = Object.keys(task.languages);
  const out = [];
  const error = (message) => (errors++, out.push(`  error ${message}`));
  const hint = (message) => (hints++, out.push(`  hint  ${message}`));
  let answer;
  try {
    answer = parseAnswerText(readFileSync(path, "utf8"));
  } catch (e) {
    error(e.code === "ENOENT" ? "no answer file yet" : `not a valid JSON object (${e.message})`);
    return out;
  }
  if (!answer || typeof answer[field] !== "object") {
    error(`the answer needs a "${field}" object`);
    return out;
  }
  // Language keys the task doesn't have ("jp" for ja) would otherwise only show as missing strings.
  const top = Object.keys(answer[field]);
  const nested = codes.length > 1 || top.some((k) => languageByCode(k));
  if (nested) for (const code of top.filter((k) => !codes.includes(k))) error(`"${code}": not a language of this task (${codes.join(", ")})`);
  const skip = new Set(Object.keys(task.strings));
  for (const code of codes) {
    const [language] = resolveLanguages([code]);
    const got = answerFor(answer, field, language, codes.length === 1);
    const asked = new Set(task.languages[code]);
    const elsewhere = translationsByEnglish(language, skip);
    const lines = [];
    const before = out.length;
    for (const id of Object.keys(got)) if (!asked.has(id)) error(`${code} ${id}: not in this task`);
    for (const id of asked) {
      const string = task.strings[id];
      if (got[id] === undefined) {
        if (!reviewing) error(`${code} ${id}: missing`);
        continue;
      }
      const value = normalizeAnswer(got[id], language, string.plural);
      if (value === null) {
        error(`${code} ${id}: expected ${string.plural ? "plural forms, one text per form" : "text"}`);
        continue;
      }
      const problems = problemsOf(string.english, value, language, string.plural);
      for (const e of problems.errors) error(`${code} ${id}: ${e}`);
      for (const w of problems.warnings) hint(`${code} ${id}: ${w}`);
      for (const { term, word, source } of termMisses(string.english, value, language)) {
        hint(`${code} ${id}: doesn't use ${source === "desktop" ? "the desktop app's" : "the chosen"} word "${word}" for "${term}"`);
      }
      const texts = flat(value);
      if (texts.some((t) => t !== t.trim())) hint(`${code} ${id}: leading or trailing spaces`);
      for (const mark of ["…", "·"]) if (flat(string.english).some((e) => e.includes(mark)) && !texts.some((t) => t.includes(mark))) hint(`${code} ${id}: the English has ${mark}, the translation doesn't`);
      const same = elsewhere(string.english);
      if (same.length && !same.some(([v]) => v === JSON.stringify(value))) {
        hint(`${code} ${id}: the same English is translated as ${same.slice(0, 3).map(([v, other]) => `${v} in ${other}`).join(", ")}`);
      }
    }
    if (out.length === before) lines.push(`  ${code}: ok`);
    out.push(...lines);
  }
  return out;
}

for (const path of answerPaths(target)) {
  const lines = check(path);
  console.log(`${path.split("/").slice(-2).join("/")}:\n${lines.join("\n")}`);
}
console.log(`${errors} errors, ${hints} hints.`);
process.exit(errors ? 1 : 0);
