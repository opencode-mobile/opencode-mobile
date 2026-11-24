#!/usr/bin/env node
// Writes captures/contract.json: the component and state ids native capture tests must produce.
// Usage: node export-capture-contract.mjs [component-id ...]   (no ids = every component)
import { execFileSync } from "node:child_process";
import { mkdirSync, readFileSync, readdirSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import vm from "node:vm";

const here = dirname(fileURLToPath(import.meta.url));
execFileSync(process.execPath, [join(here, "build.mjs")], { stdio: "ignore" });
const dom = new Proxy(() => {}, { get: () => dom, apply: () => dom });
const sandbox = { document: dom, addEventListener: dom, getComputedStyle: dom };
sandbox.window = sandbox;
vm.createContext(sandbox);
const files = ["data.js", "kit.js", ...readdirSync(join(here, "components")).filter((f) => f.endsWith(".js")).sort().map((f) => `components/${f}`)];
for (const file of files) vm.runInContext(readFileSync(join(here, file), "utf8"), sandbox, { filename: file });

export const slug = (s) => String(s).toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "");
const wanted = process.argv.slice(2);
const entries = sandbox.DS.entries.filter((e) => !wanted.length || wanted.includes(e.id));
const components = entries.map((e) => {
  const c = sandbox.COMPONENTS[e.id];
  const states = (c?.states ?? [["Default", {}]]).map(([name, props]) => ({ id: slug(name), name, props: { ...c?.defaults, ...props } }));
  return { id: e.id, title: e.title, category: e.category, platforms: e.platforms, nativeGalleryKey: e.key, states };
});
mkdirSync(join(here, "captures"), { recursive: true });
writeFileSync(join(here, "captures/contract.json"), JSON.stringify({ version: 1, theme: "porcelain", appearances: ["light", "dark"], components }, null, 2) + "\n");
console.log(components.map((c) => `${c.id}: ${c.states.map((s) => s.id).join(", ")}`).join("\n"));
