#!/usr/bin/env node
/**
 * Generates the Android Kotlin Ktor client from api/opencode-sessions.openapi.yaml.
 * Run scripts/fetch-openapi.mjs first when validating against a live server /doc export.
 */
import { spawnSync } from "node:child_process";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const spec = path.join(root, "api", "opencode-sessions.openapi.yaml");
const outDir = path.join(
  root,
  "android",
  "app",
  "build",
  "generated",
  "openapi",
);

const args = [
  "openapi-generator-cli",
  "generate",
  "-i",
  spec,
  "-g",
  "kotlin",
  "-o",
  outDir,
  "--additional-properties",
  [
    "library=ktor",
    "packageName=dev.opencode.mobile.generated.api",
    "apiSuffix=Api",
    "serializableModel=true",
    "dateLibrary=kotlinx-datetime",
  ].join(","),
];

const result = spawnSync("npx", ["--yes", "@openapitools/openapi-generator-cli", ...args.slice(1)], {
  cwd: root,
  stdio: "inherit",
  env: process.env,
});

if (result.status !== 0) {
  process.exit(result.status ?? 1);
}

console.log(`Android client generated under ${outDir}`);
