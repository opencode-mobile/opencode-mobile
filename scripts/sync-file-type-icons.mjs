#!/usr/bin/env node
// File-type icons (OpenCodeFileIcon) for iOS and Android.
//
//   node scripts/sync-file-type-icons.mjs --import <opencode checkout>
//     Re-vendors icons/file-types/ from an opencode checkout: the lookup tables in
//     packages/ui/src/components/file-icon.tsx and the SVGs they can reach.
//   node scripts/sync-file-type-icons.mjs
//     Regenerates the platform outputs from icons/file-types/.
//
// Unlike sync-icons.mjs (stroke-only Lucide templates), these icons are
// multi-colour and keep their original colours.
import { execFileSync } from "node:child_process";
import { createRequire } from "node:module";
import { copyFileSync, mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { REPO_ROOT } from "./lib/utils.mjs";

const require = createRequire(import.meta.url);
const svg2vectordrawable = require("svg2vectordrawable");

const VENDOR_DIR = join(REPO_ROOT, "icons/file-types");
const VENDOR_SVG_DIR = join(VENDOR_DIR, "svg");
const MAPS_FILE = join(VENDOR_DIR, "maps.json");
const IOS_ROOT = join(REPO_ROOT, "ios/OpenCode Mobile/OpenCode Mobile");
const IOS_XCASSETS = join(IOS_ROOT, "FileTypeIcons.xcassets");
const IOS_TABLES = join(IOS_ROOT, "Icons/OpenCodeFileTypeIconTables.swift");
const ANDROID_RES = join(REPO_ROOT, "android/app/src/main/res/drawable");
const ANDROID_TABLES = join(
  REPO_ROOT,
  "android/app/src/main/java/dev/opencode/mobile/ui/icons/OpenCodeFileTypeIconTables.kt",
);
const ASSET_PREFIX = "filetype_";

// --- Shared helpers -----------------------------------------------------------

/** vite-plugin-icons-spritesheet's default name: `folder-open` → `FolderOpen`. */
function spriteName(fileName) {
  return fileName
    .replace(/\.svg$/, "")
    .split("-")
    .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
    .join("");
}

/** Upstream `toOpenVariant`. */
function toOpenVariant(icon) {
  if (!icon.startsWith("Folder")) return icon;
  if (icon.endsWith("_light")) return icon.replace("_light", "Open_light");
  if (!icon.endsWith("Open")) return `${icon}Open`;
  return icon;
}

/** Every icon name the lookup can return, including expanded-folder variants. */
function reachableNames(maps) {
  const names = new Set([
    ...Object.values(maps.fileNames),
    ...Object.values(maps.fileExtensions),
    ...Object.values(maps.folderNames),
    ...Object.values(maps.defaults),
  ]);
  for (const icon of [...Object.values(maps.folderNames), maps.defaults.folder]) {
    names.add(toOpenVariant(icon));
  }
  return [...names].sort();
}

function assetName(svgFile) {
  return ASSET_PREFIX + svgFile.replace(/\.svg$/, "").replaceAll("-", "_").toLowerCase();
}

// --- Import from opencode -------------------------------------------------------

function extractIconMaps(source) {
  const start = source.indexOf("const ICON_MAPS: IconMaps = ");
  if (start < 0) throw new Error("ICON_MAPS not found in file-icon.tsx");
  const open = source.indexOf("{", start);
  let depth = 0;
  for (let i = open; i < source.length; i++) {
    if (source[i] === "{") depth++;
    else if (source[i] === "}" && --depth === 0) {
      // The object is a plain literal of string keys and string values.
      return Function(`"use strict"; return (${source.slice(open, i + 1)})`)();
    }
  }
  throw new Error("Unbalanced ICON_MAPS literal");
}

function importFromOpencode(checkout) {
  const iconSource = readFileSync(join(checkout, "packages/ui/src/components/file-icon.tsx"), "utf8");
  const maps = extractIconMaps(iconSource);
  const svgDir = join(checkout, "packages/ui/src/assets/icons/file-types");
  const svgByName = new Map(
    readdirSync(svgDir)
      .filter((file) => file.endsWith(".svg"))
      .map((file) => [spriteName(file), file]),
  );
  const names = reachableNames(maps);
  const icons = {};
  for (const name of names) {
    const file = svgByName.get(name);
    if (!file) throw new Error(`No SVG for icon ${name}`);
    icons[name] = file;
  }

  const commit = execFileSync("git", ["-C", checkout, "rev-parse", "HEAD"], { encoding: "utf8" }).trim();
  rmSync(VENDOR_SVG_DIR, { recursive: true, force: true });
  mkdirSync(VENDOR_SVG_DIR, { recursive: true });
  for (const file of Object.values(icons)) copyFileSync(join(svgDir, file), join(VENDOR_SVG_DIR, file));

  const sortKeys = (record) => Object.fromEntries(Object.entries(record).sort(([a], [b]) => (a < b ? -1 : 1)));
  const payload = {
    source: { repository: "https://github.com/anomalyco/opencode", commit },
    defaults: maps.defaults,
    fileNames: sortKeys(maps.fileNames),
    fileExtensions: sortKeys(maps.fileExtensions),
    folderNames: sortKeys(maps.folderNames),
    icons,
  };
  writeFileSync(MAPS_FILE, `${JSON.stringify(payload, null, 2)}\n`, "utf8");
  console.log(`Imported ${names.length} icons from opencode ${commit.slice(0, 10)}`);
}

// --- iOS ----------------------------------------------------------------------

const IMAGESET_CONTENTS = `{
  "images" : [
    {
      "filename" : "icon.svg",
      "idiom" : "universal"
    }
  ],
  "info" : {
    "author" : "xcode",
    "version" : 1
  },
  "properties" : {
    "preserves-vector-representation" : true,
    "template-rendering-intent" : "original"
  }
}
`;

const CATALOG_CONTENTS = `{
  "info" : {
    "author" : "xcode",
    "version" : 1
  }
}
`;

function generateIosAssets(maps) {
  rmSync(IOS_XCASSETS, { recursive: true, force: true });
  mkdirSync(IOS_XCASSETS, { recursive: true });
  writeFileSync(join(IOS_XCASSETS, "Contents.json"), CATALOG_CONTENTS, "utf8");
  for (const file of new Set(Object.values(maps.icons))) {
    const dir = join(IOS_XCASSETS, `${assetName(file)}.imageset`);
    mkdirSync(dir, { recursive: true });
    writeFileSync(join(dir, "icon.svg"), normalizeSvg(readFileSync(join(VENDOR_SVG_DIR, file), "utf8")), "utf8");
    writeFileSync(join(dir, "Contents.json"), IMAGESET_CONTENTS, "utf8");
  }
}

function swiftString(value) {
  return `"${value.replaceAll("\\", "\\\\").replaceAll('"', '\\"')}"`;
}

function swiftDictionary(name, record) {
  const lines = Object.entries(record).map(([key, value]) => `        ${swiftString(key)}: ${swiftString(value)},`);
  return `    static let ${name}: [String: String] = [\n${lines.join("\n")}\n    ]`;
}

function generateIosTables(maps) {
  const assets = Object.fromEntries(Object.entries(maps.icons).map(([name, file]) => [name, assetName(file)]));
  const body = [
    "// Generated by scripts/sync-file-type-icons.mjs from icons/file-types/maps.json — do not edit.",
    "",
    "nonisolated enum OpenCodeFileTypeIconTables {",
    `    static let defaultFile = ${swiftString(maps.defaults.file)}`,
    `    static let defaultFolder = ${swiftString(maps.defaults.folder)}`,
    `    static let defaultFolderOpen = ${swiftString(maps.defaults.folderOpen)}`,
    "",
    swiftDictionary("fileNames", maps.fileNames),
    "",
    swiftDictionary("fileExtensions", maps.fileExtensions),
    "",
    swiftDictionary("folderNames", maps.folderNames),
    "",
    "    /// Icon name → asset in FileTypeIcons.xcassets.",
    swiftDictionary("assets", assets),
    "}",
    "",
  ];
  writeFileSync(IOS_TABLES, body.join("\n"), "utf8");
}

// --- Android ------------------------------------------------------------------

/**
 * Bakes `gradientTransform` into the gradient coordinates. svg2vectordrawable
 * ignores the attribute, and VectorDrawable gradients have no transform.
 */
function bakeGradientTransforms(svg) {
  return svg.replace(/<(linearGradient|radialGradient)\b([^>]*)>/g, (tag, kind, attrs) => {
    const transformMatch = attrs.match(/\sgradientTransform="([^"]*)"/);
    if (!transformMatch) return tag;
    const m = parseTransform(transformMatch[1]);
    const get = (name, fallback) => {
      const match = attrs.match(new RegExp(`\\s${name}="([^"]*)"`));
      return match ? Number(match[1]) : fallback;
    };
    if (!/gradientUnits="userSpaceOnUse"/.test(attrs)) {
      throw new Error("gradientTransform without userSpaceOnUse is not supported");
    }
    let rest = attrs.replace(transformMatch[0], "");
    const apply = ([x, y]) => [m[0] * x + m[2] * y + m[4], m[1] * x + m[3] * y + m[5]];
    const fmt = (value) => Number(value.toFixed(4));
    const setAttr = (name, value) => {
      const pattern = new RegExp(`\\s${name}="[^"]*"`);
      rest = pattern.test(rest) ? rest.replace(pattern, ` ${name}="${fmt(value)}"`) : `${rest} ${name}="${fmt(value)}"`;
    };
    if (kind === "linearGradient") {
      // Keep the colour a function of the same linear form: start maps through
      // the matrix; the direction is chosen so iso-lines stay perpendicular.
      const p1 = [get("x1", 0), get("y1", 0)];
      const d = [get("x2", 1) - p1[0], get("y2", 0) - p1[1]];
      const det = m[0] * m[3] - m[1] * m[2];
      const dd = d[0] * d[0] + d[1] * d[1];
      // w = A^-T d / |d|^2
      const w = [(m[3] * d[0] - m[1] * d[1]) / det / dd, (-m[2] * d[0] + m[0] * d[1]) / det / dd];
      const ww = w[0] * w[0] + w[1] * w[1];
      const start = apply(p1);
      setAttr("x1", start[0]);
      setAttr("y1", start[1]);
      setAttr("x2", start[0] + w[0] / ww);
      setAttr("y2", start[1] + w[1] / ww);
    } else {
      const center = apply([get("cx", 0.5), get("cy", 0.5)]);
      const scale = Math.sqrt(Math.abs(m[0] * m[3] - m[1] * m[2]));
      setAttr("cx", center[0]);
      setAttr("cy", center[1]);
      setAttr("r", get("r", 0.5) * scale);
    }
    return `<${kind}${rest}>`;
  });
}

function multiply(a, b) {
  return [
    a[0] * b[0] + a[2] * b[1],
    a[1] * b[0] + a[3] * b[1],
    a[0] * b[2] + a[2] * b[3],
    a[1] * b[2] + a[3] * b[3],
    a[0] * b[4] + a[2] * b[5] + a[4],
    a[1] * b[4] + a[3] * b[5] + a[5],
  ];
}

function parseTransform(text) {
  let matrix = [1, 0, 0, 1, 0, 0];
  for (const [, op, rawArgs] of text.matchAll(/(\w+)\s*\(([^)]*)\)/g)) {
    const args = rawArgs.trim().split(/[\s,]+/).filter(Boolean).map(Number);
    let next;
    switch (op) {
      case "matrix":
        next = args;
        break;
      case "translate":
        next = [1, 0, 0, 1, args[0], args[1] ?? 0];
        break;
      case "scale":
        next = [args[0], 0, 0, args[1] ?? args[0], 0, 0];
        break;
      case "rotate": {
        const rad = (args[0] * Math.PI) / 180;
        const [cx, cy] = [args[1] ?? 0, args[2] ?? 0];
        next = multiply(
          multiply([1, 0, 0, 1, cx, cy], [Math.cos(rad), Math.sin(rad), -Math.sin(rad), Math.cos(rad), 0, 0]),
          [1, 0, 0, 1, -cx, -cy],
        );
        break;
      }
      default:
        throw new Error(`Unsupported transform ${op}`);
    }
    matrix = multiply(matrix, next);
  }
  return matrix;
}

/** Inline `style="a:b"` declarations as attributes for both converters. */
function normalizeSvg(svg) {
  return svg.replace(/\sstyle="([^"]*)"/g, (_, style) =>
    style
      .split(";")
      .map((declaration) => declaration.trim())
      .filter(Boolean)
      .map((declaration) => {
        const [name, ...value] = declaration.split(":");
        return ` ${name.trim()}="${value.join(":").trim()}"`;
      })
      .join(""),
  );
}

/**
 * Writes the inherited `fill` onto every shape that has none. svg2vectordrawable
 * drops fills set on `<svg>` or `<g>` (readme.svg sets `fill="none"` on the root),
 * and VectorDrawable paths without a fill draw nothing instead of black.
 * Shapes that end up with neither fill nor stroke are dropped.
 */
function explicitFills(svg) {
  const shapes = new Set(["path", "circle", "ellipse", "rect", "polygon", "polyline", "line"]);
  const stack = ["#000"];
  return svg.replace(/<(\/?)([a-zA-Z][\w:-]*)([^>]*?)(\/?)>/g, (tag, closing, name, attrs, selfClosing) => {
    if (closing) {
      stack.pop();
      return tag;
    }
    const own = attrs.match(/\sfill="([^"]*)"/)?.[1];
    const fill = own ?? stack[stack.length - 1];
    if (!selfClosing) stack.push(fill);
    if (!shapes.has(name)) return tag;
    // An unfilled, unstroked shape draws nothing; fillBlack would paint it black.
    if (fill === "none" && selfClosing && !/\sstroke="(?!none)/.test(attrs)) return "";
    if (own == null) return `<${name}${attrs} fill="${fill}"${selfClosing}>`;
    return tag;
  });
}

async function generateAndroidDrawables(maps) {
  mkdirSync(ANDROID_RES, { recursive: true });
  for (const file of readdirSync(ANDROID_RES)) {
    if (file.startsWith(ASSET_PREFIX)) rmSync(join(ANDROID_RES, file));
  }
  for (const file of new Set(Object.values(maps.icons))) {
    const svg = explicitFills(bakeGradientTransforms(normalizeSvg(readFileSync(join(VENDOR_SVG_DIR, file), "utf8"))));
    const converted = await svg2vectordrawable(svg, { floatPrecision: 3, fillBlack: true, xmlTag: true });
    const xml = converted
      // fillBlack also tags gradient paths; the aapt:attr child must be the only fill.
      .replace(
        /(<path\b[^>]*?)\s+android:fillColor="[^"]*"([^>]*>\s*<aapt:attr name="android:fillColor")/g,
        "$1$2",
      )
      // Size comes from the caller; keep every intrinsic size at 24dp.
      .replace(/android:width="[^"]*"/, 'android:width="24dp"')
      .replace(/android:height="[^"]*"/, 'android:height="24dp"');
    if (!xml.includes("<path")) throw new Error(`Empty VectorDrawable for ${file}`);
    if (/<(use|mask|style)\b/.test(xml)) throw new Error(`Unconverted SVG element in ${file}`);
    writeFileSync(join(ANDROID_RES, `${assetName(file)}.xml`), `${xml.trim()}\n`, "utf8");
  }
}

function kotlinString(value) {
  return `"${value.replaceAll("\\", "\\\\").replaceAll('"', '\\"').replaceAll("$", "\\$")}"`;
}

function kotlinMap(name, record, valueOf = kotlinString) {
  const lines = Object.entries(record).map(([key, value]) => `            ${kotlinString(key)} to ${valueOf(value)},`);
  return `    val ${name}: Map<String, ${valueOf === kotlinString ? "String" : "Int"}> =\n        mapOf(\n${lines.join("\n")}\n        )`;
}

function generateAndroidTables(maps) {
  const body = [
    "// Generated by scripts/sync-file-type-icons.mjs from icons/file-types/maps.json — do not edit.",
    "package dev.opencode.mobile.ui.icons",
    "",
    "import dev.opencode.mobile.R",
    "",
    "internal object OpenCodeFileTypeIconTables {",
    `    const val DEFAULT_FILE = ${kotlinString(maps.defaults.file)}`,
    `    const val DEFAULT_FOLDER = ${kotlinString(maps.defaults.folder)}`,
    `    const val DEFAULT_FOLDER_OPEN = ${kotlinString(maps.defaults.folderOpen)}`,
    "",
    kotlinMap("fileNames", maps.fileNames),
    "",
    kotlinMap("fileExtensions", maps.fileExtensions),
    "",
    kotlinMap("folderNames", maps.folderNames),
    "",
    "    /** Icon name → `R.drawable.filetype_*`. */",
    kotlinMap("drawables", maps.icons, (file) => `R.drawable.${assetName(file)}`),
    "}",
    "",
  ];
  writeFileSync(ANDROID_TABLES, body.join("\n"), "utf8");
}

// --- Main ---------------------------------------------------------------------

async function main() {
  const importIndex = process.argv.indexOf("--import");
  if (importIndex >= 0) {
    const checkout = process.argv[importIndex + 1];
    if (!checkout) throw new Error("--import needs the path to an opencode checkout");
    importFromOpencode(checkout);
  }
  const maps = JSON.parse(readFileSync(MAPS_FILE, "utf8"));
  generateIosAssets(maps);
  generateIosTables(maps);
  await generateAndroidDrawables(maps);
  generateAndroidTables(maps);
  console.log(`Generated ${new Set(Object.values(maps.icons)).size} file-type icons for iOS and Android`);
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
