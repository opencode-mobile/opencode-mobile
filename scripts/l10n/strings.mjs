// Reads and writes OpenCode Mobile's string files on both platforms, in one neutral form.
//
//   Android  android/app/src/main/res/values[-<code>]/strings.xml
//   iOS      ios/OpenCode Mobile/OpenCode Mobile/Localizable.xcstrings
//
// Keys match across platforms: iOS `area.name` is Android `area_name`
// (scripts/check-string-keys.mjs). A unit is one key. Its English is kept per platform,
// because a few keys say the same thing in each platform's own words.
//
// Neutral text is what a translator sees: plain text with each placeholder written as {1},
// {2}, … in English argument order, and a literal percent sign as %. It converts back to
// either platform's syntax, so one translation serves both apps when their English matches.

import { createHash } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

export const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..", "..");
export const ANDROID_RES = join(ROOT, "android/app/src/main/res");
export const ANDROID_ENGLISH = join(ANDROID_RES, "values/strings.xml");
export const IOS_CATALOG = join(ROOT, "ios/OpenCode Mobile/OpenCode Mobile/Localizable.xcstrings");
export const IOS_INFOPLIST_CATALOG = join(ROOT, "ios/OpenCode Mobile/OpenCode Mobile/InfoPlist.xcstrings");
export const IOS_INFOPLIST = join(ROOT, "ios/OpenCode Mobile/OpenCode Mobile/AppTransportSecurity.plist");
export const LOCKFILE = join(ROOT, "l10n/source-hashes.json");

export function androidPath(language) {
  return join(ANDROID_RES, `values-${language.android}`, "strings.xml");
}

export function androidNameFor(iosKey) {
  return iosKey.replaceAll(".", "_");
}

// ---------------------------------------------------------------------------------------------
// Placeholders

const ANDROID_SPEC = /%%|%(\d+)\$([-#+ 0,(]*\d*(?:\.\d+)?[sdfxXoeEgGc])|%([-#+ 0,(]*\d*(?:\.\d+)?[sdfxXoeEgGc])/g;
const IOS_SPEC = /%%|%(\d+)\$([-#+ 0]*\d*(?:\.\d+)?(?:@|lld|llu|ld|lu|d|i|u|f|e|g|x|X|c|s))|%([-#+ 0]*\d*(?:\.\d+)?(?:@|lld|llu|ld|lu|d|i|u|f|e|g|x|X|c|s))/g;

// Converts platform text to neutral text. Returns the text and each argument's conversion,
// by argument number, so the translation can be written back in the same syntax.
function toNeutral(text, pattern) {
  const specs = {};
  let next = 1;
  let positional = false;
  const neutral = text.replace(pattern, (match, index, positionalSpec, plainSpec) => {
    if (match === "%%") return "%";
    if (index) {
      positional = true;
      specs[Number(index)] = positionalSpec;
      return `{${Number(index)}}`;
    }
    const n = next++;
    specs[n] = plainSpec;
    return `{${n}}`;
  });
  return { neutral, specs, positional };
}

// Writes neutral text back in platform syntax. A literal % is doubled only when the string is
// a format string (it has placeholders, or its English already wrote %%), as each platform
// formats only those.
function fromNeutral(neutral, specs, { positional, escapePercent }) {
  const count = Object.keys(specs).length;
  const usePositions = positional || count > 1;
  let text = escapePercent ? neutral.replaceAll("%", "%%") : neutral;
  text = text.replace(/\{(\d+)\}/g, (match, n) => {
    const spec = specs[Number(n)];
    if (!spec) return match;
    return usePositions ? `%${n}$${spec}` : `%${spec}`;
  });
  return text;
}

export function neutralTokens(text) {
  return [...text.matchAll(/\{(\d+)\}/g)].map((m) => Number(m[1])).sort((a, b) => a - b);
}

// ---------------------------------------------------------------------------------------------
// Android XML

function unescapeAndroid(raw) {
  let text = raw
    .replaceAll("&lt;", "<")
    .replaceAll("&gt;", ">")
    .replaceAll("&quot;", '"')
    .replaceAll("&apos;", "'")
    .replace(/&#(\d+);/g, (_, n) => String.fromCodePoint(Number(n)))
    .replace(/&#x([0-9a-fA-F]+);/g, (_, n) => String.fromCodePoint(parseInt(n, 16)))
    .replaceAll("&amp;", "&");
  if (text.length >= 2 && text.startsWith('"') && text.endsWith('"')) text = text.slice(1, -1);
  return text.replace(/\\(u[0-9a-fA-F]{4}|.)/g, (_, c) => {
    if (c === "n") return "\n";
    if (c === "t") return "\t";
    if (c.startsWith("u") && c.length === 5) return String.fromCharCode(parseInt(c.slice(1), 16));
    return c;
  });
}

function escapeAndroid(text) {
  let out = text
    .replaceAll("\\", "\\\\")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll("'", "\\'")
    .replaceAll('"', '\\"')
    .replaceAll("\n", "\\n")
    .replaceAll("\t", "\\t");
  if (out.startsWith("@") || out.startsWith("?")) out = `\\${out}`;
  // Android collapses runs of spaces and trims the ends unless they are escaped.
  out = out.replace(/^ +| +$/g, (spaces) => "\\u0020".repeat(spaces.length));
  out = out.replace(/ {2,}/g, (spaces) => " " + "\\u0020".repeat(spaces.length - 1));
  return out;
}

function attribute(tag, name) {
  return tag.match(new RegExp(`\\b${name}="([^"]*)"`))?.[1];
}

// Parses a strings.xml into entries in file order. Each entry: { name, type: "string" | "plural",
// raw (string text or { quantity: text }), translatable, notes: [comment, …] }.
// The underscore-separated segments two keys start with, with the trailing underscore ("" when
// they share none).
function sharedPrefix(a, b) {
  const x = a.split("_");
  const y = b.split("_");
  let n = 0;
  while (n < x.length - 1 && n < y.length - 1 && x[n] === y[n]) n++;
  return n ? `${x.slice(0, n).join("_")}_` : "";
}

export function parseAndroid(xml) {
  const entries = [];
  const token = /<!--([\s\S]*?)-->|<string\b([^>]*)>([\s\S]*?)<\/string>|<plurals\b([^>]*)>([\s\S]*?)<\/plurals>|\n[ \t]*\n/g;
  // A comment that is a sentence ("%1$s is the file name.") describes the string right after it.
  // A short one without a full stop ("Privacy cover (App Lock)") heads the strings after it
  // whose keys share the prefix of the first two (privacy_cover_), up to a blank line. Headings
  // often stand above several screens' strings with no blank line after them, so a heading
  // rather stops early than lands on strings it doesn't describe.
  let heading = null;
  let headingFirst = null;
  let headingArea = null;
  let note = null;
  for (const m of xml.matchAll(token)) {
    if (m[1] !== undefined) {
      const text = m[1].replace(/\s+/g, " ").trim();
      // Comments about the files themselves (key parity, tooling) aren't context for a string.
      if (/scripts\/|Localizable\.xcstrings|Generated by/.test(text)) continue;
      if (/[.!?:]["')\]]*$/.test(text) || /%\d/.test(text)) note = text;
      else {
        heading = text;
        headingFirst = null;
        headingArea = null;
      }
      continue;
    }
    if (m[0].startsWith("\n")) {
      heading = null;
      note = null;
      continue;
    }
    const name = attribute(m[2] ?? m[4], "name");
    if (heading && headingFirst === null) headingFirst = name;
    else if (heading && headingArea === null) headingArea = sharedPrefix(headingFirst, name);
    if (heading && headingArea !== null && !`${name}_`.startsWith(headingArea)) heading = null;
    const notes = [heading, note].filter(Boolean);
    note = null;
    if (m[2] !== undefined) {
      entries.push({
        name: attribute(m[2], "name"),
        type: "string",
        raw: unescapeAndroid(m[3]),
        translatable: attribute(m[2], "translatable") !== "false",
        notes,
      });
    } else {
      const items = {};
      for (const item of m[5].matchAll(/<item\b([^>]*)>([\s\S]*?)<\/item>/g)) {
        items[attribute(item[1], "quantity")] = unescapeAndroid(item[2]);
      }
      entries.push({
        name: attribute(m[4], "name"),
        type: "plural",
        raw: items,
        translatable: attribute(m[4], "translatable") !== "false",
        notes,
      });
    }
  }
  return entries;
}

export function readAndroid(path) {
  if (!existsSync(path)) return new Map();
  return new Map(parseAndroid(readFileSync(path, "utf8")).map((e) => [e.name, e]));
}

// ---------------------------------------------------------------------------------------------
// iOS String Catalog

export function readCatalog(path = IOS_CATALOG) {
  return JSON.parse(readFileSync(path, "utf8"));
}

// Xcode writes catalogs as 2-space JSON with " : " between keys and values, keys sorted.
export function writeCatalog(catalog, path = IOS_CATALOG) {
  const sorted = { ...catalog, strings: {} };
  for (const key of Object.keys(catalog.strings).sort()) {
    const entry = { ...catalog.strings[key] };
    if (entry.localizations) {
      const locs = {};
      for (const code of Object.keys(entry.localizations).sort()) locs[code] = entry.localizations[code];
      entry.localizations = locs;
    }
    sorted.strings[key] = entry;
  }
  const json = JSON.stringify(sorted, null, 2).replace(/^(\s*"(?:[^"\\]|\\.)*"): /gm, "$1 : ");
  writeFileSync(path, `${json}\n`);
}

function iosRaw(localization) {
  if (!localization) return null;
  if (localization.stringUnit) return localization.stringUnit.value;
  const plural = localization.variations?.plural;
  if (plural) {
    const items = {};
    for (const [quantity, value] of Object.entries(plural)) items[quantity] = value.stringUnit.value;
    return items;
  }
  return null;
}

function iosState(localization) {
  if (!localization) return null;
  if (localization.stringUnit) return localization.stringUnit.state;
  const plural = localization.variations?.plural;
  if (!plural) return null;
  const states = Object.values(plural).map((v) => v.stringUnit.state);
  return states.every((s) => s === states[0]) ? states[0] : "needs_review";
}

// ---------------------------------------------------------------------------------------------
// Units

function neutralize(raw, pattern) {
  if (typeof raw === "string") return toNeutral(raw, pattern);
  const forms = {};
  let specs = {};
  let positional = false;
  for (const [quantity, text] of Object.entries(raw)) {
    const result = toNeutral(text, pattern);
    forms[quantity] = result.neutral;
    specs = { ...specs, ...result.specs };
    positional ||= result.positional;
  }
  return { neutral: forms, specs, positional };
}

function sameNeutral(a, b) {
  return JSON.stringify(a) === JSON.stringify(b);
}

function hasDoubledPercent(raw) {
  const texts = typeof raw === "string" ? [raw] : Object.values(raw);
  return texts.some((t) => t.includes("%%"));
}

// One entry per key, in Android file order, then iOS-only keys.
// Each unit:
//   key        iOS key (Android-only keys use their Android name)
//   android    { name, english (neutral), specs, positional, percent } or null
//   ios        { key, english (neutral), specs, positional, percent } or null
//   plural     true when the key is a plural
//   shared     true when both platforms have the same neutral English
//   notes      context comments from both files
export function loadUnits() {
  const androidEntries = parseAndroid(readFileSync(ANDROID_ENGLISH, "utf8"));
  const catalog = readCatalog();
  const iosByAndroidName = new Map();
  for (const key of Object.keys(catalog.strings)) iosByAndroidName.set(androidNameFor(key), key);

  const units = [];
  const seen = new Set();
  for (const entry of androidEntries) {
    if (!entry.translatable) {
      seen.add(iosByAndroidName.get(entry.name));
      continue;
    }
    const iosKey = iosByAndroidName.get(entry.name);
    const a = neutralize(entry.raw, ANDROID_SPEC);
    const unit = {
      key: iosKey ?? entry.name,
      plural: entry.type === "plural",
      android: {
        name: entry.name,
        english: a.neutral,
        specs: a.specs,
        positional: a.positional,
        percent: hasDoubledPercent(entry.raw),
      },
      ios: null,
      notes: [...entry.notes],
    };
    if (iosKey) {
      seen.add(iosKey);
      const iosEntry = catalog.strings[iosKey];
      if (iosEntry.shouldTranslate !== false) unit.ios = iosUnit(iosKey, iosEntry);
      if (iosEntry.comment) unit.notes.push(iosEntry.comment);
    }
    unit.shared = !!unit.ios && sameNeutral(unit.android.english, unit.ios.english);
    units.push(unit);
  }
  for (const [key, entry] of Object.entries(catalog.strings)) {
    if (seen.has(key) || entry.shouldTranslate === false) continue;
    const ios = iosUnit(key, entry);
    if (!ios) continue;
    units.push({
      key,
      plural: typeof ios.english !== "string",
      android: null,
      ios,
      shared: false,
      notes: entry.comment ? [entry.comment] : [],
    });
  }
  for (const unit of units) unit.hash = englishHash(unit);
  return units;
}

function iosUnit(key, entry) {
  const raw = iosRaw(entry.localizations?.en);
  if (raw === null) return null;
  const n = neutralize(raw, IOS_SPEC);
  return { key, english: n.neutral, specs: n.specs, positional: n.positional, percent: hasDoubledPercent(raw) };
}

// The texts to translate for a unit: one when both platforms share their English, else one per
// platform. Each: { id, platform: "both" | "android" | "ios", english }.
export function textsOf(unit) {
  if (unit.shared || !unit.ios) return [{ id: unit.key, platform: unit.ios ? "both" : "android", english: unit.android.english }];
  if (!unit.android) return [{ id: unit.key, platform: "ios", english: unit.ios.english }];
  return [
    { id: `${unit.key}#android`, platform: "android", english: unit.android.english },
    { id: `${unit.key}#ios`, platform: "ios", english: unit.ios.english },
  ];
}

export function englishHash(unit) {
  const source = JSON.stringify([unit.android?.english ?? null, unit.ios?.english ?? null]);
  return createHash("sha256").update(source).digest("hex").slice(0, 12);
}

// ---------------------------------------------------------------------------------------------
// Translations of one language

function toAndroidRaw(unit, neutral) {
  const options = { positional: unit.android.positional, escapePercent: unit.android.percent || Object.keys(unit.android.specs).length > 0 };
  if (typeof neutral === "string") return fromNeutral(neutral, unit.android.specs, options);
  const items = {};
  for (const [quantity, text] of Object.entries(neutral)) items[quantity] = fromNeutral(text, unit.android.specs, options);
  return items;
}

function toIosLocalization(unit, neutral, state) {
  const options = { positional: unit.ios.positional, escapePercent: unit.ios.percent || Object.keys(unit.ios.specs).length > 0 };
  if (typeof neutral === "string") return { stringUnit: { state, value: fromNeutral(neutral, unit.ios.specs, options) } };
  const plural = {};
  for (const [quantity, text] of Object.entries(neutral)) {
    plural[quantity] = { stringUnit: { state, value: fromNeutral(text, unit.ios.specs, options) } };
  }
  return { variations: { plural } };
}

// Writes translations into the iOS catalog object (in memory; call writeCatalog to save).
export function setIosTranslation(catalog, unit, language, neutral, state = "translated") {
  const entry = catalog.strings[unit.ios.key];
  entry.localizations ??= {};
  entry.localizations[language.ios] = toIosLocalization(unit, neutral, state);
}

export function removeIosTranslation(catalog, key, language) {
  const entry = catalog.strings[key];
  if (entry?.localizations?.[language.ios]) delete entry.localizations[language.ios];
}

// Rewrites a language's Android file from a map of Android name -> neutral text, in English order.
export function writeAndroid(units, language, translationsByName) {
  const lines = [
    '<?xml version="1.0" encoding="utf-8"?>',
    "<!-- Generated by scripts/translate.mjs from values/strings.xml. Hand edits are kept;",
    "     docs/translating.md explains how to change a string. -->",
    "<resources>",
  ];
  for (const unit of units) {
    if (!unit.android) continue;
    const neutral = translationsByName.get(unit.android.name);
    if (neutral === undefined || neutral === null) continue;
    const raw = toAndroidRaw(unit, neutral);
    if (typeof raw === "string") {
      lines.push(`    <string name="${unit.android.name}">${escapeAndroid(raw)}</string>`);
    } else {
      lines.push(`    <plurals name="${unit.android.name}">`);
      for (const [quantity, text] of Object.entries(raw)) {
        lines.push(`        <item quantity="${quantity}">${escapeAndroid(text)}</item>`);
      }
      lines.push("    </plurals>");
    }
  }
  lines.push("</resources>", "");
  const path = androidPath(language);
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, lines.join("\n"));
}

// Reads a language's Android translations by name, in neutral form.
export function readAndroidNeutral(language) {
  const map = new Map();
  for (const [name, entry] of readAndroid(androidPath(language))) map.set(name, neutralize(entry.raw, ANDROID_SPEC).neutral);
  return map;
}

export function readIosNeutral(catalog, unit, language) {
  const loc = catalog.strings[unit.ios.key]?.localizations?.[language.ios];
  const raw = iosRaw(loc);
  if (raw === null) return null;
  return { text: neutralize(raw, IOS_SPEC).neutral, state: iosState(loc) };
}

// Raw platform text, for checks that look at escaping and format specifiers.
export function readIosRaw(catalog, key, language) {
  return iosRaw(catalog.strings[key]?.localizations?.[language.ios]);
}

// ---------------------------------------------------------------------------------------------
// Lockfile: the English each key's translations were made from.

export function readLock() {
  if (!existsSync(LOCKFILE)) return {};
  return JSON.parse(readFileSync(LOCKFILE, "utf8"));
}

export function writeLock(lock) {
  const sorted = {};
  for (const key of Object.keys(lock).sort()) sorted[key] = lock[key];
  mkdirSync(dirname(LOCKFILE), { recursive: true });
  writeFileSync(LOCKFILE, `${JSON.stringify(sorted, null, 2)}\n`);
}

// ---------------------------------------------------------------------------------------------
// Info.plist permission prompts (iOS only): InfoPlist.xcstrings, keyed by the plist key.

// The plist's *UsageDescription strings, by key, in file order.
export function readInfoPlistStrings() {
  const plist = readFileSync(IOS_INFOPLIST, "utf8");
  const out = {};
  for (const m of plist.matchAll(/<key>(NS\w+UsageDescription)<\/key>\s*<string>([\s\S]*?)<\/string>/g)) {
    out[m[1]] = m[2].replaceAll("&lt;", "<").replaceAll("&gt;", ">").replaceAll("&amp;", "&");
  }
  return out;
}

// InfoPlist.xcstrings with its English kept in step with the plist.
export function readInfoPlistCatalog() {
  const catalog = existsSync(IOS_INFOPLIST_CATALOG) ? readCatalog(IOS_INFOPLIST_CATALOG) : { sourceLanguage: "en", strings: {}, version: "1.0" };
  const english = readInfoPlistStrings();
  for (const key of Object.keys(catalog.strings)) if (!(key in english)) delete catalog.strings[key];
  for (const [key, value] of Object.entries(english)) {
    const entry = (catalog.strings[key] ??= {});
    entry.comment = "Permission prompt iOS shows the first time the app asks for this access.";
    entry.extractionState = "manual";
    entry.localizations ??= {};
    entry.localizations.en = { stringUnit: { state: "translated", value } };
  }
  return { catalog, english };
}

export function infoPlistHash(english) {
  return createHash("sha256").update(english).digest("hex").slice(0, 12);
}
