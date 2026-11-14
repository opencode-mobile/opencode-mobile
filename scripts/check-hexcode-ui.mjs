#!/usr/bin/env node
// Hex Code has its own UI kit and shares only data and domain code with OpenCode Mobile. This fails when:
//   - Hex Code Android imports OpenCode Mobile's theme or UI packages (dev.opencode.mobile.theme, dev.opencode.mobile.ui.*),
//     other than the few non-UI helpers that live in :opencode-kit under a ui package (ALLOWED_ANDROID_IMPORTS);
//   - :opencode-kit gains Compose code, theme presets or UI string resources;
//   - Hex Code iOS names a type declared in OpenCode Mobile's UI (its Theme, Components, Icons and UI folders, or
//     OpenCodeKit's Theme, Components and Icons folders while they exist).
//
// Usage: node scripts/check-hexcode-ui.mjs

import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { join, relative } from "node:path";
import { REPO_ROOT } from "./lib/utils.mjs";

const HEX_ANDROID = join(REPO_ROOT, "android/hexcode/src");
const KIT_ANDROID = join(REPO_ROOT, "android/opencode-kit/src");
const HEX_IOS = join(REPO_ROOT, "ios/OpenCode Mobile/Hex Code");
const MOBILE_IOS_UI = ["Theme", "Components", "Icons", "UI", "ComponentGallery"].map((dir) =>
  join(REPO_ROOT, "ios/OpenCode Mobile/OpenCode Mobile", dir),
);
const KIT_IOS_UI = ["Theme", "Components", "Icons"].map((dir) => join(REPO_ROOT, "ios/OpenCodeKit/Sources/OpenCodeKit", dir));

/** Kit classes in a `ui` package that are not UI: kept there so OpenCode Mobile's files match `main`. */
const ALLOWED_ANDROID_IMPORTS = new Set([
  "dev.opencode.mobile.ui.connection.PairingQRDecoder", // ZXing decode of a camera frame; no Compose
]);

function files(dir, extension) {
  if (!existsSync(dir)) return [];
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) return name === "build" ? [] : files(path, extension);
    return name.endsWith(extension) ? [path] : [];
  });
}

const problems = [];
const rel = (path) => relative(REPO_ROOT, path);

for (const file of files(HEX_ANDROID, ".kt")) {
  for (const [, name] of readFileSync(file, "utf8").matchAll(/^import\s+(dev\.opencode\.mobile\.(?:theme|ui)\.[\w.]+)/gm)) {
    if (!ALLOWED_ANDROID_IMPORTS.has(name)) problems.push(`${rel(file)} imports OpenCode Mobile UI: ${name}`);
  }
}

for (const file of files(KIT_ANDROID, ".kt")) {
  if (/^import\s+androidx\.compose\./m.test(readFileSync(file, "utf8"))) {
    problems.push(`${rel(file)} uses Compose; :opencode-kit holds no UI`);
  }
}
for (const dir of ["main/assets/theme-presets", "main/assets/syntax-presets", "main/res/values"]) {
  if (existsSync(join(KIT_ANDROID, dir))) problems.push(`${rel(join(KIT_ANDROID, dir))} exists; UI resources belong to each app`);
}

/** Type names a Swift file declares at the top level (nested ones such as `Content` are too generic to look for). */
function swiftTypes(source) {
  const declaration = /^(?:@\w+(?:\([^)]*\))?\s+)*(?:(?:public|internal|fileprivate|private|final|indirect)\s+)*(?:struct|class|enum|protocol|actor|typealias)\s+([A-Z]\w*)/gm;
  return [...source.matchAll(declaration)].map((m) => m[1]);
}

const hexSwift = files(HEX_IOS, ".swift");
const hexOwn = new Set(hexSwift.flatMap((file) => swiftTypes(readFileSync(file, "utf8"))));
const mobileUi = new Map();
for (const file of [...MOBILE_IOS_UI, ...KIT_IOS_UI].flatMap((dir) => files(dir, ".swift"))) {
  for (const name of swiftTypes(readFileSync(file, "utf8"))) {
    if (!hexOwn.has(name) && !mobileUi.has(name)) mobileUi.set(name, file);
  }
}
for (const file of hexSwift) {
  const source = readFileSync(file, "utf8").replace(/\/\/.*$/gm, "");
  const used = new Set([...source.matchAll(/\b[A-Z]\w*\b/g)].map((m) => m[0]).filter((name) => mobileUi.has(name)));
  for (const name of [...used].sort()) problems.push(`${rel(file)} uses OpenCode Mobile UI: ${name} (${rel(mobileUi.get(name))})`);
}

if (problems.length > 0) {
  console.error(`Hex Code UI check failed (${problems.length}):`);
  for (const p of problems) console.error(`  - ${p}`);
  process.exit(1);
}
console.log("Hex Code UI check passed: no OpenCode Mobile UI in Hex Code, no UI in OpenCode Kit.");
