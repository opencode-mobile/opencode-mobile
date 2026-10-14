/**
 * Launch graphics server (node:http only).
 *   /                 renderer/index.html (editor; ?raw=1 = canvas only)
 *   /<file>           renderer/ static files
 *   /tokens           shared/theme-presets/porcelain.json
 *   /icon.png         play-listing/icon-512.png
 *   /shots/*          store screenshots dir (default <repo>/store-screenshots)
 *   /fonts/<family>/* @fontsource/ibm-plex-sans | @fontsource/ibm-plex-mono
 *   /vendor/html-to-image.js  (editor download only)
 *   /api/presets      GET list · /api/presets/<name> GET one, PUT save (validated)
 */
import { createReadStream, existsSync, readFileSync, readdirSync, statSync, writeFileSync } from "node:fs";
import { createServer } from "node:http";
import { dirname, extname, join, normalize as normalizePath, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";
import { normalize } from "./renderer/spec.js";

const HERE = dirname(fileURLToPath(import.meta.url));
export const REPO_ROOT = resolve(HERE, "../..");
export const RENDERER_DIR = join(HERE, "renderer");
export const PRESETS_DIR = join(HERE, "presets");
export const DEFAULT_SHOTS_DIR = join(REPO_ROOT, "store-screenshots");
const NODE_MODULES = join(REPO_ROOT, "node_modules");
const PRESET_NAME = /^[a-z0-9][a-z0-9-]{0,60}$/;
const MIME = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".png": "image/png",
  ".jpg": "image/jpeg",
  ".woff2": "font/woff2",
  ".woff": "font/woff",
  ".svg": "image/svg+xml",
};
const FONT_PACKAGES = { "ibm-plex-sans": "@fontsource/ibm-plex-sans", "ibm-plex-mono": "@fontsource/ibm-plex-mono" };

/** Resolve `rel` under `root`, refusing anything that escapes it. */
function inside(root, rel) {
  const p = resolve(root, `.${sep}${normalizePath(decodeURIComponent(rel))}`);
  return p === root || p.startsWith(root + sep) ? p : null;
}

function sendFile(res, file, cache = "no-cache") {
  if (!file || !existsSync(file) || !statSync(file).isFile()) return sendJson(res, 404, { error: "not found" });
  res.writeHead(200, { "content-type": MIME[extname(file).toLowerCase()] || "application/octet-stream", "cache-control": cache });
  createReadStream(file).pipe(res);
}
function sendJson(res, status, body) {
  res.writeHead(status, { "content-type": MIME[".json"], "cache-control": "no-cache" });
  res.end(JSON.stringify(body, null, 2));
}
async function readBody(req, limit = 1_000_000) {
  let size = 0;
  const chunks = [];
  for await (const c of req) {
    size += c.length;
    if (size > limit) throw new Error("body too large");
    chunks.push(c);
  }
  return Buffer.concat(chunks).toString("utf8");
}

export function listPresets(dir = PRESETS_DIR) {
  if (!existsSync(dir)) return [];
  return readdirSync(dir)
    .filter((f) => f.endsWith(".json"))
    .sort()
    .map((f) => {
      const id = f.slice(0, -5);
      try {
        const s = JSON.parse(readFileSync(join(dir, f), "utf8"));
        return { id, name: s.name || id, description: s.description || "", format: s.format || "1600x900", composition: s.composition || "closeup", moment: s.moment || "approval" };
      } catch (err) {
        return { id, error: err.message };
      }
    });
}

async function handlePresets(req, res, name, presetsDir) {
  if (!name) {
    if (req.method !== "GET") return sendJson(res, 405, { error: "GET only" });
    return sendJson(res, 200, listPresets(presetsDir));
  }
  if (!PRESET_NAME.test(name)) return sendJson(res, 400, { error: "preset names use lowercase letters, digits and dashes" });
  const file = join(presetsDir, `${name}.json`);
  if (req.method === "GET") return sendFile(res, file);
  if (req.method !== "PUT") return sendJson(res, 405, { error: "GET or PUT" });
  let spec;
  try {
    spec = JSON.parse(await readBody(req));
    normalize(spec); // validate; store as sent (sparse)
  } catch (err) {
    return sendJson(res, 400, { error: err.message });
  }
  writeFileSync(file, `${JSON.stringify(spec, null, 2)}\n`);
  return sendJson(res, 200, { ok: true, id: name, path: `scripts/launch-graphics/presets/${name}.json` });
}

/** Start the server. port 0 = ephemeral. Resolves { url, port, close() }. */
export function startServer({ port = 0, host = "127.0.0.1", shotsDir = DEFAULT_SHOTS_DIR, presetsDir = PRESETS_DIR } = {}) {
  const shotsRoot = resolve(shotsDir);
  const server = createServer(async (req, res) => {
    try {
      const url = new URL(req.url, "http://x");
      const p = url.pathname;
      if (p === "/" || p === "/index.html") return sendFile(res, join(RENDERER_DIR, "index.html"));
      if (p === "/tokens") return sendFile(res, join(REPO_ROOT, "shared/theme-presets/porcelain.json"));
      if (p === "/icon.png" || p === "/favicon.ico") return sendFile(res, join(REPO_ROOT, "play-listing/icon-512.png"), "max-age=3600");
      if (p.startsWith("/shots/")) return sendFile(res, inside(shotsRoot, p.slice(7)), "max-age=60");
      if (p.startsWith("/fonts/")) {
        const [, , family, ...rest] = p.split("/");
        const pkg = FONT_PACKAGES[family];
        return sendFile(res, pkg ? inside(join(NODE_MODULES, pkg), rest.join("/")) : null, "max-age=86400");
      }
      if (p === "/vendor/html-to-image.js") return sendFile(res, join(NODE_MODULES, "html-to-image/dist/html-to-image.js"), "max-age=86400");
      if (p === "/api/presets" || p.startsWith("/api/presets/")) return await handlePresets(req, res, decodeURIComponent(p.slice("/api/presets/".length)), presetsDir);
      return sendFile(res, inside(RENDERER_DIR, p.slice(1)));
    } catch (err) {
      sendJson(res, 500, { error: err.message });
    }
  });
  return new Promise((resolvePromise, reject) => {
    server.once("error", reject);
    server.listen(port, host, () => {
      const actual = server.address().port;
      resolvePromise({ url: `http://${host}:${actual}`, port: actual, server, close: () => new Promise((r) => server.close(r)) });
    });
  });
}
