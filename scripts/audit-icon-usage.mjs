#!/usr/bin/env node
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join, relative } from "node:path";
import { loadManifest, REPO_ROOT } from "./lib/utils.mjs";

const EXCLUDE_PATH_SUFFIXES = [
  "ui/icons/OpenCodeIcons.kt",
  "Icons/OpenCodeIcon.swift",
  "IconsGalleryScreen.kt",
  "IconsGalleryView.swift",
];

const ANDROID_ROOT = join(REPO_ROOT, "android/app/src/main/java");
const IOS_ROOT = join(REPO_ROOT, "ios/OpenCode Mobile/OpenCode Mobile");

function camelToSnake(camel) {
  return camel.replace(/([a-z0-9])([A-Z])/g, "$1_$2").toLowerCase();
}

function pascalToSnake(pascal) {
  return camelToSnake(pascal.charAt(0).toLowerCase() + pascal.slice(1));
}

function shouldExclude(relativePath) {
  return EXCLUDE_PATH_SUFFIXES.some((suffix) => relativePath.endsWith(suffix));
}

function walkFiles(dir, extension, files = []) {
  for (const entry of readdirSync(dir)) {
    const fullPath = join(dir, entry);
    if (statSync(fullPath).isDirectory()) {
      walkFiles(fullPath, extension, files);
      continue;
    }
    if (entry.endsWith(extension)) {
      files.push(fullPath);
    }
  }
  return files;
}

function collectAndroidUsage() {
  const used = new Set();
  const androidIconPattern = /OpenCodeIcons\.([A-Z][A-Za-z0-9]*)/g;

  for (const file of walkFiles(ANDROID_ROOT, ".kt")) {
    const rel = relative(REPO_ROOT, file);
    if (shouldExclude(rel)) continue;

    const content = readFileSync(file, "utf8");
    for (const match of content.matchAll(androidIconPattern)) {
      used.add(pascalToSnake(match[1]));
    }
  }

  return used;
}

function collectIosUsage() {
  const used = new Set();
  const enumPath = join(IOS_ROOT, "Icons/OpenCodeIcon.swift");
  const enumCases = [
    ...readFileSync(enumPath, "utf8").matchAll(/case\s+([a-z][a-zA-Z0-9]*)\s*=/g),
  ].map((match) => match[1]);
  const caseSet = new Set(enumCases);

  const dottedCasePattern = /\.([a-z][a-zA-Z0-9]*)\b/g;

  for (const file of walkFiles(IOS_ROOT, ".swift")) {
    const rel = relative(REPO_ROOT, file);
    if (shouldExclude(rel)) continue;

    const content = readFileSync(file, "utf8");
    for (const match of content.matchAll(dottedCasePattern)) {
      const caseName = match[1];
      if (caseSet.has(caseName)) {
        used.add(camelToSnake(caseName));
      }
    }
  }

  return used;
}

function main() {
  const manifestIcons = loadManifest();
  const manifestNames = new Set(manifestIcons.map((icon) => icon.name));

  const androidUsed = collectAndroidUsage();
  const iosUsed = collectIosUsage();
  const used = new Set([...androidUsed, ...iosUsed]);

  const missingFromManifest = [...used].filter((name) => !manifestNames.has(name)).sort();
  const unusedInManifest = [...manifestNames].filter((name) => !used.has(name)).sort();

  let failed = false;

  console.log(`Manifest icons: ${manifestNames.size}`);
  console.log(`Used in Android: ${androidUsed.size}`);
  console.log(`Used in iOS: ${iosUsed.size}`);
  console.log(`Used overall: ${used.size}`);

  if (missingFromManifest.length > 0) {
    failed = true;
    console.error("\nUsed in app code but missing from icons/manifest.yaml:");
    for (const name of missingFromManifest) {
      console.error(`  - ${name}`);
    }
  }

  if (unusedInManifest.length > 0) {
    console.warn("\nIn manifest but not referenced in app code (warning only):");
    for (const name of unusedInManifest) {
      console.warn(`  - ${name}`);
    }
  }

  if (!failed && missingFromManifest.length === 0) {
    console.log("\nIcon usage matches manifest.");
  } else if (failed) {
    process.exit(1);
  }
}

main();
