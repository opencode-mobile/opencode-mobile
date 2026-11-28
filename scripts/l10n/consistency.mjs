#!/usr/bin/env node
// Writes a term-consistency review request per language, for another agent to answer.
//
// Usage: node scripts/l10n/consistency.mjs <dir> [codes… | all]
//        node scripts/l10n/consistency.mjs --save <dir>
//
// A one-off pass after translating a language in full; each request is large (every string that
// uses a term). Later changes don't need it: --save writes the words each answer chose
// ("terms") to l10n/terms.json, and translate.mjs sends those words with every request.
//
// For each product term (TERMS below and the glossary), it lists the English strings that use it
// with their current translation, so a reviewer can pick one word per term and fix the strings
// that use another. It writes <dir>/<ios code>/consistency-001.prompt.md; the answer goes next to
// it as consistency-001.answer.json ({"fixes": {...}, "reasons": {...}}) and is applied with
// `node scripts/translate.mjs apply <dir>` (it writes the task.json and plan.json apply reads).

import { existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { resolveLanguages } from "./languages.mjs";
import { writeReviewTask } from "./consistency-task.mjs";
import { ROOT, loadUnits, textsOf, readCatalog, readAndroidNeutral, readIosNeutral } from "./strings.mjs";

const TERMS = [
  "session", "server", "project", "agent", "model", "variant", "provider", "archive", "worktree",
  "main checkout", "branch", "permission", "question", "attachment", "pair", "pairing", "compact",
  "fork", "revert", "undo", "retry", "dismiss", "queue", "steer", "home", "uptime", "reconnect",
  "hunk", "uncommitted", "diff", "changes", "shell", "terminal", "command", "subagent", "todo",
  "skill", "prompt", "context", "token", "usage", "connection profile", "app lock", "dictation",
  "server health", "side question", "notification", "theme", "appearance", "allow", "deny",
  "reject", "always allow", "clear", "remove", "delete", "stop", "send", "share", "copy", "settings",
  "folder", "file", "path", "directory", "log", "line", "step", "dictation",
];
const PER_TERM = 30;

if (process.argv[2] === "--save") {
  saveTerms(process.argv[3]);
  process.exit(0);
}

// Merges the chosen words from <dir>/<code>/consistency-001.answer.json into l10n/terms.json.
function saveTerms(answers) {
  const path = join(ROOT, "l10n/terms.json");
  const doc = existsSync(path) ? JSON.parse(readFileSync(path, "utf8")) : { languages: {} };
  for (const code of readdirSync(answers)) {
    const file = join(answers, code, "consistency-001.answer.json");
    if (!existsSync(file)) continue;
    const text = readFileSync(file, "utf8");
    const terms = JSON.parse(text.slice(text.indexOf("{"), text.lastIndexOf("}") + 1)).terms ?? {};
    const words = Object.entries(terms).filter(([, word]) => typeof word === "string" && word.trim());
    doc.languages[code] = Object.fromEntries(words.sort(([a], [b]) => a.localeCompare(b)));
    console.log(`${code}: ${words.length} terms`);
  }
  doc.languages = Object.fromEntries(Object.entries(doc.languages).sort(([a], [b]) => a.localeCompare(b)));
  writeFileSync(path, `${JSON.stringify(doc, null, 2)}\n`);
}

const [dir, ...codes] = process.argv.slice(2);
if (!dir) {
  console.error("Usage: node scripts/l10n/consistency.mjs <dir> [codes… | all]");
  process.exit(1);
}
const glossary = JSON.parse(readFileSync(join(ROOT, "l10n/glossary.json"), "utf8"));
const units = loadUnits();
const catalog = readCatalog();

for (const language of resolveLanguages(codes)) {
  const android = readAndroidNeutral(language);
  const rows = [];
  for (const unit of units) {
    for (const text of textsOf(unit)) {
      const value = (unit.android ? android.get(unit.android.name) : undefined) ?? (unit.ios ? readIosNeutral(catalog, unit, language)?.text : undefined);
      if (value === undefined || value === null) continue;
      rows.push({ id: text.id, english: text.english, translation: value });
    }
  }
  const englishText = (e) => (typeof e === "string" ? e : Object.values(e).join(" / "));
  const groups = [];
  for (const term of TERMS) {
    const pattern = new RegExp(`\\b${term.replace(/ /g, "\\s+")}(e?s|ed|ing)?\\b`, "i");
    const matches = rows.filter((r) => pattern.test(englishText(r.english))).slice(0, PER_TERM);
    if (matches.length >= 2) groups.push({ term, strings: matches });
  }
  // Form of address: strings that speak to the user, so one register (formal or informal) wins.
  const addressing = /\byou(r|rs|'re|'ll|'ve)?\b|^(Try|Select|Choose|Tap|Enter|Pick|Add|Open|Check|Set|Use|Allow|Turn|Make|Keep|Type|Say|Tell|Describe|Scan|Run|Connect|Sign|Grant|Wait|Retry)\b/i;
  const register = rows.filter((r) => addressing.test(englishText(r.english))).slice(0, 120);
  if (register.length) groups.push({ term: "FORM OF ADDRESS (formal vs informal you, imperatives)", strings: register });
  const prompt = `You are a native ${language.name} (${language.ios}) localization reviewer for OpenCode Mobile, a phone app that remotely controls OpenCode, an AI coding agent. Several translators worked on this app, so the same English term may have been translated with different ${language.name} words.

For each term group below, decide the one ${language.name} word or phrase a native ${language.name} app would use for that term. The current strings may follow the OpenCode desktop app's word lists, which contain some mistakes (an English word transliterated instead of translated, a calque, a word in the wrong sense); do not keep a word just because it is used often, choose the correct one (as noun, verb or adjective as the English needs, inflected correctly), then fix every string in the group that uses a different word for the same meaning. Keep a different word only where the English uses the term in a different sense. Also fix any grammar or wording you break or notice in those strings. Do not restyle otherwise.

The last group, "FORM OF ADDRESS", holds strings that speak to the user. Choose the one form of address (formal or informal "you", and the matching imperative form) that ${language.name} developer apps normally use, and rewrite every string in that group that uses the other form. Report the choice in "terms" under "form of address".

Product terms (what they mean in this app):
${Object.entries(glossary.terms).map(([t, d]) => `- ${t}: ${d}`).join("\n")}

Keep placeholders {1}, {2}, … exactly. Keep these as written: ${glossary.keep.join(", ")}. Plural items (objects) need the same forms they have now: ${language.plurals.join(", ")}.

Answer with one JSON object only: {"terms": {"<term>": "<chosen ${language.name} word>"}, "fixes": {"<id>": "<corrected text>" or {"<form>": "<text>"}}, "reasons": {"<id>": "<few words>"}}. Only include ids you changed in fixes. No prose, no code fences.

Term groups (JSON):
${JSON.stringify(groups, null, 1)}`;
  const folder = join(dir, language.ios);
  mkdirSync(folder, { recursive: true });
  const base = join(folder, "consistency-001");
  writeFileSync(`${base}.prompt.md`, `${prompt}\n\nWrite only the JSON object to ${base}.answer.json (UTF-8, no code fences). Make sure it parses as JSON.\n`);
  writeReviewTask(dir, base, language, groups.flatMap((g) => g.strings));
  console.log(`${language.ios}: ${groups.length} term groups, ${groups.reduce((n, g) => n + g.strings.length, 0)} strings`);
}
