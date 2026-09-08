#!/usr/bin/env node
/**
 * Verify shared, iOS, and Android theme preset bundles match.
 */
import { createHash } from "node:crypto";
import { existsSync, readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { REPO_ROOT } from "./lib/utils.mjs";

const SHARED_DIR = join(REPO_ROOT, "shared/theme-presets");
const IOS_DIR = join(REPO_ROOT, "ios/OpenCode Mobile/OpenCode Mobile/Resources/ThemePresets");
const ANDROID_DIR = join(REPO_ROOT, "android/app/src/main/assets/theme-presets");

function sha256(path) {
  return createHash("sha256").update(readFileSync(path)).digest("hex");
}

function requireFile(path, label) {
  if (!existsSync(path)) {
    console.error(`Missing ${label}: ${path}`);
    process.exit(1);
  }
}

function extraFiles(dir, allowed) {
  return readdirSync(dir)
    .filter((name) => name.endsWith(".json") && !allowed.has(name))
    .sort();
}

function main() {
  const manifestPath = join(SHARED_DIR, "manifest.json");
  requireFile(manifestPath, "shared manifest");
  const manifest = JSON.parse(readFileSync(manifestPath, "utf8"));

  if (!Array.isArray(manifest) || manifest.length === 0) {
    console.error("shared/theme-presets/manifest.json is empty or invalid");
    process.exit(1);
  }

  const sharedManifestHash = sha256(manifestPath);
  for (const [label, dir] of [
    ["iOS", IOS_DIR],
    ["Android", ANDROID_DIR],
  ]) {
    const path = join(dir, "manifest.json");
    requireFile(path, `${label} manifest`);
    if (sha256(path) !== sharedManifestHash) {
      console.error(`${label} manifest.json is out of sync — run: npm run themes:sync`);
      process.exit(1);
    }
  }

  for (const entry of manifest) {
    const id = entry.id;
    if (!id) {
      console.error("Manifest entry is missing id");
      process.exit(1);
    }
    const sharedFile = join(SHARED_DIR, `${id}.json`);
    const iosFile = join(IOS_DIR, `${id}.json`);
    const androidFile = join(ANDROID_DIR, `${id}.json`);
    for (const [label, file] of [
      ["shared", sharedFile],
      ["iOS", iosFile],
      ["Android", androidFile],
    ]) {
      requireFile(file, `${label} preset ${id}`);
    }
    const sharedHash = sha256(sharedFile);
    if (sha256(iosFile) !== sharedHash || sha256(androidFile) !== sharedHash) {
      console.error(`Theme preset "${id}" is out of sync — run: npm run themes:sync`);
      process.exit(1);
    }
  }

  const allowed = new Set(["manifest.json", ...manifest.map((entry) => `${entry.id}.json`)]);
  for (const [label, dir] of [
    ["shared", SHARED_DIR],
    ["iOS", IOS_DIR],
    ["Android", ANDROID_DIR],
  ]) {
    const extra = extraFiles(dir, allowed);
    if (extra.length > 0) {
      console.error(`${label} has leftover theme files: ${extra.join(", ")}`);
      process.exit(1);
    }
  }

  console.log(`Theme preset bundles match (${manifest.length} presets).`);
}

main();
