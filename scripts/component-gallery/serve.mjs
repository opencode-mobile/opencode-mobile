#!/usr/bin/env node
// Builds the gallery data, then serves it locally and reloads the page when a source file changes.
import { execFileSync } from "node:child_process";
import { createReadStream, existsSync, readFileSync, statSync, watch } from "node:fs";
import { createServer } from "node:http";
import { dirname, extname, join, normalize, sep } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const port = Number(process.env.PORT ?? 4600);
const types = { ".html": "text/html", ".js": "text/javascript", ".css": "text/css", ".svg": "image/svg+xml", ".md": "text/markdown", ".png": "image/png", ".json": "application/json" };
const clients = new Set();
const reloadScript = '<script>new EventSource("/__reload").onmessage = () => location.reload();</script>';

execFileSync(process.execPath, [join(here, "build.mjs")], { stdio: "inherit" });

let timer;
watch(here, { recursive: true }, (_event, file) => {
  if (!file || file === "data.js" || file.startsWith(`assets${sep}`) || file.startsWith(`captures${sep}`)) return;
  clearTimeout(timer);
  timer = setTimeout(() => { for (const client of clients) client.write("data: reload\n\n"); }, 80);
});

createServer((req, res) => {
  const path = decodeURIComponent(new URL(req.url, "http://localhost").pathname);
  if (path === "/__reload") {
    res.writeHead(200, { "content-type": "text/event-stream", "cache-control": "no-cache" });
    clients.add(res);
    req.on("close", () => clients.delete(res));
    return;
  }
  const file = normalize(join(here, path === "/" ? "index.html" : path));
  if (!file.startsWith(here + sep) || !existsSync(file) || statSync(file).isDirectory()) { res.writeHead(404); res.end(); return; }
  res.writeHead(200, { "content-type": types[extname(file)] ?? "application/octet-stream", "cache-control": "no-store" });
  if (file.endsWith(`${sep}index.html`)) res.end(readFileSync(file, "utf8").replace("</body>", `${reloadScript}</body>`));
  else createReadStream(file).pipe(res);
}).listen(port, "127.0.0.1", () => console.log(`Component gallery: http://127.0.0.1:${port}/`));
