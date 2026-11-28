#!/usr/bin/env node
// Context for one string, for an agent translating or reviewing it. It pulls what it needs
// rather than getting everything in its task.
//
// Usage:
//   node scripts/l10n/lookup.mjs <id> [language]
//       the string's English and notes, the code that uses it, the strings next to it on the
//       same screen and, with a language, their translations, its form of address and its
//       words for the terms the string uses
//   node scripts/l10n/lookup.mjs --find "<English words>" <language>
//       how the app already translates a word or phrase (up to 12 strings)

import { resolveLanguages } from "./languages.mjs";
import { loadUnits, textsOf, readCatalog, readAndroidNeutral, readIosNeutral } from "./strings.mjs";
import { usageIndex } from "./usage.mjs";
import { englishText, formOfAddress, glossaryFor, termsFor } from "./terms.mjs";

const args = process.argv.slice(2);
if (!args.length || args[0] === "--help") {
  console.log("Usage: node scripts/l10n/lookup.mjs <id> [language]\n       node scripts/l10n/lookup.mjs --find \"<English words>\" <language>");
  process.exit(args.length ? 0 : 2);
}
const units = loadUnits();
const catalog = readCatalog();
const show = (value) => JSON.stringify(value);
const area = (key) => key.split(/[._]/)[0];

// The current translation of a text in a language, or null.
function reader(language) {
  const android = readAndroidNeutral(language);
  return (unit, text) => {
    const a = text.platform !== "ios" && unit.android ? android.get(unit.android.name) : undefined;
    return a ?? (text.platform !== "android" && unit.ios ? readIosNeutral(catalog, unit, language)?.text : undefined) ?? null;
  };
}

if (args[0] === "--find") {
  const [, phrase, code] = args;
  if (!phrase || !code) throw new Error('--find needs "<English words>" and a language');
  const [language] = resolveLanguages([code]);
  const current = reader(language);
  const needle = phrase.toLowerCase();
  const rows = [];
  for (const unit of units) {
    for (const text of textsOf(unit)) {
      if (englishText(text.english).toLowerCase().includes(needle)) rows.push(`${text.id}: ${show(text.english)} → ${show(current(unit, text))}`);
    }
  }
  console.log(`${rows.length} strings use "${phrase}"${rows.length > 12 ? " (first 12)" : ""}:`);
  for (const row of rows.slice(0, 12)) console.log(`  ${row}`);
} else {
  const [id, code] = args;
  const key = id.split("#")[0];
  const index = units.findIndex((u) => u.key === key || u.android?.name === key);
  if (index < 0) throw new Error(`${id}: no such string`);
  const unit = units[index];
  const language = code ? resolveLanguages([code])[0] : null;
  const current = language ? reader(language) : () => undefined;
  const lines = [`${unit.key}${unit.plural ? " (plural)" : ""}`];
  for (const text of textsOf(unit)) {
    const platform = text.platform === "both" ? "" : ` (${text.platform} only)`;
    lines.push(`English${platform}: ${show(text.english)}${language ? `\n${language.name}${platform}: ${show(current(unit, text))}` : ""}`);
  }
  if (unit.notes.length) lines.push(`Note: ${[...new Set(unit.notes)].join(" / ")}`);
  const uses = usageIndex(units, 3).get(unit.key) ?? [];
  if (uses.length) lines.push(`Used in:\n${uses.map((u) => `  ${u}`).join("\n")}`);
  const neighbours = units.slice(Math.max(0, index - 5), index + 6).filter((u) => u !== unit && area(u.key) === area(unit.key));
  if (neighbours.length) {
    lines.push(`Same screen:`);
    for (const n of neighbours) for (const text of textsOf(n)) lines.push(`  ${text.id}: ${show(text.english)}${language ? ` → ${show(current(n, text))}` : ""}`);
  }
  const english = textsOf(unit).map((t) => englishText(t.english)).join("\n");
  if (language) {
    const { chosen, desktop } = termsFor(language, english);
    if (formOfAddress(language)) lines.push(`Form of address: ${formOfAddress(language)}`);
    if (chosen.length) lines.push(`Words: ${chosen.map(([t, w]) => `${t} → ${w}`).join("; ")}`);
    if (desktop.length) lines.push(`Desktop app words: ${desktop.map(([t, w]) => `${t} → ${w}`).join("; ")}`);
    if (unit.plural) lines.push(`Plural forms: ${language.plurals.join(", ")}`);
  }
  const product = glossaryFor(english);
  if (product.length) lines.push(`Product terms:\n${product.map(([t, d]) => `  ${t}: ${d}`).join("\n")}`);
  console.log(lines.join("\n"));
}
