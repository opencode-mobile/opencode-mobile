#!/usr/bin/env node
// Rasterize app-icon/interlock.svg into store listing PNGs.
// Does NOT touch on-device launcher icons (those come from app-icon/source.svg).
// See docs/design-system/app-icon.md.

import { execFileSync } from "node:child_process";
import { existsSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const REPO_ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const STORE_SVG = join(REPO_ROOT, "app-icon/interlock.svg");
const PLAY_PNG = join(
  REPO_ROOT,
  "android/app/src/main/ic_launcher-playstore.png",
);
const PLAY_LISTING_ICON = join(REPO_ROOT, "play-listing/icon-512.png");
const STORE_1024 = join(REPO_ROOT, "app-icon/store-1024.png");

function fail(msg) {
  console.error(`app-icon:store: ${msg}`);
  process.exit(1);
}

function requireRsvg() {
  try {
    execFileSync("rsvg-convert", ["--version"], { stdio: "ignore" });
  } catch {
    fail(
      "rsvg-convert not found. Install librsvg (brew install librsvg / apt install librsvg2-bin).",
    );
  }
}

function render(size, outPath) {
  execFileSync(
    "rsvg-convert",
    ["-w", String(size), "-h", String(size), STORE_SVG, "-o", outPath],
    { stdio: "inherit" },
  );
}

if (!existsSync(STORE_SVG)) fail(`missing ${STORE_SVG}`);
requireRsvg();
render(512, PLAY_PNG);
render(512, PLAY_LISTING_ICON);
render(1024, STORE_1024);
console.log(
  "app-icon:store: wrote Play 512 PNGs + app-icon/store-1024.png from app-icon/interlock.svg",
);
console.log(
  "app-icon:store: launcher icons unchanged (still from app-icon/source.svg)",
);
