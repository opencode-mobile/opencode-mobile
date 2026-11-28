#!/usr/bin/env node
// Checks OpenCode Mobile's translations on both platforms (docs/translating.md).
//
// Usage: node scripts/check-translations.mjs [codes…] [--warnings]
//
// For every language in scripts/l10n/languages.mjs it reads
// android/app/src/main/res/values-<code>/strings.xml and the iOS Localizable.xcstrings, and
// fails (exit 1) when a language:
//   - is missing a key, or has a key that's gone from English or isn't translatable
//   - has placeholders that differ from the English, or a plural form missing or extra
//   - lost "OpenCode" or text in backticks, or has a stray brace
//   - has Android text that aapt would reject (an unescaped apostrophe or quote, a bare &)
//   - has a translation made from older English: l10n/source-hashes.json records the English
//     each key was translated from, so a changed meaning gets translated again
// and when the language lists in android/app/build.gradle.kts (appLanguages) and the Xcode
// project (knownRegions) differ from languages.mjs. Text still in English, in the wrong script
// or much longer than the English is a warning (listed with --warnings). Each failure prints
// the command that fixes it.

import { readFileSync, existsSync } from "node:fs";
import { join } from "node:path";
import { LANGUAGES, resolveLanguages } from "./l10n/languages.mjs";
import {
  ROOT,
  loadUnits,
  textsOf,
  readCatalog,
  readAndroid,
  readAndroidNeutral,
  readIosNeutral,
  androidPath,
  readLock,
  parseAndroid,
  ANDROID_ENGLISH,
  IOS_INFOPLIST_CATALOG,
  readInfoPlistStrings,
  infoPlistHash,
} from "./l10n/strings.mjs";
import { problemsOf } from "./l10n/rules.mjs";

const args = process.argv.slice(2);
const showWarnings = args.includes("--warnings");
const languages = resolveLanguages(args.filter((a) => !a.startsWith("--")));

const units = loadUnits();
const catalog = readCatalog();
const lock = readLock();
const untranslatable = new Set(parseAndroid(readFileSync(ANDROID_ENGLISH, "utf8")).filter((e) => !e.translatable).map((e) => e.name));
const englishNames = new Set(units.filter((u) => u.android).map((u) => u.android.name));

const configErrors = [];
checkConfig();
const infoPlistEnglish = readInfoPlistStrings();
const infoPlist = existsSync(IOS_INFOPLIST_CATALOG) ? readCatalog(IOS_INFOPLIST_CATALOG) : null;
if (!infoPlist) configErrors.push(`${rel(IOS_INFOPLIST_CATALOG)} is missing; node scripts/translate.mjs all creates it`);
for (const [key, value] of Object.entries(infoPlistEnglish)) {
  if (infoPlist && infoPlist.strings[key]?.localizations?.en?.stringUnit?.value !== value) {
    configErrors.push(`InfoPlist.xcstrings English for ${key} differs from the Info.plist; run node scripts/translate.mjs all`);
  }
}

const stale = [
  ...units.filter((u) => lock[u.key] !== u.hash),
  ...Object.entries(infoPlistEnglish).filter(([k, v]) => lock[`InfoPlist:${k}`] !== infoPlistHash(v)).map(([k]) => ({ key: `InfoPlist:${k}` })),
];
const reports = [];
for (const language of languages) reports.push(checkLanguage(language));

let failed = configErrors.length > 0;
for (const e of configErrors) console.log(`config: ${e}`);
let totalWarnings = 0;
for (const r of reports) {
  totalWarnings += r.warnings.length;
  if (r.errors.length) {
    failed = true;
    console.log(`\n${r.language.ios} (${r.language.name}): ${r.errors.length} problems`);
    for (const e of r.errors.slice(0, 25)) console.log(`  ${e}`);
    if (r.errors.length > 25) console.log(`  … and ${r.errors.length - 25} more`);
  }
  if (showWarnings && r.warnings.length) {
    console.log(`\n${r.language.ios}: ${r.warnings.length} warnings`);
    for (const w of r.warnings) console.log(`  ${w}`);
  }
}
if (stale.length) {
  failed = true;
  console.log(`\n${stale.length} keys have English that changed since they were translated (or were never translated):`);
  for (const u of stale.slice(0, 20)) console.log(`  ${u.key}`);
  if (stale.length > 20) console.log(`  … and ${stale.length - 20} more`);
}

if (failed) {
  console.log(`
To fix:
  node scripts/translate.mjs all              translates missing and changed strings in every language
  node scripts/translate.mjs --accept <key>   keeps a key's translations after an English typo fix
Without model access, leave the strings for a maintainer and say so in the pull request.`);
  process.exit(1);
}
const total = units.reduce((n, u) => n + textsOf(u).length, 0) + Object.keys(infoPlistEnglish).length;
console.log(`Translations OK: ${languages.length} languages × ${total} strings on Android and iOS, ${totalWarnings} warnings${showWarnings ? "" : " (--warnings lists them)"}.`);

// ---------------------------------------------------------------------------------------------

function checkLanguage(language) {
  const errors = [];
  const warnings = [];
  const path = androidPath(language);
  if (!existsSync(path)) {
    return { language, errors: [`${rel(path)} is missing`], warnings };
  }
  checkAndroidEscaping(path, errors);
  const android = readAndroidNeutral(language);
  const androidEntries = readAndroid(path);
  for (const name of androidEntries.keys()) {
    if (untranslatable.has(name)) errors.push(`${name}: marked translatable="false" in English; remove it`);
    else if (!englishNames.has(name)) errors.push(`${name}: gone from English; remove it (translate.mjs does)`);
  }
  for (const unit of units) {
    for (const text of textsOf(unit)) {
      if (text.platform !== "ios" && unit.android) {
        const value = android.get(unit.android.name);
        if (value === undefined) errors.push(`${unit.android.name}: missing on Android`);
        else report(`${unit.android.name} (Android)`, text.english, value, unit.plural);
      }
      if (text.platform !== "android" && unit.ios) {
        const value = readIosNeutral(catalog, unit, language);
        if (!value) errors.push(`${unit.ios.key}: missing on iOS`);
        else {
          if (value.state !== "translated") warnings.push(`${unit.ios.key}: iOS state is ${value.state}`);
          report(`${unit.ios.key} (iOS)`, text.english, value.text, unit.plural);
        }
      }
    }
  }
  for (const [key, english] of Object.entries(infoPlistEnglish)) {
    const value = infoPlist?.strings[key]?.localizations?.[language.ios]?.stringUnit?.value;
    if (!value) errors.push(`${key}: missing in InfoPlist.xcstrings`);
    else report(`${key} (InfoPlist)`, english, value, false);
  }
  return { language, errors, warnings };

  function report(label, english, value, plural) {
    const p = problemsOf(english, value, language, plural);
    for (const e of p.errors) errors.push(`${label}: ${e}`);
    for (const w of p.warnings) warnings.push(`${label}: ${w}`);
  }
}

// aapt rejects an apostrophe or double quote that isn't escaped, and XML a bare ampersand.
function checkAndroidEscaping(path, errors) {
  const xml = readFileSync(path, "utf8");
  for (const m of xml.matchAll(/<(string|item)\b[^>]*>([\s\S]*?)<\/\1>/g)) {
    const text = m[2];
    const name = m[0].match(/name="([^"]+)"/)?.[1] ?? "item";
    if (/(^|[^\\])['"]/.test(text) && !(text.startsWith('"') && text.endsWith('"'))) errors.push(`${name}: unescaped ' or " in ${rel(path)}`);
    if (/&(?!amp;|lt;|gt;|quot;|apos;|#\d+;|#x[0-9a-fA-F]+;)/.test(text)) errors.push(`${name}: bare & in ${rel(path)}`);
    if (/<(?!\/)/.test(text)) errors.push(`${name}: markup inside a string in ${rel(path)}`);
  }
}

function checkConfig() {
  const gradle = readFileSync(join(ROOT, "android/app/build.gradle.kts"), "utf8");
  const listed = gradle.match(/val appLanguages =\s*listOf\(([\s\S]*?)\)/)?.[1];
  const android = new Set((listed ?? "").match(/"([^"]+)"/g)?.map((s) => s.slice(1, -1)) ?? []);
  const expectedAndroid = new Set(["en", ...LANGUAGES.map((l) => l.android)]);
  diff("android/app/build.gradle.kts appLanguages", android, expectedAndroid);

  const pbx = readFileSync(join(ROOT, "ios/OpenCode Mobile/OpenCode Mobile.xcodeproj/project.pbxproj"), "utf8");
  const regions = pbx.match(/knownRegions = \(([\s\S]*?)\);/)?.[1] ?? "";
  const ios = new Set([...regions.matchAll(/"?([A-Za-z0-9-]+)"?,/g)].map((m) => m[1]).filter((c) => c !== "Base"));
  const expectedIos = new Set(["en", ...LANGUAGES.map((l) => l.ios)]);
  diff("Xcode project knownRegions", ios, expectedIos);

  function diff(label, actual, expected) {
    const missing = [...expected].filter((c) => !actual.has(c));
    const extra = [...actual].filter((c) => !expected.has(c));
    if (missing.length) configErrors.push(`${label} is missing ${missing.join(", ")} (scripts/l10n/languages.mjs lists them)`);
    if (extra.length) configErrors.push(`${label} has ${extra.join(", ")}, which scripts/l10n/languages.mjs doesn't list`);
  }
}

function rel(path) {
  return path.slice(ROOT.length + 1);
}
