#!/usr/bin/env node
// Fails when a native gallery entry has no HTML component, or an HTML component has no native entry.
import { execFileSync } from "node:child_process";
import { readFileSync, readdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import vm from "node:vm";

const here = dirname(fileURLToPath(import.meta.url));
execFileSync(process.execPath, [join(here, "build.mjs")], { stdio: "ignore" });
// Components only need the DOM once rendered, so anything touched at load is a stub here.
const dom = new Proxy(() => {}, { get: () => dom, apply: () => dom });
const sandbox = { document: dom, addEventListener: dom, getComputedStyle: dom };
sandbox.window = sandbox;
vm.createContext(sandbox);
const files = ["data.js", "kit.js", ...readdirSync(join(here, "components")).filter((f) => f.endsWith(".js")).sort().map((f) => `components/${f}`)];
for (const file of files) vm.runInContext(readFileSync(join(here, file), "utf8"), sandbox, { filename: file });
const native = sandbox.DS.entries.map((e) => e.id), html = Object.keys(sandbox.COMPONENTS);
const missing = native.filter((id) => !html.includes(id)), extra = html.filter((id) => !native.includes(id));
if (missing.length) console.error(`No HTML version: ${missing.join(", ")}`);
if (extra.length) console.error(`No native gallery entry: ${extra.join(", ")}`);
if (missing.length || extra.length) process.exit(1);
console.log(`Gallery coverage OK: ${html.length} of ${native.length} components.`);
