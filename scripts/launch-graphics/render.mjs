#!/usr/bin/env node
/**
 * `npm run graphics:render` — render launch graphic specs to PNG with headless Chrome (puppeteer-core).
 *
 *   --spec <file|preset>   repeatable; a JSON file or a name from scripts/launch-graphics/presets/
 *   --all-presets          every committed preset
 *   --formats <list>       1600x900,1500x500 | all | posts | banners (default: each spec's format)
 *   --modes <list>         dark,light (default: each spec's mode)
 *   --set key=value        repeatable dot-path override, e.g. --set copy.headline="Ship it." --set composition=pair
 *   --out <dir>            default <repo>/launch-graphics/
 *   --scale <n>            device scale factor, default 2
 *   --shots <dir>          screenshots: store-screenshots layout or flat <device>_<screen>_<mode>.png (default <repo>/store-screenshots)
 *   --list [--json]        compositions, moments, formats, devices, presets
 * Chrome: /Applications/Google Chrome.app/... or CHROME_PATH.
 */
import { existsSync, mkdirSync, readFileSync } from "node:fs";
import { basename, join, resolve } from "node:path";
import { parseArgs } from "node:util";
import { DEVICES } from "./renderer/devices.js";
import { MOMENTS, SCREENS } from "./renderer/moments.js";
import { COMPOSITIONS, FORMATS, SAFE_AREAS, SpecError, expandFormats, normalize, parseFormat, parseValue, setPath } from "./renderer/spec.js";
import { DEFAULT_SHOTS_DIR, PRESETS_DIR, REPO_ROOT, listPresets, startServer } from "./server.mjs";

const DEFAULT_CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";

function fail(message) {
  console.error(message);
  process.exit(1);
}

let args;
try {
  ({ values: args } = parseArgs({
    options: {
      spec: { type: "string", multiple: true, default: [] },
      "all-presets": { type: "boolean", default: false },
      formats: { type: "string" },
      modes: { type: "string" },
      set: { type: "string", multiple: true, default: [] },
      out: { type: "string", default: join(REPO_ROOT, "launch-graphics") },
      scale: { type: "string", default: "2" },
      shots: { type: "string", default: DEFAULT_SHOTS_DIR },
      list: { type: "boolean", default: false },
      json: { type: "boolean", default: false },
      help: { type: "boolean", short: "h", default: false },
    },
  }));
} catch (err) {
  fail(`${err.message}\nRun with --help for usage.`);
}

if (args.help) {
  console.log(readFileSync(new URL(import.meta.url), "utf8").match(/\/\*\*([\s\S]*?)\*\//)[1].replace(/^ \* ?/gm, ""));
  process.exit(0);
}

if (args.list) {
  const catalog = {
    compositions: COMPOSITIONS,
    moments: Object.entries(MOMENTS).map(([id, m]) => ({ id, name: m.name, headline: m.copy.headline.replace(/\n/g, " ") })),
    formats: [...FORMATS.map((f) => ({ ...f, safeArea: SAFE_AREAS[f.key]?.label || null })), { key: "<W>x<H>", group: "custom", ratio: "any", use: "custom size, 300–4000 per side" }],
    devices: Object.entries(DEVICES).map(([id, d]) => ({ id, name: d.name, screenshots: d.dir })),
    screens: SCREENS,
    presets: listPresets().map((p) => ({ id: p.id, format: p.format, composition: p.composition, moment: p.moment, description: p.description })),
  };
  if (args.json) console.log(JSON.stringify(catalog, null, 2));
  else {
    const pad = (s, n) => String(s).padEnd(n);
    console.log("Compositions (layout only):");
    for (const c of catalog.compositions) console.log(`  ${pad(c.id, 10)} ${pad(c.name, 16)} ${c.desc}`);
    console.log("\nMoments (content only: screenshots + copy):");
    for (const m of catalog.moments) console.log(`  ${pad(m.id, 10)} ${m.headline}`);
    console.log("\nFormats:");
    for (const f of catalog.formats) console.log(`  ${pad(f.key, 10)} ${pad(f.group, 7)} ${pad(f.ratio, 7)} ${f.use}${f.safeArea ? `  (safe area: ${f.safeArea})` : ""}`);
    console.log("\nDevices:");
    for (const d of catalog.devices) console.log(`  ${pad(d.id, 10)} ${pad(d.name, 16)} store-screenshots/${d.screenshots}/<mode>/<screen>.png`);
    console.log(`\nScreens: ${Object.keys(SCREENS).join(", ")}`);
    console.log("\nPresets (scripts/launch-graphics/presets):");
    for (const p of catalog.presets) console.log(`  ${pad(p.id, 20)} ${pad(p.format, 10)} ${pad(p.composition, 9)} ${pad(p.moment, 9)} ${p.description}`);
  }
  process.exit(0);
}

/* ---------------- collect jobs ---------------- */
function loadSpec(ref) {
  const file = existsSync(ref) ? resolve(ref) : join(PRESETS_DIR, ref.endsWith(".json") ? ref : `${ref}.json`);
  if (!existsSync(file)) fail(`Spec not found: "${ref}" is neither a file nor a preset (see --list).`);
  try {
    return { label: basename(file, ".json"), spec: JSON.parse(readFileSync(file, "utf8")) };
  } catch (err) {
    fail(`${file}: ${err.message}`);
  }
}
const sources = [...args.spec.map(loadSpec)];
if (args["all-presets"]) for (const p of listPresets()) sources.push(loadSpec(p.id));
if (!sources.length) fail("Nothing to render: pass --spec <file|preset> or --all-presets (see --list).");

const jobs = [];
try {
  const sets = args.set.map((s) => {
    const i = s.indexOf("=");
    if (i < 1) throw new SpecError([`--set needs key=value (got "${s}")`]);
    return [s.slice(0, i), parseValue(s.slice(i + 1))];
  });
  const formats = args.formats ? expandFormats(args.formats) : null;
  const modes = args.modes ? args.modes.split(",").map((m) => m.trim()).filter(Boolean) : null;
  for (const { label, spec } of sources) {
    for (const [k, v] of sets) setPath(spec, k, v);
    let base;
    try {
      base = normalize(spec);
    } catch (err) {
      throw new SpecError((err.messages || [err.message]).map((m) => `${label}: ${m}`));
    }
    for (const format of formats || [base.format]) {
      for (const mode of modes || [base.mode]) jobs.push({ label, spec: normalize({ ...spec, format, mode }) });
    }
  }
} catch (err) {
  fail(err.message);
}

/* ---------------- render ---------------- */
const chromePath = process.env.CHROME_PATH || DEFAULT_CHROME;
if (!existsSync(chromePath)) fail(`Chrome not found at ${chromePath}. Install Google Chrome or set CHROME_PATH.`);
const shotsDir = resolve(args.shots);
if (!existsSync(shotsDir)) console.warn(`warning: no screenshots at ${shotsDir} — run \`npm run store:screenshots\` or pass --shots <dir>.`);
const scale = Number(args.scale);
if (!(scale > 0 && scale <= 4)) fail("--scale must be a number between 0 and 4");
const outDir = resolve(args.out);
mkdirSync(outDir, { recursive: true });

const { default: puppeteer } = await import("puppeteer-core");
const server = await startServer({ port: 0, shotsDir });
const browser = await puppeteer.launch({ executablePath: chromePath, headless: true, args: ["--hide-scrollbars", "--font-render-hinting=none"] });
let failed = 0;
try {
  const page = await browser.newPage();
  page.on("pageerror", (err) => console.error(`page error: ${err.message}`));
  await page.setViewport({ width: 1600, height: 900, deviceScaleFactor: scale });
  await page.goto(`${server.url}/?raw=1`, { waitUntil: "load" });
  await page.waitForFunction(() => window.launchGraphics?.render, { timeout: 30_000 });
  for (const { label, spec } of jobs) {
    const { w, h } = parseFormat(spec.format);
    await page.setViewport({ width: w, height: h, deviceScaleFactor: scale });
    const result = await page.evaluate((s) => window.launchGraphics.render(s), spec);
    const file = join(outDir, `${label}-${spec.format}-${spec.mode}.png`);
    const canvas = await page.$(".canvas");
    await canvas.screenshot({ path: file });
    console.log(file);
    for (const n of result.notes) console.log(`  note: ${n}`);
    if (result.missing.length) {
      failed++;
      console.warn(`  warning: ${result.missing.length} screenshot(s) missing, e.g. ${result.missing[0]}`);
    }
  }
} finally {
  await browser.close();
  await server.close();
}
if (failed) console.warn(`${failed} render(s) had missing screenshots.`);
