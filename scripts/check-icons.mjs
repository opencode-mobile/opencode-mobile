#!/usr/bin/env node
import { readFileSync, existsSync } from "node:fs";
import { join } from "node:path";
import { loadManifest, REPO_ROOT } from "./lib/utils.mjs";

const MANIFEST_HASH_FILE = join(REPO_ROOT, "icons/.manifest-hash");
const ANDROID_OUTPUT = join(
  REPO_ROOT,
  "android/app/src/main/java/dev/opencode/mobile/ui/icons/OpenCodeIcons.kt",
);
const IOS_ENUM_OUTPUT = join(REPO_ROOT, "ios/OpenCode Mobile/OpenCode Mobile/Icons/OpenCodeIcon.swift");

function expectedHash(icons) {
  return icons.map((i) => `${i.source}/${i.lucide}/${i.name}:${i.iosAssetName}`).join("\n") + "\n";
}

function main() {
  const icons = loadManifest();
  const expected = expectedHash(icons);

  if (!existsSync(MANIFEST_HASH_FILE)) {
    console.error("icons/.manifest-hash missing — run: npm run icons:sync");
    process.exit(1);
  }

  const actual = readFileSync(MANIFEST_HASH_FILE, "utf8");
  if (actual !== expected) {
    console.error("Icon manifest changed — run: npm run icons:sync");
    process.exit(1);
  }

  for (const file of [ANDROID_OUTPUT, IOS_ENUM_OUTPUT]) {
    if (!existsSync(file)) {
      console.error(`Missing generated file: ${file}`);
      process.exit(1);
    }
  }

  for (const icon of icons) {
    const imageset = join(
      REPO_ROOT,
      "ios/OpenCode Mobile/OpenCode Mobile/LucideIcons.xcassets",
      `${icon.iosAssetName}.imageset/icon.svg`,
    );
    if (!existsSync(imageset)) {
      console.error(`Missing iOS asset: ${imageset}`);
      process.exit(1);
    }
  }

  console.log("Icon generated outputs match manifest.");
}

main();
