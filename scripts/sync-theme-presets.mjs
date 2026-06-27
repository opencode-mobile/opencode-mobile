#!/usr/bin/env node
/**
 * Sync OC-2 desktop theme presets from anomalyco/opencode into mobile app bundles.
 *
 * Source: packages/ui/src/theme/themes/*.json
 * Pin OPENCODE_THEME_REF to a commit where those files exist.
 */
import { mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { REPO_ROOT } from "./lib/utils.mjs";

const OPENCODE_THEME_REF = "ec3ae17e";
const THEME_IDS = [
  "oc-2",
  "amoled",
  "aura",
  "ayu",
  "carbonfox",
  "catppuccin",
  "catppuccin-frappe",
  "catppuccin-macchiato",
  "cobalt2",
  "cursor",
  "dracula",
  "everforest",
  "flexoki",
  "github",
  "gruvbox",
  "kanagawa",
  "lucent-orng",
  "material",
  "matrix",
  "mercury",
  "monokai",
  "nightowl",
  "nord",
  "one-dark",
  "onedarkpro",
  "opencode",
  "orng",
  "osaka-jade",
  "palenight",
  "rosepine",
  "shadesofpurple",
  "solarized",
  "synthwave84",
  "tokyonight",
  "vercel",
  "vesper",
  "zenburn",
];

const SHARED_DIR = join(REPO_ROOT, "shared/theme-presets");
const IOS_DIR = join(REPO_ROOT, "ios/OpenCode Mobile/OpenCode Mobile/Resources/ThemePresets");
const ANDROID_DIR = join(REPO_ROOT, "android/app/src/main/assets/theme-presets");

async function fetchTheme(id) {
  const url = `https://raw.githubusercontent.com/anomalyco/opencode/${OPENCODE_THEME_REF}/packages/ui/src/theme/themes/${id}.json`;
  const response = await fetch(url);
  if (!response.ok) {
    throw new Error(`Failed to fetch ${id}: ${response.status} ${url}`);
  }
  return response.text();
}

function writeTo(destDir, id, jsonText) {
  writeFileSync(join(destDir, `${id}.json`), jsonText, "utf8");
}

async function main() {
  mkdirSync(SHARED_DIR, { recursive: true });
  mkdirSync(IOS_DIR, { recursive: true });
  mkdirSync(ANDROID_DIR, { recursive: true });

  const manifest = [];

  for (const id of THEME_IDS) {
    const jsonText = await fetchTheme(id);
    const theme = JSON.parse(jsonText);
    if (!theme.id || !theme.name) {
      throw new Error(`Theme ${id} is missing id or name`);
    }
    manifest.push({ id: theme.id, name: theme.name });
    writeTo(SHARED_DIR, id, jsonText);
    writeTo(IOS_DIR, id, jsonText);
    writeTo(ANDROID_DIR, id, jsonText);
    console.log(`synced ${id}`);
  }

  manifest.sort((a, b) => a.name.localeCompare(b.name, undefined, { sensitivity: "base" }));
  const manifestJson = JSON.stringify(manifest, null, 2) + "\n";
  writeFileSync(join(SHARED_DIR, "manifest.json"), manifestJson, "utf8");
  writeFileSync(join(IOS_DIR, "manifest.json"), manifestJson, "utf8");
  writeFileSync(join(ANDROID_DIR, "manifest.json"), manifestJson, "utf8");

  console.log(`Synced ${manifest.length} theme presets (ref ${OPENCODE_THEME_REF})`);
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
