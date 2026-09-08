#!/usr/bin/env node
/**
 * Copy bundled OpenCode Mobile theme presets from shared/ into both app bundles.
 */
import { copyFileSync, mkdirSync, readdirSync, readFileSync, unlinkSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { REPO_ROOT } from "./lib/utils.mjs";

const SHARED_DIR = join(REPO_ROOT, "shared/theme-presets");
const IOS_DIR = join(REPO_ROOT, "ios/OpenCode Mobile/OpenCode Mobile/Resources/ThemePresets");
const ANDROID_DIR = join(REPO_ROOT, "android/app/src/main/assets/theme-presets");
const IOS_FALLBACK = join(
  REPO_ROOT,
  "ios/OpenCode Mobile/OpenCode Mobile/Assets.xcassets/ThemeJson.dataset",
);
const DEFAULT_PRESET_ID = "graphite";

function jsonFiles(dir) {
  return readdirSync(dir).filter((name) => name.endsWith(".json"));
}

function clearJson(dir) {
  mkdirSync(dir, { recursive: true });
  for (const name of jsonFiles(dir)) {
    unlinkSync(join(dir, name));
  }
}

function copyBundle(destDir) {
  clearJson(destDir);
  for (const name of jsonFiles(SHARED_DIR)) {
    copyFileSync(join(SHARED_DIR, name), join(destDir, name));
  }
}

function main() {
  const manifest = JSON.parse(readFileSync(join(SHARED_DIR, "manifest.json"), "utf8"));
  if (!Array.isArray(manifest) || manifest.length === 0) {
    throw new Error("shared/theme-presets/manifest.json is empty or invalid");
  }
  for (const entry of manifest) {
    if (!entry.id || !entry.name) {
      throw new Error("Manifest entry is missing id or name");
    }
    const file = join(SHARED_DIR, `${entry.id}.json`);
    const theme = JSON.parse(readFileSync(file, "utf8"));
    if (theme.id !== entry.id || theme.name !== entry.name) {
      throw new Error(`Theme ${entry.id} does not match its manifest entry`);
    }
  }

  copyBundle(IOS_DIR);
  copyBundle(ANDROID_DIR);

  mkdirSync(IOS_FALLBACK, { recursive: true });
  for (const name of readdirSync(IOS_FALLBACK)) {
    if (name.endsWith(".json") && name !== "Contents.json") {
      unlinkSync(join(IOS_FALLBACK, name));
    }
  }
  copyFileSync(join(SHARED_DIR, `${DEFAULT_PRESET_ID}.json`), join(IOS_FALLBACK, `${DEFAULT_PRESET_ID}.json`));
  writeFileSync(
    join(IOS_FALLBACK, "Contents.json"),
    `${JSON.stringify(
      {
        data: [{ idiom: "universal", filename: `${DEFAULT_PRESET_ID}.json` }],
        info: { author: "xcode", version: 1 },
      },
      null,
      2,
    )}\n`,
  );

  console.log(`Synced ${manifest.length} theme presets`);
}

main();
