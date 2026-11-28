#!/usr/bin/env node
// Writes a term-consistency review request per language, for another agent to answer.
//
// Usage: node scripts/l10n/consistency.mjs <dir> [codes… | all]
//
// For each product term (TERMS below and the glossary), it lists the English strings that use it
// with their current translation, so a reviewer can pick one word per term and fix the strings
// that use another. It writes <dir>/<ios code>/consistency-001.prompt.md; the answer goes next to
// it as consistency-001.answer.json ({"fixes": {...}, "reasons": {...}}) and is applied with
// `node scripts/translate.mjs <codes> --review --answers <dir>`.

import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { resolveLanguages } from "./languages.mjs";
import { ROOT, loadUnits, textsOf, readCatalog, readAndroidNeutral, readIosNeutral } from "./strings.mjs";

const TERMS = [
  "session", "server", "project", "agent", "model", "variant", "provider", "archive", "worktree",
  "main checkout", "branch", "permission", "question", "attachment", "pair", "pairing", "compact",
  "fork", "revert", "undo", "retry", "dismiss", "queue", "steer", "home", "uptime", "reconnect",
  "hunk", "uncommitted", "diff", "changes", "shell", "terminal", "command", "subagent", "todo",
  "skill", "prompt", "context", "token", "usage", "connection profile", "app lock", "dictation",
  "server health", "side question", "notification", "theme", "appearance",
];
const PER_TERM = 30;

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
  const prompt = `You are a native ${language.name} (${language.ios}) localization reviewer for OpenCode Mobile, a phone app that remotely controls OpenCode, an AI coding agent. Several translators worked on this app, so the same English term may have been translated with different ${language.name} words.

For each term group below, decide the one ${language.name} word or phrase this app should use for that term (as noun, verb or adjective as the English needs, inflected correctly), then fix every string in the group that uses a different word for the same meaning. Keep a different word only where the English uses the term in a different sense. Also fix any grammar or wording you break or notice in those strings. Do not restyle otherwise.

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
  console.log(`${language.ios}: ${groups.length} term groups, ${groups.reduce((n, g) => n + g.strings.length, 0)} strings`);
}
