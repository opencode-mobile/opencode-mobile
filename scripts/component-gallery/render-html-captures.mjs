#!/usr/bin/env node
// Renders the HTML replica of every captured state at the native capture's width and pixel scale,
// into captures/html-<platform>/, so the Native tab can swipe between HTML and native.
// Needs Google Chrome (or set CHROME_PATH) and at least one native manifest.
import { existsSync, mkdirSync, readFileSync, writeFileSync, createReadStream, statSync } from "node:fs";
import { createServer } from "node:http";
import { createRequire } from "node:module";
import { dirname, extname, join, normalize, sep } from "node:path";
import { fileURLToPath } from "node:url";
import { execFileSync } from "node:child_process";

const here = dirname(fileURLToPath(import.meta.url));
const require = createRequire(join(here, "../../package.json"));
const puppeteer = require("puppeteer-core");
const chrome = process.env.CHROME_PATH ?? "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
if (!existsSync(chrome)) { console.error(`Chrome not found at ${chrome}. Set CHROME_PATH.`); process.exit(1); }
const contractFile = join(here, "captures/contract.json");
if (!existsSync(contractFile)) { console.error("captures/contract.json is missing. Run export-capture-contract.mjs."); process.exit(1); }

execFileSync(process.execPath, [join(here, "build.mjs")], { stdio: "ignore" });
const types = { ".html": "text/html", ".js": "text/javascript", ".css": "text/css", ".svg": "image/svg+xml" };
const server = createServer((req, res) => {
  let file;
  try { file = normalize(join(here, decodeURIComponent(new URL(req.url, "http://localhost").pathname))); } catch { res.writeHead(400); res.end(); return; }
  if (!file.startsWith(here + sep) || !existsSync(file) || statSync(file).isDirectory()) { res.writeHead(404); res.end(); return; }
  res.writeHead(200, { "content-type": types[extname(file)] ?? "application/octet-stream" });
  createReadStream(file).pipe(res);
});
await new Promise((ok) => server.listen(0, "127.0.0.1", ok));
const contract = JSON.parse(readFileSync(contractFile, "utf8"));
const browser = await puppeteer.launch({ headless: "new", executablePath: chrome });
try {
  for (const platform of ["ios", "android"]) {
    const manifestFile = join(here, `captures/${platform}/manifest.json`);
    if (!existsSync(manifestFile)) { console.log(`${platform}: no native manifest, skipped`); continue; }
    const native = JSON.parse(readFileSync(manifestFile, "utf8"));
    const width = native.captures[0]?.widthPt, scale = native.scale;
    if (!width || !scale) { console.log(`${platform}: manifest has no width or scale, skipped`); continue; }
    const page = await browser.newPage();
    await page.setViewport({ width: Math.ceil(width) + 40, height: 1400, deviceScaleFactor: scale });
    await page.goto(`http://127.0.0.1:${server.address().port}/render-html.html`);
    const captures = [];
    for (const component of contract.components) {
      if (!native.captures.some((x) => x.component === component.id)) continue;
      for (const state of component.states) for (const appearance of contract.appearances) {
        await page.evaluate((id, props, pl, dark, w) => window.renderTile(id, props, pl, dark, w), component.id, state.props, platform, appearance === "dark", width);
        await page.evaluate(() => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r))));
        const file = `${component.id}/${state.id}--${appearance}.png`;
        mkdirSync(join(here, `captures/html-${platform}/${component.id}`), { recursive: true });
        const el = await page.$("#tile"), box = await el.boundingBox();
        await el.screenshot({ path: join(here, `captures/html-${platform}/${file}`) });
        captures.push({ component: component.id, state: state.id, appearance, file, widthPt: box.width, heightPt: box.height });
      }
    }
    writeFileSync(join(here, `captures/html-${platform}/manifest.json`), JSON.stringify({ platform, source: "html", scale, captures }, null, 2) + "\n");
    console.log(`${platform}: ${captures.length} HTML tiles`);
    await page.close();
  }
} finally {
  await browser.close();
  server.close();
}
