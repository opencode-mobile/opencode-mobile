#!/usr/bin/env node
import { copyFileSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { createRequire } from "node:module";
import { generateAndroidIcons } from "./lib/android-generator.mjs";
import { generateIosAssets, generateIosSwiftEnum } from "./lib/ios-generator.mjs";
import { fetchMaterialSymbolSvg } from "./lib/material-symbols.mjs";
import { loadManifest, REPO_ROOT, svgSourcePath } from "./lib/utils.mjs";

const require = createRequire(import.meta.url);
const svgPackageRoot = dirname(require.resolve("@material-design-icons/svg/package.json"));

const CACHE_DIR = join(REPO_ROOT, "icons/cache/svg");
const ANDROID_OUTPUT = join(
  REPO_ROOT,
  "android/app/src/main/java/dev/opencode/mobile/ui/icons/OpenCodeIcons.kt",
);
const IOS_XCASSETS = join(REPO_ROOT, "ios/OpenCode Mobile/OpenCode Mobile/MaterialIcons.xcassets");
const IOS_ENUM_OUTPUT = join(REPO_ROOT, "ios/OpenCode Mobile/OpenCode Mobile/Icons/OpenCodeIcon.swift");
const MANIFEST_HASH_FILE = join(REPO_ROOT, "icons/.manifest-hash");

async function copyIconsToCache(icons) {
  for (const icon of icons) {
    const destDir = join(CACHE_DIR, icon.style);
    mkdirSync(destDir, { recursive: true });
    const dest = join(destDir, `${icon.name}.svg`);

    if (icon.source === "material_symbols") {
      const svg = await fetchMaterialSymbolSvg(icon.name);
      writeFileSync(dest, svg, "utf8");
      continue;
    }

    const source = svgSourcePath(svgPackageRoot, icon.style, icon.name);
    try {
      copyFileSync(source, dest);
    } catch {
      throw new Error(`Missing Material icon SVG: ${icon.style}/${icon.name}.svg`);
    }
  }
}

function readCachedSvgs(icons) {
  const svgByName = new Map();
  for (const icon of icons) {
    const path = join(CACHE_DIR, icon.style, `${icon.name}.svg`);
    svgByName.set(icon.name, readFileSync(path, "utf8"));
  }
  return svgByName;
}

function writeManifestHash(icons) {
  const payload = icons
    .map((i) => `${i.source}/${i.style}/${i.name}:${i.iosAssetName}`)
    .join("\n");
  writeFileSync(MANIFEST_HASH_FILE, `${payload}\n`, "utf8");
}

async function main() {
  const icons = loadManifest();
  console.log(`Syncing ${icons.length} Material icons...`);

  await copyIconsToCache(icons);
  const svgByName = readCachedSvgs(icons);

  generateAndroidIcons(icons, svgByName, ANDROID_OUTPUT);
  console.log(`Wrote ${ANDROID_OUTPUT}`);

  generateIosAssets(icons, CACHE_DIR, IOS_XCASSETS);
  console.log(`Wrote ${IOS_XCASSETS}`);

  generateIosSwiftEnum(icons, IOS_ENUM_OUTPUT);
  console.log(`Wrote ${IOS_ENUM_OUTPUT}`);

  writeManifestHash(icons);
  console.log("Icon sync complete.");
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
