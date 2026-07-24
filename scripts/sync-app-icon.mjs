#!/usr/bin/env node
// Generates the app launcher icon set for both platforms from
// app-icon/source.svg. Emits the Android adaptive-icon vector drawables and
// the iOS AppIcon.appiconset PNGs (any/dark/tinted). See
// docs/design-system/app-icon.md.
import { execFileSync } from "node:child_process";
import { existsSync, readFileSync, writeFileSync, mkdirSync, rmSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const REPO_ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const SOURCE_SVG = join(REPO_ROOT, "app-icon/source.svg");
const IOS_APPICONSET = join(REPO_ROOT, "ios/OpenCode Mobile/OpenCode Mobile/Assets.xcassets/AppIcon.appiconset");
const ANDROID_RES = join(REPO_ROOT, "android/app/src/main/res");

const PNG_SIZE = 1024;

function fail(msg) {
  console.error(`app-icon: ${msg}`);
  process.exit(1);
}

function ensureRsvg() {
  try {
    execFileSync("rsvg-convert", ["--version"], { stdio: "ignore" });
  } catch {
    fail(
      "rsvg-convert not found. Install librsvg: `brew install librsvg` (macOS) or `apt install librsvg2-bin` (Linux).",
    );
  }
}

// Parse the controlled source SVG. We rely on the @opencode/background and
// @opencode/glyph marker comments documented in app-icon/source.svg.
function parseSource(src) {
  const viewBoxMatch = src.match(/<svg[^>]*\sviewBox="0\s+0\s+(\d+)\s+(\d+)"/);
  if (!viewBoxMatch) fail("source.svg: missing <svg viewBox=\"0 0 W H\">");
  const viewBoxWidth = parseInt(viewBoxMatch[1], 10);
  const viewBoxHeight = parseInt(viewBoxMatch[2], 10);
  if (viewBoxWidth <= 0 || viewBoxHeight <= 0) fail("source.svg: invalid viewBox");

  const bgMatch = src.match(
    /<!--\s*@opencode\/background\s*-->\s*<rect\s+([^>]*)\/>/,
  );
  if (!bgMatch) fail("source.svg: missing <!-- @opencode/background --> <rect .../>");
  const bgAttr = bgMatch[1];
  const fillMatch = bgAttr.match(/fill="([^"]+)"/);
  if (!fillMatch) fail("source.svg: background <rect> must declare fill=\"...\"");
  const backgroundFill = fillMatch[1];

  const glyphMatch = src.match(
    /<!--\s*@opencode\/glyph\s*-->\s*(<g[\s\S]*?<\/g>)/,
  );
  if (!glyphMatch) fail("source.svg: missing <!-- @opencode/glyph --> <g>...</g>");
  const glyphGroup = glyphMatch[1];

  const transformMatch = glyphGroup.match(/transform="([^"]*)"/);
  if (!transformMatch) fail("source.svg: glyph <g> must declare transform=\"...\"");
  const transform = transformMatch[1];

  const paths = [...glyphGroup.matchAll(/<path\s+d="([^"]+)"\s+fill="([^"]+)"/g)].map(
    (m) => ({ d: m[1], fill: m[2] }),
  );
  if (paths.length === 0) fail("source.svg: glyph group has no <path> children");

  return { viewBoxWidth, viewBoxHeight, backgroundFill, transform, paths };
}

// Android group transform "translate(tx,ty) scale(sx,sy)" -> {translateX,...}
function svgTransformToAndroid(transform) {
  const t = transform.match(/translate\(([^,]+),([^)]+)\)/);
  const s = transform.match(/scale\(([^,]+),([^)]+)\)/);
  if (!t || !s) fail(`source.svg: unsupported glyph transform "${transform}"`);
  return {
    translateX: parseFloat(t[1]),
    translateY: parseFloat(t[2]),
    scaleX: parseFloat(s[1]),
    scaleY: parseFloat(s[2]),
  };
}

// The source SVG viewBox (240x300, portrait) is non-square, but the glyph
// group transform is designed so that once mapped to the Android 108dp square
// adaptive canvas the effective scale becomes uniform (Android compensates for
// the 4:5 viewport by pairing scaleX/scaleY in a 0.8 ratio). iOS has no such
// compensation pass, so if we rasterized the 240x300 viewBox into a 1024x1024
// PNG directly rsvg would stretch it non-uniformly and the glyph would look
// squished. We instead derive the equivalent uniform transform for a square
// 108x108 target viewBox and render that at 1024x1024 (square->square = no
// non-uniform scaling). The math:
//   uniformScale = scaleX * S / viewBoxWidth   (== scaleY * S / viewBoxHeight
//                                              when the source is aspect-compensated)
//   targetTx     = translateX * S / viewBoxWidth
//   targetTy     = translateY * S / viewBoxHeight
// where S is the square target side (we use 108 to mirror Android's dp canvas).
const IOS_SQUARE_SIDE = 108;

// iOS glyph scale multiplier relative to the Android-derived size. Android's
// adaptive-icon safe zone (~66% of 108dp) leaves the glyph small inside an iOS
// squircle (~24% of canvas height at 1.0x). Bump this to give the glyph more
// presence on iOS; the transform is recentered on the canvas center so the
// glyph stays centered at any scale. 1.5x ~36% of canvas height.
const IOS_GLYPH_SCALE = 1.5;

function deriveSquareTransform(parsed) {
  const { viewBoxWidth, viewBoxHeight, transform } = parsed;
  const android = svgTransformToAndroid(transform);
  const scaleX = android.scaleX * (IOS_SQUARE_SIDE / viewBoxWidth);
  const scaleY = android.scaleY * (IOS_SQUARE_SIDE / viewBoxHeight);
  if (Math.abs(scaleX - scaleY) > 1e-4) {
    fail(
      `source.svg: glyph group scale is not aspect-compensated for the ${viewBoxWidth}x${viewBoxHeight} viewBox (sx=${scaleX}, sy=${scaleY}). iOS square render would still distort.`,
    );
  }
  const baseScale = scaleX;
  const baseTx = android.translateX * (IOS_SQUARE_SIDE / viewBoxWidth);
  const baseTy = android.translateY * (IOS_SQUARE_SIDE / viewBoxHeight);
  // Scale the glyph around the canvas center (54,54). Derivation:
  //   current center maps to (baseTx + baseScale*c) = 54  =>  baseScale*c = 54-baseTx
  //   scaled:  newTx + (baseScale*k)*c = 54  =>  newTx = 54 - k*(54-baseTx)
  const k = IOS_GLYPH_SCALE;
  const c = IOS_SQUARE_SIDE / 2;
  return {
    scale: baseScale * k,
    translateX: c - k * (c - baseTx),
    translateY: c - k * (c - baseTy),
  };
}

function buildSquareSvg({ backgroundFill, square, paths, fillOverride }) {
  const pathsXml = paths
    .map(
      (p) =>
        `    <path d="${p.d}" fill="${fillOverride ?? p.fill}"/>`,
    )
    .join("\n");
  return `<?xml version="1.0" encoding="utf-8"?>
<svg xmlns="http://www.w3.org/2000/svg" width="${PNG_SIZE}" height="${PNG_SIZE}" viewBox="0 0 ${IOS_SQUARE_SIDE} ${IOS_SQUARE_SIDE}">
  <rect x="0" y="0" width="${IOS_SQUARE_SIDE}" height="${IOS_SQUARE_SIDE}" fill="${backgroundFill ?? "none"}"/>
  <g transform="translate(${square.translateX},${square.translateY}) scale(${square.scale},${square.scale})">
${pathsXml}
  </g>
</svg>`;
}

function buildFlattenedSvg(parsed, square) {
  return buildSquareSvg({ backgroundFill: parsed.backgroundFill, square, paths: parsed.paths });
}

// Tinted/monochrome silhouette: transparent background + all glyph fills white.
// iOS 18 uses the alpha channel; the white fill is just a carrier for opacity.
function buildSilhouetteSvg(parsed, square) {
  return buildSquareSvg({ backgroundFill: "none", square, paths: parsed.paths, fillOverride: "#FFFFFF" });
}

import { tmpdir } from "node:os";
import { randomBytes } from "node:crypto";

function renderPng(svg, outPath) {
  const tmp = join(tmpdir(), `opencode-app-icon-${randomBytes(4).toString("hex")}.svg`);
  writeFileSync(tmp, svg, "utf8");
  try {
    execFileSync("rsvg-convert", ["-w", PNG_SIZE, "-h", PNG_SIZE, tmp, "-o", outPath], {
      stdio: "pipe",
    });
  } catch (err) {
    fail(`rsvg-convert failed: ${err.stderr?.toString() ?? err.message}`);
  } finally {
    try {
      rmSync(tmp);
    } catch {}
  }
}

function writeIosContentsJson() {
  // iOS 14+ single-size 1024 slots. We provide any (universal) + dark + tinted
  // appearances, all from the same source design. "any" and "dark" use the
  // flattened art (dark-background design works for both luminosity modes).
  const json = {
    images: [
      {
        filename: "icon-1024.png",
        idiom: "universal",
        platform: "ios",
        size: "1024x1024",
      },
      {
        appearances: [{ appearance: "luminosity", value: "dark" }],
        filename: "icon-1024.png",
        idiom: "universal",
        platform: "ios",
        size: "1024x1024",
      },
      {
        appearances: [{ appearance: "luminosity", value: "tinted" }],
        filename: "icon-tinted-1024.png",
        idiom: "universal",
        platform: "ios",
        size: "1024x1024",
      },
    ],
    info: { author: "xcode", version: 1 },
  };
  writeFileSync(
    join(IOS_APPICONSET, "Contents.json"),
    JSON.stringify(json, null, 2) + "\n",
    "utf8",
  );
}

function writeAndroidForeground({ transform, paths }) {
  const { translateX, translateY, scaleX, scaleY } = svgTransformToAndroid(transform);
  const pathsXml = paths
    .map((p) => `    <path\n        android:pathData="${p.d}"\n        android:fillColor="${p.fill}" />`)
    .join("\n");
  const xml = `<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="240"
    android:viewportHeight="300">
  <group android:scaleX="${scaleX}"
      android:scaleY="${scaleY}"
      android:translateX="${translateX}"
      android:translateY="${translateY}">
${pathsXml}
  </group>
</vector>
`;
  const outDir = join(ANDROID_RES, "drawable");
  mkdirSync(outDir, { recursive: true });
  writeFileSync(join(outDir, "ic_launcher_foreground.xml"), xml, "utf8");
}

function writeAndroidBackgroundColor({ backgroundFill }) {
  const xml = `<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">${backgroundFill}</color>
</resources>
`;
  const outDir = join(ANDROID_RES, "values");
  mkdirSync(outDir, { recursive: true });
  writeFileSync(
    join(outDir, "ic_launcher_background.xml"),
    xml,
    "utf8",
  );
}

function writeAndroidAdaptive() {
  const adaptive = `<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background"/>
    <foreground android:drawable="@drawable/ic_launcher_foreground"/>
    <monochrome android:drawable="@drawable/ic_launcher_foreground"/>
</adaptive-icon>
`;
  const outDir = join(ANDROID_RES, "mipmap-anydpi-v26");
  mkdirSync(outDir, { recursive: true });
  writeFileSync(join(outDir, "ic_launcher.xml"), adaptive, "utf8");
  writeFileSync(join(outDir, "ic_launcher_round.xml"), adaptive, "utf8");
}

function main() {
  if (!existsSync(SOURCE_SVG)) fail(`source SVG not found: ${SOURCE_SVG}`);
  ensureRsvg();

  const src = readFileSync(SOURCE_SVG, "utf8");
  const parsed = parseSource(src);
  const square = deriveSquareTransform(parsed);

  mkdirSync(IOS_APPICONSET, { recursive: true });

  // iOS: flattened icon (any + dark) and tinted silhouette, rendered into a
  // square viewBox so the portrait source's design intent is preserved.
  const flattened = buildFlattenedSvg(parsed, square);
  const silhouette = buildSilhouetteSvg(parsed, square);
  renderPng(flattened, join(IOS_APPICONSET, "icon-1024.png"));
  renderPng(silhouette, join(IOS_APPICONSET, "icon-tinted-1024.png"));

  writeIosContentsJson();

  // Android: emit adaptive-icon layers from the same source.
  writeAndroidForeground(parsed);
  writeAndroidBackgroundColor(parsed);
  writeAndroidAdaptive();

  console.log("app-icon: synced iOS AppIcon + Android adaptive icon from app-icon/source.svg");
}

main();