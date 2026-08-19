#!/usr/bin/env node
/**
 * Local SSE observer. Serves the subscriber page and proxies to an opencode
 * server so the browser is not blocked by CORS.
 *
 *   node scripts/sse-subscriber.mjs [targetUrl] [port]
 *   open http://127.0.0.1:3456/
 */
import { createServer } from "node:http";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const target = (process.argv[2] || "http://127.0.0.1:4096").replace(/\/+$/, "");
const port = Number(process.argv[3] || 3456);
const htmlPath = join(dirname(fileURLToPath(import.meta.url)), "sse-subscriber.html");
const html = readFileSync(htmlPath, "utf8").replace(
  'value="http://127.0.0.1:4096"',
  'value=""',
).replace(
  "CORS must allow this origin.",
  `Proxied on this machine → ${target}. Leave Server empty.`,
);

const server = createServer(async (req, res) => {
  if (req.url === "/" || req.url === "/index.html") {
    res.writeHead(200, { "content-type": "text/html; charset=utf-8" });
    res.end(html);
    return;
  }
  const dest = new URL(req.url || "/", target);
  const headers = {};
  for (const name of ["authorization", "accept", "cache-control", "accept-encoding"]) {
    const v = req.headers[name];
    if (v) headers[name] = v;
  }
  try {
    const up = await fetch(dest, { headers, redirect: "follow" });
    const out = { "cache-control": "no-cache" };
    const ct = up.headers.get("content-type");
    if (ct) out["content-type"] = ct;
    res.writeHead(up.status, out);
    if (!up.body) {
      res.end();
      return;
    }
    const reader = up.body.getReader();
    const pump = async () => {
      while (true) {
        const { value, done } = await reader.read();
        if (done) break;
        if (!res.write(Buffer.from(value))) {
          await new Promise((resolve) => res.once("drain", resolve));
        }
      }
      res.end();
    };
    req.on("close", () => reader.cancel().catch(() => {}));
    await pump();
  } catch (err) {
    if (!res.headersSent) res.writeHead(502, { "content-type": "text/plain" });
    res.end(String(err?.message || err));
  }
});

server.listen(port, "127.0.0.1", () => {
  console.log(`http://127.0.0.1:${port}/  →  ${target}`);
});
