#!/usr/bin/env node
// Checks that Android and iOS app text use the same structured keys.
//
// Sources:
//   Android  android/app/src/main/res/values/strings.xml   (<string> and <plurals> names)
//            android/opencode-kit/src/main/res/values/strings.xml   (the shared theme and components)
//   iOS      ios/OpenCode Mobile/OpenCode Mobile/Localizable.xcstrings   (catalog keys)
//
// Key rule:
//   An iOS structured key is lowercase dot-separated segments, for example
//   `servers.remove_dialog.title`. It must match
//     /^[a-z][a-z0-9_]*(\.[a-z0-9_]+)+$/
//   Its Android name is the same key with every `.` replaced by `_`:
//     servers.remove_dialog.title  <=>  servers_remove_dialog_title
//   Catalog keys that are not structured (English text that Xcode extracted from
//   `Text("…")`) are ignored.
//
// Checks (any failure exits 1):
//   - every Android name has an iOS structured key, unless it is in ANDROID_ONLY
//   - every iOS structured key has an Android name, unless it is in IOS_ONLY
//   - no two iOS keys map to the same Android name
//   - a key is a plural on both platforms or on neither
//   - both English values have the same number of placeholders
//     (Android %s %d %1$s …; iOS %@ %lld %d %1$@ …; %% is not a placeholder)
//   - every iOS structured key has an English value, so the UI never shows the raw key
//
// Hex Code (reported, not failing, while its keys settle):
//   Android  android/hexcode/src/main/res/values/strings.xml
//   iOS      ios/OpenCode Mobile/Hex Code/**/*.swift   (LocalizedStringResource("key", defaultValue: …) and
//            String(localized: "key", defaultValue: …); Hex Code keeps its text in code)
//            ios/OpenCode Mobile/Hex Code/Localizable.xcstrings
//   Kit keys from the OpenCode Mobile catalog are left to that pair, unless Hex Code Android uses them too.
//   `--strict-hexcode` makes a Hex Code mismatch fail too.
//
// Usage: node scripts/check-string-keys.mjs [--strict-hexcode]

import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const REPO_ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const ANDROID_STRINGS = [
  join(REPO_ROOT, "android/app/src/main/res/values/strings.xml"),
  join(REPO_ROOT, "android/opencode-kit/src/main/res/values/strings.xml"),
];
const IOS_CATALOG = join(REPO_ROOT, "ios/OpenCode Mobile/OpenCode Mobile/Localizable.xcstrings");
const HEX_ANDROID_STRINGS = [join(REPO_ROOT, "android/hexcode/src/main/res/values/strings.xml")];
const HEX_IOS_SOURCES = join(REPO_ROOT, "ios/OpenCode Mobile/Hex Code");
const HEX_IOS_CATALOG = join(HEX_IOS_SOURCES, "Localizable.xcstrings");

/** Hex Code's Android-only resources. */
const HEX_ANDROID_ONLY = new Set([
  "app_name", // launcher label; iOS uses CFBundleDisplayName
]);

const STRUCTURED_KEY = /^[a-z][a-z0-9_]*(\.[a-z0-9_]+)+$/;

/** Android-only resources: platform plumbing with no iOS screen text. */
const ANDROID_ONLY = new Set([
  "app_name", // launcher label; iOS uses CFBundleDisplayName
]);

/** iOS-only catalog keys, by iOS key. */
const IOS_ONLY = new Set([]);

const ANDROID_PLACEHOLDER = /%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[sdfxXoeEgGc]/g;
const IOS_PLACEHOLDER = /%(?:\d+\$)?[-#+ 0]*\d*(?:\.\d+)?(?:@|lld|llu|ld|lu|d|i|u|f|e|g|x|X|c|s)/g;

function placeholderCount(value, pattern) {
  return (value.replaceAll("%%", "").match(pattern) ?? []).length;
}

function decodeXml(text) {
  return text
    .replace(/<!\[CDATA\[([\s\S]*?)\]\]>/g, "$1")
    .replaceAll("&lt;", "<")
    .replaceAll("&gt;", ">")
    .replaceAll("&quot;", '"')
    .replaceAll("&apos;", "'")
    .replaceAll("&amp;", "&");
}

function readAndroid(files = ANDROID_STRINGS) {
  const xml = files
    .map((file) => readFileSync(file, "utf8"))
    .join("\n")
    .replace(/<!--[\s\S]*?-->/g, "");
  const entries = new Map();
  for (const m of xml.matchAll(/<string\s+[^>]*name="([^"]+)"[^>]*>([\s\S]*?)<\/string>/g)) {
    entries.set(m[1], { plural: false, values: [decodeXml(m[2])] });
  }
  for (const m of xml.matchAll(/<plurals\s+[^>]*name="([^"]+)"[^>]*>([\s\S]*?)<\/plurals>/g)) {
    const values = [...m[2].matchAll(/<item\s+quantity="[^"]+"\s*>([\s\S]*?)<\/item>/g)].map((i) => decodeXml(i[1]));
    entries.set(m[1], { plural: true, values });
  }
  return entries;
}

function readIos(path = IOS_CATALOG) {
  const catalog = JSON.parse(readFileSync(path, "utf8"));
  const sourceLanguage = catalog.sourceLanguage ?? "en";
  const entries = new Map();
  for (const [key, entry] of Object.entries(catalog.strings ?? {})) {
    if (!STRUCTURED_KEY.test(key)) continue;
    const english = entry.localizations?.[sourceLanguage];
    const plural = english?.variations?.plural;
    if (plural) {
      const values = Object.values(plural)
        .map((v) => v.stringUnit?.value)
        .filter((v) => typeof v === "string");
      entries.set(key, { plural: true, values });
    } else {
      const value = english?.stringUnit?.value;
      entries.set(key, { plural: false, values: typeof value === "string" ? [value] : [] });
    }
  }
  return entries;
}

function main() {
  const android = readAndroid();
  const ios = readIos();
  const errors = [];

  const iosByAndroidName = new Map();
  for (const key of ios.keys()) {
    const name = key.replaceAll(".", "_");
    if (iosByAndroidName.has(name)) {
      errors.push(`iOS keys "${iosByAndroidName.get(name)}" and "${key}" both map to Android "${name}"`);
    }
    iosByAndroidName.set(name, key);
  }

  for (const name of android.keys()) {
    if (ANDROID_ONLY.has(name)) continue;
    if (!iosByAndroidName.has(name)) {
      errors.push(`Android "${name}" has no iOS key (expected a dotted form such as "${name.replace("_", ".")}")`);
    }
  }

  for (const [key, entry] of ios) {
    if (entry.values.length === 0) {
      errors.push(`iOS "${key}" has no English value`);
    }
    if (IOS_ONLY.has(key)) continue;
    const name = key.replaceAll(".", "_");
    const other = android.get(name);
    if (!other) {
      errors.push(`iOS "${key}" has no Android resource "${name}"`);
      continue;
    }
    if (entry.plural !== other.plural) {
      errors.push(`"${key}" is a plural on ${entry.plural ? "iOS" : "Android"} only`);
    }
    const iosCounts = new Set(entry.values.map((v) => placeholderCount(v, IOS_PLACEHOLDER)));
    const androidCounts = new Set(other.values.map((v) => placeholderCount(v, ANDROID_PLACEHOLDER)));
    const iosMax = Math.max(0, ...iosCounts);
    const androidMax = Math.max(0, ...androidCounts);
    if (iosMax !== androidMax) {
      errors.push(`"${key}" has ${iosMax} placeholder(s) on iOS and ${androidMax} on Android`);
    }
  }

  const shared = [...ios.keys()].filter((k) => android.has(k.replaceAll(".", "_"))).length;
  if (errors.length > 0) {
    console.error(`String key parity failed (${errors.length}):`);
    for (const e of errors) console.error(`  - ${e}`);
    console.error("\nAdd the key on both platforms (iOS `area.name`, Android `area_name`),");
    console.error("or list a platform-only key in ANDROID_ONLY / IOS_ONLY in scripts/check-string-keys.mjs.");
    process.exit(1);
  }
  console.log(`String keys match: ${shared} shared, ${android.size} Android, ${ios.size} iOS structured.`);
}

/** Swift sources under [dir]. */
function swiftFiles(dir) {
  if (!existsSync(dir)) return [];
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) return swiftFiles(path);
    return name.endsWith(".swift") ? [path] : [];
  });
}

/** Keys Hex Code iOS writes in code, with their default English text; `\(…)` interpolations count as placeholders. */
function readHexIosSources() {
  const entries = new Map();
  const pattern =
    /(?:LocalizedStringResource\(|String\(\s*localized:)\s*"([a-z][a-z0-9_.]*)"\s*,\s*defaultValue:\s*"((?:[^"\\]|\\\([^)]*\)|\\.)*)"/g;
  for (const file of swiftFiles(HEX_IOS_SOURCES)) {
    for (const m of readFileSync(file, "utf8").matchAll(pattern)) {
      if (!STRUCTURED_KEY.test(m[1])) continue;
      const interpolations = (m[2].match(/\\\(/g) ?? []).length;
      entries.set(m[1], { plural: false, values: [m[2]], interpolations });
    }
  }
  return entries;
}

/** Hex Code's pair: Android hexcode resources against the keys Hex Code iOS uses. Reports; fails only when strict. */
function checkHexCode(strict) {
  if (!existsSync(HEX_ANDROID_STRINGS[0])) return;
  const android = readAndroid(HEX_ANDROID_STRINGS);
  const ios = readHexIosSources();
  if (existsSync(HEX_IOS_CATALOG)) {
    for (const [key, entry] of readIos(HEX_IOS_CATALOG)) if (!ios.has(key)) ios.set(key, entry);
  }
  // The shared kit's keys belong to the OpenCode Mobile pair, unless Hex Code Android uses the same one.
  const shared = readIos();
  for (const key of shared.keys()) {
    if (!android.has(key.replaceAll(".", "_"))) ios.delete(key);
  }

  const problems = [];
  for (const name of android.keys()) {
    if (HEX_ANDROID_ONLY.has(name)) continue;
    const key = [...ios.keys()].find((k) => k.replaceAll(".", "_") === name);
    if (!key) problems.push(`Android "${name}" has no Hex Code iOS key`);
  }
  for (const [key, entry] of ios) {
    const name = key.replaceAll(".", "_");
    const other = android.get(name);
    if (!other) {
      problems.push(`iOS "${key}" has no Android resource "${name}"`);
      continue;
    }
    const iosCount = entry.interpolations ?? Math.max(0, ...entry.values.map((v) => placeholderCount(v, IOS_PLACEHOLDER)));
    const androidCount = Math.max(0, ...other.values.map((v) => placeholderCount(v, ANDROID_PLACEHOLDER)));
    if (iosCount !== androidCount) {
      problems.push(`"${key}" has ${iosCount} placeholder(s) on iOS and ${androidCount} on Android`);
    }
  }
  if (problems.length === 0) {
    console.log(`Hex Code string keys match: ${android.size} Android, ${ios.size} iOS.`);
    return;
  }
  const report = strict ? console.error : console.warn;
  report(`Hex Code string key differences (${problems.length}):`);
  for (const p of problems.sort()) report(`  - ${p}`);
  if (strict) process.exit(1);
}

main();
checkHexCode(process.argv.includes("--strict-hexcode"));
