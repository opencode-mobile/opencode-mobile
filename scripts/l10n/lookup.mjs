#!/usr/bin/env node
// Context for one string, for an agent translating or reviewing it. It pulls what it needs
// rather than getting everything in its task.
//
// Usage:
//   node scripts/l10n/lookup.mjs <id>[,<id>…] [language[,language…]]
//       for each string: its English and notes, the code that uses it, the strings near it and,
//       per language, its current translation, form of address and words for the string's terms
//   node scripts/l10n/lookup.mjs --find "<English words>" <language>[,language…]
//       how the app already translates a word or phrase (up to 12 strings)
//
// Ask for every string and language you need in one call: each call costs an agent a turn.

import { resolveLanguages } from "./languages.mjs";
import { loadUnits, textsOf, readCatalog, readAndroidNeutral, readIosNeutral, readLock } from "./strings.mjs";
import { usageIndex } from "./usage.mjs";
import { englishText, formOfAddress, glossaryFor, termsFor } from "./terms.mjs";

const args = process.argv.slice(2);
if (!args.length || args[0] === "--help") {
  console.log('Usage: node scripts/l10n/lookup.mjs <id>[,<id>…] [language[,language…]]\n       node scripts/l10n/lookup.mjs --find "<English words>" <language>[,language…]');
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

const list = (arg) => (arg ? arg.split(",").map((a) => a.trim()).filter(Boolean) : []);

if (args[0] === "--find") {
  const [, phrase, codes] = args;
  if (!phrase || !codes) throw new Error('--find needs "<English words>" and a language');
  const languages = resolveLanguages(list(codes));
  const readers = languages.map((l) => [l, reader(l)]);
  const needle = phrase.toLowerCase();
  const rows = [];
  for (const unit of units) {
    for (const text of textsOf(unit)) {
      if (!englishText(text.english).toLowerCase().includes(needle)) continue;
      rows.push(`${text.id}: ${show(text.english)} → ${readers.map(([l, current]) => `${l.ios} ${show(current(unit, text))}`).join(" · ")}`);
    }
  }
  console.log(`${rows.length} strings use "${phrase}"${rows.length > 12 ? " (first 12)" : ""}:`);
  for (const row of rows.slice(0, 12)) console.log(`  ${row}`);
} else {
  const languages = list(args[1]).length ? resolveLanguages(list(args[1])) : [];
  const readers = languages.map((l) => [l, reader(l)]);
  const lock = readLock();
  const usage = usageIndex(units, 3);
  const blocks = [];
  for (const id of list(args[0])) {
    const key = id.split("#")[0];
    const index = units.findIndex((u) => u.key === key || u.android?.name === key);
    if (index < 0) throw new Error(`${id}: no such string`);
    const unit = units[index];
    // A translation made from older English, or one being redone, shouldn't anchor the new one.
    const stale = lock[unit.key] && lock[unit.key] !== unit.hash ? ", made from older English" : "";
    const lines = [`== ${unit.key}${unit.plural ? " (plural)" : ""}`];
    for (const text of textsOf(unit)) {
      const platform = text.platform === "both" ? "" : ` (${text.platform} only)`;
      lines.push(`English${platform}: ${show(text.english)}`);
      for (const [l, current] of readers) lines.push(`  ${l.ios} current${stale}: ${show(current(unit, text))}`);
    }
    if (unit.notes.length) lines.push(`Note: ${[...new Set(unit.notes)].join(" / ")}`);
    const uses = usage.get(unit.key) ?? [];
    if (uses.length) lines.push(`Used in:\n${uses.map((u) => `  ${u}`).join("\n")}`);
    // Strings next to it in the file with the same key prefix; shared common.* keys have no
    // screen of their own.
    const neighbours = area(unit.key) === "common" ? [] : units.slice(Math.max(0, index - 3), index + 4).filter((u) => u !== unit && area(u.key) === area(unit.key));
    if (neighbours.length) {
      lines.push("Nearby strings:");
      for (const n of neighbours) {
        for (const text of textsOf(n)) lines.push(`  ${text.id}: ${show(text.english)}${readers.map(([l, current]) => ` · ${l.ios} ${show(current(n, text))}`).join("")}`);
      }
    }
    const english = textsOf(unit).map((t) => englishText(t.english)).join("\n");
    for (const language of languages) {
      const { chosen, desktop } = termsFor(language, english);
      const parts = [];
      if (chosen.length) parts.push(`words ${chosen.map(([t, w]) => `${t} → ${w}`).join("; ")}`);
      if (desktop.length) parts.push(`desktop app words ${desktop.map(([t, w]) => `${t} → ${w}`).join("; ")}`);
      if (unit.plural) parts.push(`plural forms ${language.plurals.join(", ")}`);
      if (parts.length) lines.push(`${language.ios}: ${parts.join(" | ")}`);
    }
    const product = glossaryFor(english);
    if (product.length) lines.push(`Product terms:\n${product.map(([t, d]) => `  ${t}: ${d}`).join("\n")}`);
    blocks.push(lines.join("\n"));
  }
  for (const language of languages) if (formOfAddress(language)) blocks.push(`${language.ios} form of address: ${formOfAddress(language)}`);
  console.log(blocks.join("\n\n"));
}
