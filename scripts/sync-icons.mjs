#!/usr/bin/env node
import { copyFileSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { createRequire } from "node:module";
import { generateAndroidIcons } from "./lib/android-generator.mjs";
import { generateIosAssets, generateIosSwiftEnum } from "./lib/ios-generator.mjs";
import { loadManifest, lucideSvgSourcePath, REPO_ROOT } from "./lib/utils.mjs";

const require = createRequire(import.meta.url);
const svgPackageRoot = dirname(require.resolve("lucide-static/package.json"));

const CACHE_DIR = join(REPO_ROOT, "icons/cache/lucide");
const ANDROID_OUTPUT = join(
  REPO_ROOT,
  "android/app/src/main/java/dev/opencode/mobile/ui/icons/OpenCodeIcons.kt",
);
const IOS_XCASSETS = join(REPO_ROOT, "ios/OpenCode Mobile/OpenCode Mobile/LucideIcons.xcassets");
const LEGACY_IOS_XCASSETS = join(
  REPO_ROOT,
  "ios/OpenCode Mobile/OpenCode Mobile/MaterialIcons.xcassets",
);
const IOS_ENUM_OUTPUT = join(REPO_ROOT, "ios/OpenCode Mobile/OpenCode Mobile/Icons/OpenCodeIcon.swift");
const MANIFEST_HASH_FILE = join(REPO_ROOT, "icons/.manifest-hash");

function copyIconsToCache(icons) {
  rmSync(CACHE_DIR, { recursive: true, force: true });
  mkdirSync(CACHE_DIR, { recursive: true });
  for (const icon of icons) {
    const dest = join(CACHE_DIR, `${icon.name}.svg`);
    const source = lucideSvgSourcePath(svgPackageRoot, icon.lucide);
    try {
      copyFileSync(source, dest);
    } catch {
      throw new Error(`Missing Lucide icon SVG: ${icon.lucide}.svg (catalog name ${icon.name})`);
    }
  }
}

function readCachedSvgs(icons) {
  const svgByName = new Map();
  for (const icon of icons) {
    const path = join(CACHE_DIR, `${icon.name}.svg`);
    svgByName.set(icon.name, readFileSync(path, "utf8"));
  }
  return svgByName;
}

function writeManifestHash(icons) {
  const payload = icons.map((i) => `${i.source}/${i.lucide}/${i.name}:${i.iosAssetName}`).join("\n");
  writeFileSync(MANIFEST_HASH_FILE, `${payload}\n`, "utf8");
}

function main() {
  const icons = loadManifest();
  console.log(`Syncing ${icons.length} Lucide icons...`);

  copyIconsToCache(icons);
  const svgByName = readCachedSvgs(icons);

  generateAndroidIcons(icons, svgByName, ANDROID_OUTPUT);
  console.log(`Wrote ${ANDROID_OUTPUT}`);

  generateIosAssets(icons, svgByName, IOS_XCASSETS);
  rmSync(LEGACY_IOS_XCASSETS, { recursive: true, force: true });
  console.log(`Wrote ${IOS_XCASSETS}`);

  generateIosSwiftEnum(icons, IOS_ENUM_OUTPUT);
  console.log(`Wrote ${IOS_ENUM_OUTPUT}`);

  writeManifestHash(icons);
  console.log("Icon sync complete.");
}

main();
