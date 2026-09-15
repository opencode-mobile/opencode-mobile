#!/usr/bin/env node
/**
 * Write a self-contained HTML gallery for store-screenshot captures.
 *
 * Usage:
 *   node scripts/store-screenshots/write-gallery.mjs [--out store-screenshots]
 */
import { existsSync, mkdirSync, readFileSync, statSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { IOS_EXPECTED, IOS_OUTPUT_TARGET, readPngSize } from "./png.mjs";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const AVDS_PATH = join(dirname(fileURLToPath(import.meta.url)), "avds.json");
const DEFAULT_OUT = "store-screenshots";
const APPEARANCES = ["light", "dark"];
const SCENES = ["01-home", "02-chat-code", "03-chat-permission", "04-changes", "05-settings"];

export function parseGalleryArgs(argv = process.argv.slice(2)) {
  let out = DEFAULT_OUT;
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === "--out") {
      out = argv[++i];
      if (!out) throw new Error("--out requires a directory");
    } else if (arg === "--help" || arg === "-h") {
      console.log(`Write an HTML gallery for store-screenshot captures.

Usage:
  node scripts/store-screenshots/write-gallery.mjs [--out store-screenshots]
`);
      process.exit(0);
    } else {
      throw new Error(`Unknown argument: ${arg}`);
    }
  }
  return { outDir: resolve(ROOT, out) };
}

function escapeHtml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");
}

function formatMtime(mtime) {
  return mtime.toISOString().replace("T", " ").replace(/\.\d{3}Z$/, " UTC");
}

function loadTargetSpecs() {
  const avds = JSON.parse(readFileSync(AVDS_PATH, "utf8"));
  return [
    { id: IOS_OUTPUT_TARGET, expected: IOS_EXPECTED },
    ...Object.entries(avds).map(([id, spec]) => ({
      id,
      expected: { width: spec.width, height: spec.height },
    })),
  ];
}

function scanSlot(outDir, targetId, appearance, scene) {
  const fileName = `${scene}.png`;
  const rel = `${targetId}/${appearance}/${fileName}`;
  const abs = join(outDir, targetId, appearance, fileName);
  if (!existsSync(abs)) {
    return { scene, rel, present: false };
  }
  const size = readPngSize(abs);
  const mtime = formatMtime(statSync(abs).mtime);
  return {
    scene,
    rel,
    present: true,
    width: size?.width ?? null,
    height: size?.height ?? null,
    mtime,
  };
}

function collectGallery(outDir) {
  const targets = loadTargetSpecs().map((spec) => {
    const appearances = APPEARANCES.map((appearance) => ({
      appearance,
      slots: SCENES.map((scene) => scanSlot(outDir, spec.id, appearance, scene)),
    }));
    const slots = appearances.flatMap((row) => row.slots);
    const present = slots.filter((slot) => slot.present).length;
    const missing = slots.length - present;
    return {
      ...spec,
      appearances,
      present,
      missing,
      complete: missing === 0,
    };
  });
  const presentCount = targets.reduce((sum, target) => sum + target.present, 0);
  const slotCount = targets.reduce((sum, target) => sum + target.present + target.missing, 0);
  return { targets, presentCount, slotCount };
}

function sizeBadge(slot, expected) {
  if (!slot.present) {
    return `<span class="badge missing">missing</span>`;
  }
  if (slot.width !== expected.width || slot.height !== expected.height) {
    return `<span class="badge mismatch">size mismatch</span>`;
  }
  return "";
}

function renderCard(slot, expected, targetId, appearance) {
  const aspect = expected.width / expected.height;
  const sizeLabel = slot.present && slot.width && slot.height ? `${slot.width}×${slot.height}` : "—";
  const mtime = slot.present ? `<div class="mtime">${escapeHtml(slot.mtime)}</div>` : "";
  const media = slot.present
    ? `<button type="button" class="thumb" data-src="${escapeHtml(slot.rel)}" data-target="${escapeHtml(targetId)}" data-appearance="${escapeHtml(appearance)}" data-scene="${escapeHtml(slot.scene)}" aria-label="Open ${escapeHtml(slot.scene)}">
        <img src="${escapeHtml(slot.rel)}" alt="${escapeHtml(slot.scene)}" height="520" />
      </button>`
    : `<div class="thumb missing-slot" aria-hidden="true"></div>`;
  return `<article class="card" style="--aspect:${aspect}" data-scene="${escapeHtml(slot.scene)}">
    ${media}
    <div class="meta">
      <div class="caption">${escapeHtml(slot.scene)}</div>
      <div class="size">${escapeHtml(sizeLabel)}</div>
      ${mtime}
      ${sizeBadge(slot, expected)}
    </div>
  </article>`;
}

function renderTarget(target) {
  const expected = `${target.expected.width}×${target.expected.height}`;
  const pill = target.complete
    ? `<span class="pill ok">all files present</span>`
    : `<span class="pill warn">${target.missing} missing</span>`;
  const rows = target.appearances
    .map((row) => {
      const cards = row.slots
        .map((slot) => renderCard(slot, target.expected, target.id, row.appearance))
        .join("\n");
      return `<div class="appearance-row" data-appearance="${row.appearance}">
        <h3>${row.appearance}</h3>
        <div class="strip">${cards}</div>
      </div>`;
    })
    .join("\n");
  return `<section class="target" data-target="${escapeHtml(target.id)}" id="${escapeHtml(target.id)}">
    <header class="target-head">
      <div>
        <h2>${escapeHtml(target.id)}</h2>
        <p class="expected">expected ${escapeHtml(expected)}</p>
      </div>
      ${pill}
    </header>
    ${rows}
  </section>`;
}

function renderHtml(data) {
  const generated = formatMtime(new Date());
  const sections = data.targets.map(renderTarget).join("\n");
  return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>Store screenshots</title>
  <style>
    :root {
      color-scheme: dark;
      --bg: #111214;
      --panel: #1a1c1f;
      --line: #2a2d32;
      --text: #ececec;
      --muted: #9a9da4;
      --ok: #7dcea0;
      --warn: #e07a5f;
      --chip: #26292e;
    }
    * { box-sizing: border-box; }
    html, body { margin: 0; background: var(--bg); color: var(--text); font: 14px/1.45 ui-sans-serif, system-ui, sans-serif; }
    body { padding: 28px 24px 48px; }
    header.page { display: flex; flex-wrap: wrap; gap: 12px 24px; align-items: baseline; margin-bottom: 20px; }
    header.page h1 { margin: 0; font-size: 28px; font-weight: 600; letter-spacing: -0.03em; }
    header.page p { margin: 0; color: var(--muted); }
    .controls { display: flex; flex-wrap: wrap; gap: 16px 28px; margin-bottom: 28px; padding: 14px 16px; background: var(--panel); border: 1px solid var(--line); border-radius: 12px; }
    .control { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
    .control span { color: var(--muted); margin-right: 4px; }
    .control button {
      appearance: none; border: 1px solid var(--line); background: var(--chip); color: var(--text);
      padding: 6px 12px; border-radius: 999px; cursor: pointer;
    }
    .control button[aria-pressed="true"] { border-color: #d0d0d0; background: #2f333a; }
    .target { margin-bottom: 36px; }
    .target-head { display: flex; justify-content: space-between; align-items: flex-end; gap: 16px; margin-bottom: 12px; }
    .target-head h2 { margin: 0; font-size: 20px; }
    .expected { margin: 4px 0 0; color: var(--muted); }
    .pill { padding: 4px 10px; border-radius: 999px; font-size: 12px; border: 1px solid var(--line); }
    .pill.ok { color: var(--ok); }
    .pill.warn { color: var(--warn); }
    .appearance-row { margin-bottom: 18px; }
    .appearance-row h3 { margin: 0 0 8px; text-transform: capitalize; color: var(--muted); font-size: 13px; font-weight: 600; }
    .strip { display: flex; gap: 14px; overflow-x: auto; padding-bottom: 8px; }
    .card { flex: 0 0 auto; width: calc(520px * var(--aspect)); }
    .thumb {
      display: block; width: 100%; height: 520px; padding: 0; border: 1px solid var(--line);
      background: #0c0d0f; border-radius: 10px; overflow: hidden; cursor: zoom-in;
    }
    .thumb img { display: block; height: 520px; width: auto; max-width: none; }
    .missing-slot { cursor: default; background: repeating-linear-gradient(-45deg, #16181b, #16181b 8px, #1c1f23 8px, #1c1f23 16px); }
    .meta { margin-top: 8px; }
    .caption { font-weight: 600; }
    .size, .mtime { color: var(--muted); font-size: 12px; }
    .badge { display: inline-block; margin-top: 6px; padding: 2px 8px; border-radius: 999px; font-size: 11px; background: #3a1d1a; color: #ffb4a8; }
    footer.legend { margin-top: 32px; color: var(--muted); font-size: 12px; }
    #lightbox { position: fixed; inset: 0; display: none; align-items: center; justify-content: center; background: rgba(0,0,0,0.86); z-index: 20; padding: 24px; }
    #lightbox.open { display: flex; }
    #lightbox img { max-width: calc(100vw - 48px); max-height: calc(100vh - 48px); box-shadow: 0 12px 48px rgba(0,0,0,0.45); }
    #lightbox .hint { position: absolute; bottom: 16px; color: #bbb; font-size: 12px; }
    body.hide-light .appearance-row[data-appearance="light"] { display: none; }
    body.hide-dark .appearance-row[data-appearance="dark"] { display: none; }
    body.filter-ios-6-9 .target:not([data-target="ios-6.9"]),
    body.filter-android-phone .target:not([data-target="android-phone"]),
    body.filter-android-tab7 .target:not([data-target="android-tab7"]),
    body.filter-android-tab10 .target:not([data-target="android-tab10"]) { display: none; }
  </style>
</head>
<body>
  <header class="page">
    <h1>Store screenshots</h1>
    <p>${escapeHtml(generated)} · ${data.presentCount} files</p>
  </header>
  <div class="controls">
    <div class="control" data-kind="appearance">
      <span>Appearance</span>
      <button type="button" data-appearance="light">Light</button>
      <button type="button" data-appearance="dark">Dark</button>
      <button type="button" data-appearance="both" aria-pressed="true">Both</button>
    </div>
    <div class="control" data-kind="target">
      <span>Target</span>
      <button type="button" data-target="all" aria-pressed="true">All</button>
      <button type="button" data-target="ios-6.9">ios-6.9</button>
      <button type="button" data-target="android-phone">android-phone</button>
      <button type="button" data-target="android-tab7">android-tab7</button>
      <button type="button" data-target="android-tab10">android-tab10</button>
    </div>
  </div>
  ${sections}
  <footer class="legend">Layout: &lt;out&gt;/&lt;target&gt;/&lt;appearance&gt;/NN-scene.png · scenes 01-home, 02-chat-code, 03-chat-permission, 04-changes, 05-settings · click an image for natural size; ←/→ cycle the same target and appearance; Esc closes.</footer>
  <div id="lightbox" hidden>
    <img alt="" />
    <div class="hint">← → cycle · Esc close</div>
  </div>
  <script>
    (function () {
      var body = document.body;
      var appearanceButtons = document.querySelectorAll('[data-kind="appearance"] button');
      var targetButtons = document.querySelectorAll('[data-kind="target"] button');
      var lightbox = document.getElementById("lightbox");
      var lightboxImg = lightbox.querySelector("img");
      var queue = [];
      var index = -1;

      function setPressed(buttons, attr, value) {
        buttons.forEach(function (button) {
          button.setAttribute("aria-pressed", button.getAttribute(attr) === value ? "true" : "false");
        });
      }

      appearanceButtons.forEach(function (button) {
        button.addEventListener("click", function () {
          var value = button.getAttribute("data-appearance");
          body.classList.toggle("hide-light", value === "dark");
          body.classList.toggle("hide-dark", value === "light");
          setPressed(appearanceButtons, "data-appearance", value);
        });
      });

      targetButtons.forEach(function (button) {
        button.addEventListener("click", function () {
          var value = button.getAttribute("data-target");
          ["filter-ios-6-9", "filter-android-phone", "filter-android-tab7", "filter-android-tab10"].forEach(function (cls) {
            body.classList.remove(cls);
          });
          if (value !== "all") {
            body.classList.add("filter-" + value.replace(/\\./g, "-"));
          }
          setPressed(targetButtons, "data-target", value);
        });
      });

      function visibleThumbs() {
        return Array.prototype.slice.call(document.querySelectorAll("button.thumb")).filter(function (thumb) {
          var row = thumb.closest(".appearance-row");
          var section = thumb.closest(".target");
          return row && section && row.offsetParent !== null && section.offsetParent !== null;
        });
      }

      function openAt(i) {
        if (!queue.length) return;
        index = (i + queue.length) % queue.length;
        lightboxImg.src = queue[index].getAttribute("data-src");
        lightboxImg.alt = queue[index].getAttribute("data-scene") || "";
        lightbox.hidden = false;
        lightbox.classList.add("open");
      }

      function closeLightbox() {
        lightbox.classList.remove("open");
        lightbox.hidden = true;
        lightboxImg.removeAttribute("src");
        index = -1;
      }

      document.addEventListener("click", function (event) {
        var thumb = event.target.closest("button.thumb");
        if (!thumb) return;
        queue = visibleThumbs().filter(function (el) {
          return el.getAttribute("data-target") === thumb.getAttribute("data-target") &&
            el.getAttribute("data-appearance") === thumb.getAttribute("data-appearance");
        });
        openAt(queue.indexOf(thumb));
      });

      lightbox.addEventListener("click", function (event) {
        if (event.target === lightbox) closeLightbox();
      });

      document.addEventListener("keydown", function (event) {
        if (!lightbox.classList.contains("open")) return;
        if (event.key === "Escape") closeLightbox();
        if (event.key === "ArrowLeft") openAt(index - 1);
        if (event.key === "ArrowRight") openAt(index + 1);
      });
    })();
  </script>
</body>
</html>
`;
}

export function writeGallery(outDir) {
  const absOut = resolve(outDir);
  mkdirSync(absOut, { recursive: true });
  const data = collectGallery(absOut);
  const htmlPath = join(absOut, "index.html");
  writeFileSync(htmlPath, renderHtml(data));
  return htmlPath;
}

function main() {
  const { outDir } = parseGalleryArgs();
  const htmlPath = writeGallery(outDir);
  console.log(`gallery: ${htmlPath}`);
}

if (import.meta.url === `file://${process.argv[1]}` || process.argv[1] && fileURLToPath(import.meta.url) === resolve(process.argv[1])) {
  try {
    main();
  } catch (error) {
    console.error(error.message ?? error);
    process.exit(1);
  }
}
