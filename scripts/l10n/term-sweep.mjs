#!/usr/bin/env node
// After a consistency pass, finds every string that still doesn't use a term's chosen word.
//
// Usage: node scripts/l10n/term-sweep.mjs <consistency dir> <out dir> [codes… | all]
//
// The consistency pass (scripts/l10n/consistency.mjs) sees at most a sample of strings per term
// and answers with the word it chose for each ({"terms": {"session": "…"}}). This lists, for each
// chosen term, all English strings that use the term but whose translation doesn't contain the
// chosen word's stem, and writes them as <out dir>/<code>/sweep-001.prompt.md for a reviewer to
// fix (only where the string means that term). The answer is applied with
// `node scripts/translate.mjs <code> --review --answers <out dir>`.

import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { resolveLanguages } from "./languages.mjs";
import { loadUnits, textsOf, readCatalog, readAndroidNeutral, readIosNeutral } from "./strings.mjs";

const [inDir, outDir, ...codes] = process.argv.slice(2);
if (!inDir || !outDir) {
  console.error("Usage: node scripts/l10n/term-sweep.mjs <consistency dir> <out dir> [codes… | all]");
  process.exit(1);
}
const NO_SPACES = new Set(["ja", "zh-Hans", "zh-Hant", "th", "lo", "km", "my", "dz"]);
const units = loadUnits();
const catalog = readCatalog();
const flat = (v) => (typeof v === "string" ? v : Object.values(v).join(" / "));

function stem(word, language) {
  const w = word.trim().toLowerCase();
  if (NO_SPACES.has(language.ios)) return w;
  const first = w.split(/\s+/)[0];
  return first.slice(0, Math.max(3, Math.min(first.length, Math.ceil(first.length * 0.65))));
}

for (const language of resolveLanguages(codes)) {
  const answerPath = join(inDir, language.ios, "consistency-001.answer.json");
  if (!existsSync(answerPath)) {
    console.log(`${language.ios}: no consistency answer; skipped`);
    continue;
  }
  const terms = JSON.parse(readFileSync(answerPath, "utf8")).terms ?? {};
  const android = readAndroidNeutral(language);
  const rows = [];
  for (const unit of units) {
    for (const text of textsOf(unit)) {
      const value = (unit.android ? android.get(unit.android.name) : undefined) ?? (unit.ios ? readIosNeutral(catalog, unit, language)?.text : undefined);
      if (value !== undefined && value !== null) rows.push({ id: text.id, english: text.english, translation: value });
    }
  }
  const groups = [];
  for (const [term, chosen] of Object.entries(terms)) {
    if (!chosen || /form of address|politeness/i.test(term) || typeof chosen !== "string") continue;
    const pattern = new RegExp(`\\b${term.replace(/[.*+?^${}()|[\]\\]/g, "\\$&").replace(/ /g, "\\s+")}(e?s|ed|ing)?\\b`, "i");
    const s = stem(chosen, language);
    const off = rows.filter((r) => pattern.test(flat(r.english)) && !flat(r.translation).toLowerCase().includes(s));
    if (off.length) groups.push({ term, chosen, strings: off });
  }
  const count = groups.reduce((n, g) => n + g.strings.length, 0);
  if (!count) {
    console.log(`${language.ios}: nothing to sweep`);
    continue;
  }
  const prompt = `You are a native ${language.name} (${language.ios}) localization reviewer for OpenCode Mobile, a phone app that remotely controls OpenCode, an AI coding agent. A consistency review chose one ${language.name} word per product term (shown as "chosen" in each group). The strings below use the English term but their translation doesn't contain the chosen word.

For each string: if it uses the term in the same sense, rewrite it with the chosen word, inflected correctly, keeping the rest. If the string uses the word in another sense, or already says it correctly with another form of the word, leave it out. Keep placeholders {1}, {2}, … exactly; plural items (objects) keep the same forms they have now (${language.plurals.join(", ")}).

Answer with one JSON object only: {"fixes": {"<id>": "<corrected text>" or {"<form>": "<text>"}}, "reasons": {"<id>": "<few words>"}}. No prose, no code fences.

Groups (JSON):
${JSON.stringify(groups, null, 1)}`;
  const folder = join(outDir, language.ios);
  mkdirSync(folder, { recursive: true });
  const base = join(folder, "sweep-001");
  writeFileSync(`${base}.prompt.md`, `${prompt}\n\nWrite only the JSON object to ${base}.answer.json (UTF-8, no code fences). Make sure it parses as JSON.\n`);
  console.log(`${language.ios}: ${count} strings in ${groups.length} terms`);
}
