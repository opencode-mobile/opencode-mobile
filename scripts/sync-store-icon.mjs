#!/usr/bin/env node
// Rasterize the flattened “d” from app-icon/source.svg into store listing PNGs.
// Play and App Store need opaque rasters; they cannot use Liquid Glass or
// adaptive layers. Does not touch on-device launcher outputs.
// See docs/design-system/app-icon.md and app-icon/store/README.md.
import { copyFileSync, existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { tmpdir } from "node:os";
import { randomBytes } from "node:crypto";
import {
  SOURCE_SVG,
  buildFlattenedSvg,
  deriveSquareTransform,
  ensureRsvg,
  parseSource,
  renderPng,
} from "./lib/app-icon-source.mjs";
import { assertOpaque, decodePng, encodePng, inspectPng } from "./lib/png-bytes.mjs";

const REPO_ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const STORE_DIR = join(REPO_ROOT, "app-icon/store");
const PLAY_UPLOAD = join(STORE_DIR, "google-play-512.png");
const APP_STORE_UPLOAD = join(STORE_DIR, "app-store-1024.png");
const PLAY_PNG = join(REPO_ROOT, "android/app/src/main/ic_launcher-playstore.png");
const PLAY_LISTING_ICON = join(REPO_ROOT, "play-listing/icon-512.png");
const STORE_1024 = join(REPO_ROOT, "app-icon/store-1024.png");
const PLAY_MAX_BYTES = 1024 * 1024;

function fail(msg) {
  console.error(`app-icon:store: ${msg}`);
  process.exit(1);
}

function rasterize(svg, size) {
  const tmp = join(tmpdir(), `opencode-store-icon-${randomBytes(4).toString("hex")}.png`);
  try {
    renderPng(svg, tmp, size);
    return readFileSync(tmp);
  } finally {
    try {
      rmSync(tmp);
    } catch {
      // ignore
    }
  }
}

function writeStorePng(outPath, rgba, width, height, { alpha }) {
  assertOpaque(rgba);
  const png = encodePng(rgba, width, height, { alpha });
  writeFileSync(outPath, png);
  return inspectPng(png);
}

function report(label, info, extra = "") {
  const kind = info.alpha ? "RGBA-32" : "RGB-24";
  const kb = (info.bytes / 1024).toFixed(1);
  console.log(
    `app-icon:store: ${label}: ${info.width}×${info.height} ${kind} sRGB ${kb}KB${extra}`,
  );
}

try {
  if (!existsSync(SOURCE_SVG)) fail(`missing ${SOURCE_SVG}`);
  ensureRsvg();

  const parsed = parseSource(readFileSync(SOURCE_SVG, "utf8"));
  const square = deriveSquareTransform(parsed);
  const flattened = buildFlattenedSvg(parsed, square);

  mkdirSync(STORE_DIR, { recursive: true });

  const playDecoded = decodePng(rasterize(flattened, 512));
  if (playDecoded.width !== 512 || playDecoded.height !== 512) {
    fail(`Play raster is ${playDecoded.width}×${playDecoded.height}, expected 512×512`);
  }
  const playInfo = writeStorePng(PLAY_UPLOAD, playDecoded.rgba, 512, 512, { alpha: true });
  if (playInfo.bytes > PLAY_MAX_BYTES) {
    fail(`Play icon is ${(playInfo.bytes / 1024).toFixed(1)}KB; Play limit is 1024KB`);
  }
  copyFileSync(PLAY_UPLOAD, PLAY_PNG);
  copyFileSync(PLAY_UPLOAD, PLAY_LISTING_ICON);

  const storeDecoded = decodePng(rasterize(flattened, 1024));
  if (storeDecoded.width !== 1024 || storeDecoded.height !== 1024) {
    fail(`App Store raster is ${storeDecoded.width}×${storeDecoded.height}, expected 1024×1024`);
  }
  const storeInfo = writeStorePng(APP_STORE_UPLOAD, storeDecoded.rgba, 1024, 1024, {
    alpha: false,
  });
  copyFileSync(APP_STORE_UPLOAD, STORE_1024);

  report("Google Play", playInfo, " → Play Console high-res icon (square, no mask/shadow)");
  report("App Store", storeInfo, " → App Store Connect / promotional 1024 (no alpha)");
  console.log("app-icon:store: also wrote play-listing/icon-512.png, ic_launcher-playstore.png, app-icon/store-1024.png");
  console.log("app-icon:store: on-device launcher icons unchanged (still from npm run app-icon:sync)");
} catch (err) {
  fail(err.message);
}
