#!/usr/bin/env node
// Reads the real design-system sources and writes data.js.
// Run from anywhere: npm run gallery:build
import { cpSync, existsSync, readFileSync, readdirSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { createRequire } from "node:module";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "../..");
const require = createRequire(join(root, "package.json"));
const YAML = require("yaml");
const read = (p) => readFileSync(join(root, p), "utf8");

// Port of android/.../theme/ThemeResolver.kt
const rgb = (h) => { const c = h.replace("#", "").slice(0, 6); const v = parseInt(c, 16); return [(v >> 16) & 255, (v >> 8) & 255, v & 255]; };
// Android truncates mixed channels and iOS rounds them.
let channel = Math.trunc;
const hex = (a) => "#" + a.map((x) => channel(Math.min(255, Math.max(0, x))).toString(16).padStart(2, "0")).join("").toUpperCase();
const mix = (a, b, t) => { const x = rgb(a), y = rgb(b); return hex(x.map((v, i) => v + (y[i] - v) * t)); };
const readable = (f) => { const [r, g, b] = rgb(f); return (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255 > 0.55 ? "#171311" : "#FFFFFF"; };

function resolve({ palette: p, overrides = {} }, dark) {
  const { neutral: n, ink: k, primary, success, warning, error, info } = p;
  const interactive = p.interactive ?? primary;
  const strong = mix(k, dark ? "#FFFFFF" : "#000000", dark ? 0.04 : 0.02);
  const base = mix(n, dark ? "#000000" : "#FFFFFF", dark ? 0.1 : 0.22);
  const hover = mix(n, dark ? "#FFFFFF" : "#000000", dark ? 0.05 : 0.07);
  const textBase = mix(k, n, dark ? 0.42 : 0.45);
  const t = {
    "background-base": base, "surface-base": base, "surface-base-hover": dark ? "#FFFFFF0D" : "#0000000A",
    "surface-raised-base": mix(n, dark ? "#FFFFFF" : "#000000", dark ? 0.02 : 0.03), "surface-raised-base-hover": hover,
    "surface-interactive-weak": mix(interactive, dark ? n : "#FFFFFF", dark ? 0.78 : 0.94),
    "surface-success-base": mix(success, dark ? n : "#FFFFFF", dark ? 0.82 : 0.86),
    "surface-critical-base": mix(error, dark ? n : "#FFFFFF", dark ? 0.82 : 0.86),
    "text-strong": strong, "text-base": textBase, "text-weak": mix(k, n, dark ? 0.58 : 0.62), "text-weaker": mix(k, n, dark ? 0.72 : 0.78),
    "text-interactive-base": interactive, "text-on-brand-base": readable(primary),
    "border-weak-base": mix(n, k, dark ? 0.06 : 0.12), "border-weaker-base": mix(n, k, dark ? 0.03 : 0.07),
    "icon-base": mix(k, n, dark ? 0.55 : 0.62), "icon-weak-base": mix(k, n, dark ? 0.78 : 0.82), "icon-strong-base": strong,
    "icon-invert-base": dark ? "#1C1C1C" : "#FFFFFF", "button-primary-base": strong,
    "button-secondary-base": dark ? base : "#FCFCFC", "button-secondary-hover": hover,
    interactive, success, warning, error, info, "diff-add": p.diffAdd ?? success, "diff-delete": p.diffDelete ?? error,
    "syntax-comment": mix(k, n, 0.55), "syntax-keyword": info, "syntax-string": "#00CEB9",
    "syntax-function": dark ? mix(interactive, "#FFFFFF", 0.42) : interactive, "syntax-property": dark ? primary : info,
    "syntax-type": warning, "syntax-success": success, "syntax-warning": warning, "syntax-info": info,
    "syntax-constant": dark ? "#93E9F6" : "#007B80", "syntax-number": dark ? "#93E9F6" : "#007B80", "syntax-attribute": warning,
    "syntax-variable": dark ? "#E06C75" : "#D1383D", "syntax-operator": dark ? "#56B6C2" : "#318795", "syntax-punctuation": strong,
    "syntax-critical": dark ? primary : "#FF8C00", "syntax-diff-delete": dark ? primary : "#FF8C00", "syntax-diff-unknown": info,
    "markdown-h1": strong, "markdown-h2": strong, "markdown-h3": strong, "markdown-link-text": strong, "markdown-link-underline": strong,
    "markdown-code-text": strong, "markdown-code-bg": dark ? "#FFFFFF14" : "#00000014", "markdown-quote-text": textBase,
    "markdown-quote-bar": mix(n, k, dark ? 0.06 : 0.12), "markdown-list-marker": textBase, "markdown-image": mix(k, n, dark ? 0.58 : 0.62),
    "markdown-hr": mix(n, k, dark ? 0.03 : 0.07), "markdown-strong": strong, "markdown-em": textBase,
  };
  return { ...t, ...overrides };
}

const themes = JSON.parse(read("shared/theme-presets/manifest.json")).map((m) => {
  const d = JSON.parse(read(`shared/theme-presets/${m.id}.json`));
  channel = Math.trunc;
  const android = { light: resolve(d.light, false), dark: resolve(d.dark, true) };
  channel = Math.round;
  const ios = { light: resolve(d.light, false), dark: resolve(d.dark, true) };
  channel = Math.trunc;
  return { id: m.id, name: m.name, blurb: m.blurb, ...android, ios };
});

const manifest = YAML.parse(read("icons/manifest.yaml")).icons;
const icons = {};
for (const i of manifest) {
  const sources = [`icons/cache/lucide/${i.name}.svg`, `node_modules/lucide-static/icons/${i.lucide}.svg`];
  const file = sources.find((p) => existsSync(join(root, p)));
  if (!file) { console.warn(`missing icon: ${i.name} (${i.lucide})`); continue; }
  const filled = `icons/cache/svg/filled/${i.name}.svg`;
  icons[i.name] = {
    notes: i.notes ?? "", lucide: i.lucide ?? "",
    filled: existsSync(join(root, filled)) ? read(filled).replace(/<!--[\s\S]*?-->/g, "").trim().replace(/<svg[^>]*>/, (tag) => tag.replace(/\s(width|height)="[^"]*"/g, "")).replace(/\s+/g, " ") : undefined,
    svg: read(file).replace(/<!--[\s\S]*?-->/g, "").trim().replace(/<svg[^>]*>/, (tag) => tag.replace(/\s(width|height)="[^"]*"/g, "")).replace(/\s+/g, " ").replace(/stroke-width="2"/g, 'stroke-width="1.5"'),
  };
}

const docs = {};
for (const f of readdirSync(join(root, "docs/design-system/components"))) {
  if (f.endsWith(".md") && f !== "README.md") docs[f.slice(0, -3)] = read(`docs/design-system/components/${f}`);
}

const src = read("android/app/src/main/java/dev/opencode/mobile/ui/componentgallery/ComponentGalleryEntry.kt");
const body = src.slice(src.indexOf("val ComponentGalleryEntries"));
const entries = [...body.matchAll(/ComponentGalleryEntry\(\s*id = ComponentGalleryEntryId\.(\w+),\s*title = "([^"]+)",\s*description =\s*((?:"(?:[^"\\]|\\.)*"\s*\+?\s*)+),\s*category = ComponentGalleryCategory\.(\w+)/g)]
  .map((m) => ({
    key: m[1], title: m[2], category: m[4],
    description: [...m[3].matchAll(/"((?:[^"\\]|\\.)*)"/g)].map((x) => x[1]).join(""),
    id: m[2].toLowerCase().replace(/[^a-z0-9]+/g, "-"),
  }));

const iosSrc = read("ios/OpenCode Mobile/OpenCode Mobile/ComponentGallery/ComponentGalleryEntry.swift");
const CAT = (s) => s[0].toUpperCase() + s.slice(1);
const iosEntries = [...iosSrc.slice(iosSrc.indexOf("let componentGalleryEntries")).matchAll(/ComponentGalleryEntry\(\s*id: \.(\w+),\s*title: "([^"]+)",\s*description:\s*((?:"(?:[^"\\]|\\.)*"\s*\+?\s*)+),\s*category: \.(\w+)/g)]
  .map((m) => ({ key: m[1], title: m[2], category: CAT(m[4]), description: [...m[3].matchAll(/"((?:[^"\\]|\\.)*)"/g)].map((x) => x[1]).join(""), id: m[2].toLowerCase().replace(/[^a-z0-9]+/g, "-") }));
for (const e of entries) e.platforms = iosEntries.some((x) => x.id === e.id) ? ["ios", "android"] : ["android"];
for (const e of iosEntries) if (!entries.some((x) => x.id === e.id)) entries.push({ ...e, platforms: ["ios"] });

const duo = JSON.parse(read("docs/devices/iphone-duo/geometry.json"));
cpSync(join(root, "icons/file-types/svg"), join(here, "assets/file-types"), { recursive: true });
const fileIconMaps = JSON.parse(read("icons/file-types/maps.json"));
const data = { themes, icons, docs, entries, fileIconMaps, duo: { displays: duo.displays, poses: duo.poses } };
writeFileSync(join(here, "data.js"), `window.DS=${JSON.stringify(data)};\n`);
console.log(`themes ${themes.length}, icons ${Object.keys(icons).length}/${manifest.length}, docs ${Object.keys(docs).length}, entries ${entries.length}`);
