#!/usr/bin/env node
/**
 * Fetches the live OpenAPI document from opencode serve and writes it under build/openapi/.
 * Sessions codegen uses api/opencode-sessions.openapi.yaml until /doc includes session paths.
 */
import { mkdir, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const url =
  process.env.OPENCODE_OPENAPI_URL ?? "http://127.0.0.1:4096/doc";
const outDir = path.join(root, "build", "openapi");
const outFile = path.join(outDir, "opencode-doc.json");

const response = await fetch(url);
if (!response.ok) {
  console.error(`fetch-openapi: GET ${url} failed (${response.status})`);
  console.error("Ensure opencode serve is running (default http://127.0.0.1:4096).");
  process.exit(1);
}

const body = await response.text();
await mkdir(outDir, { recursive: true });
await writeFile(outFile, body, "utf8");
console.log(`Wrote ${outFile}`);
