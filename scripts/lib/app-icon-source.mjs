// Shared parse + square flatten for app-icon/source.svg.
// Used by the launcher generator and the store listing rasterizer.
import { execFileSync } from "node:child_process";
import { randomBytes } from "node:crypto";
import { rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { REPO_ROOT } from "./utils.mjs";

export const SOURCE_SVG = join(REPO_ROOT, "app-icon/source.svg");
export const PNG_SIZE = 1024;
export const GLASS_FILL = "#F1ECEC";

const IOS_SQUARE_SIDE = 108;
// Source glyph is inset on the Android 108dp canvas (currently 0.88) so
// circular launcher masks keep a black margin. Hold iOS / store rasters at
// the pre-inset on-canvas size (original 1.5× of the un-inset glyph).
const ANDROID_CANVAS_INSET = 0.88;
const IOS_GLYPH_SCALE = 1.5 / ANDROID_CANVAS_INSET;

export function parseSource(src) {
  const viewBoxMatch = src.match(/<svg[^>]*\sviewBox="0\s+0\s+(\d+)\s+(\d+)"/);
  if (!viewBoxMatch) throw new Error('source.svg: missing <svg viewBox="0 0 W H">');
  const viewBoxWidth = parseInt(viewBoxMatch[1], 10);
  const viewBoxHeight = parseInt(viewBoxMatch[2], 10);
  if (viewBoxWidth <= 0 || viewBoxHeight <= 0) throw new Error("source.svg: invalid viewBox");

  const bgMatch = src.match(
    /<!--\s*@opencode\/background\s*-->\s*<rect\s+([^>]*)\/>/,
  );
  if (!bgMatch) throw new Error("source.svg: missing <!-- @opencode/background --> <rect .../>");
  const fillMatch = bgMatch[1].match(/fill="([^"]+)"/);
  if (!fillMatch) throw new Error('source.svg: background <rect> must declare fill="..."');
  const backgroundFill = fillMatch[1];

  const glyphMatch = src.match(
    /<!--\s*@opencode\/glyph\s*-->\s*(<g[\s\S]*?<\/g>)/,
  );
  if (!glyphMatch) throw new Error("source.svg: missing <!-- @opencode/glyph --> <g>...</g>");
  const glyphGroup = glyphMatch[1];

  const transformMatch = glyphGroup.match(/transform="([^"]*)"/);
  if (!transformMatch) throw new Error('source.svg: glyph <g> must declare transform="..."');
  const transform = transformMatch[1];

  const paths = [...glyphGroup.matchAll(/<path\s+d="([^"]+)"\s+fill="([^"]+)"/g)].map(
    (m) => ({ d: m[1], fill: m[2] }),
  );
  if (paths.length === 0) throw new Error("source.svg: glyph group has no <path> children");

  return { viewBoxWidth, viewBoxHeight, backgroundFill, transform, paths };
}

export function svgTransformToAndroid(transform) {
  const t = transform.match(/translate\(([^,]+),([^)]+)\)/);
  const s = transform.match(/scale\(([^,]+),([^)]+)\)/);
  if (!t || !s) throw new Error(`source.svg: unsupported glyph transform "${transform}"`);
  return {
    translateX: parseFloat(t[1]),
    translateY: parseFloat(t[2]),
    scaleX: parseFloat(s[1]),
    scaleY: parseFloat(s[2]),
  };
}

export function deriveSquareTransform(parsed) {
  const { viewBoxWidth, viewBoxHeight, transform } = parsed;
  const android = svgTransformToAndroid(transform);
  if (Math.abs(android.scaleX - ANDROID_CANVAS_INSET) > 1e-4) {
    throw new Error(
      `source.svg: glyph scaleX ${android.scaleX} must match ANDROID_CANVAS_INSET ${ANDROID_CANVAS_INSET} (iOS compensation).`,
    );
  }
  const scaleX = android.scaleX * (IOS_SQUARE_SIDE / viewBoxWidth);
  const scaleY = android.scaleY * (IOS_SQUARE_SIDE / viewBoxHeight);
  if (Math.abs(scaleX - scaleY) > 1e-4) {
    throw new Error(
      `source.svg: glyph group scale is not aspect-compensated for the ${viewBoxWidth}x${viewBoxHeight} viewBox (sx=${scaleX}, sy=${scaleY}). iOS square render would still distort.`,
    );
  }
  const baseScale = scaleX;
  const baseTx = android.translateX * (IOS_SQUARE_SIDE / viewBoxWidth);
  const baseTy = android.translateY * (IOS_SQUARE_SIDE / viewBoxHeight);
  const k = IOS_GLYPH_SCALE;
  const c = IOS_SQUARE_SIDE / 2;
  return {
    scale: baseScale * k,
    translateX: c - k * (c - baseTx),
    translateY: c - k * (c - baseTy),
  };
}

export function buildSquareSvg({ backgroundFill, square, paths, fillOverride }) {
  const pathsXml = paths
    .map((p) => `    <path d="${p.d}" fill="${fillOverride ?? p.fill}"/>`)
    .join("\n");
  return `<?xml version="1.0" encoding="utf-8"?>
<svg xmlns="http://www.w3.org/2000/svg" width="${PNG_SIZE}" height="${PNG_SIZE}" viewBox="0 0 ${IOS_SQUARE_SIDE} ${IOS_SQUARE_SIDE}">
  <rect x="0" y="0" width="${IOS_SQUARE_SIDE}" height="${IOS_SQUARE_SIDE}" fill="${backgroundFill ?? "none"}"/>
  <g transform="translate(${square.translateX},${square.translateY}) scale(${square.scale},${square.scale})">
${pathsXml}
  </g>
</svg>`;
}

export function buildFlattenedSvg(parsed, square) {
  return buildSquareSvg({
    backgroundFill: parsed.backgroundFill,
    square,
    paths: parsed.paths,
  });
}

export function buildSilhouetteSvg(parsed, square) {
  return buildSquareSvg({
    backgroundFill: "none",
    square,
    paths: parsed.paths,
    fillOverride: "#FFFFFF",
  });
}

export function ensureRsvg() {
  try {
    execFileSync("rsvg-convert", ["--version"], { stdio: "ignore" });
  } catch {
    throw new Error(
      "rsvg-convert not found. Install librsvg: `brew install librsvg` (macOS) or `apt install librsvg2-bin` (Linux).",
    );
  }
}

export function renderPng(svg, outPath, size = PNG_SIZE) {
  const tmp = join(tmpdir(), `opencode-app-icon-${randomBytes(4).toString("hex")}.svg`);
  writeFileSync(tmp, svg, "utf8");
  try {
    execFileSync("rsvg-convert", ["-w", String(size), "-h", String(size), tmp, "-o", outPath], {
      stdio: "pipe",
    });
  } catch (err) {
    throw new Error(`rsvg-convert failed: ${err.stderr?.toString() ?? err.message}`);
  } finally {
    try {
      rmSync(tmp);
    } catch {
      // ignore
    }
  }
}
