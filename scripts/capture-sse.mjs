#!/usr/bin/env node
/**
 * Capture live SSE from an opencode server the same way the mobile apps do:
 * dual-health protocol probe, then GET /global/event (v1) or GET /api/event (v2).
 *
 * Usage:
 *   OPENCODE_SERVER_PASSWORD='…' node scripts/capture-sse.mjs \
 *     --url http://192.168.0.160:4096 \
 *     --session ses_…
 *
 * Writes JSONL under build/sse-captures/ (gitignored via build/).
 * Ctrl+C to stop. Heartbeats are counted but omitted unless --include-heartbeat.
 */
import { createWriteStream, mkdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const REPO_ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");

function parseArgs(argv) {
  const args = {
    url: process.env.OPENCODE_SERVER_URL ?? "http://127.0.0.1:4096",
    username: process.env.OPENCODE_SERVER_USERNAME ?? "opencode",
    password: process.env.OPENCODE_SERVER_PASSWORD ?? "",
    session: process.env.OPENCODE_SESSION_ID ?? "",
    protocol: "auto",
    includeHeartbeat: false,
    allSessions: false,
    out: "",
  };

  for (let i = 0; i < argv.length; i += 1) {
    const token = argv[i];
    const next = argv[i + 1];
    switch (token) {
      case "--url":
      case "-u":
        args.url = next;
        i += 1;
        break;
      case "--username":
        args.username = next;
        i += 1;
        break;
      case "--password":
        args.password = next;
        i += 1;
        break;
      case "--session":
      case "-s":
        args.session = next;
        i += 1;
        break;
      case "--protocol":
        args.protocol = next;
        i += 1;
        break;
      case "--out":
      case "-o":
        args.out = next;
        i += 1;
        break;
      case "--include-heartbeat":
        args.includeHeartbeat = true;
        break;
      case "--all":
        args.allSessions = true;
        break;
      case "--help":
      case "-h":
        printHelp();
        process.exit(0);
        break;
      default:
        if (token.startsWith("-")) {
          console.error(`Unknown flag: ${token}`);
          printHelp();
          process.exit(1);
        }
    }
  }
  return args;
}

function printHelp() {
  console.log(`Capture opencode SSE events for comparison with the mobile apps.

Usage:
  node scripts/capture-sse.mjs --url <base> --session <id>

Options:
  --url, -u            Server base URL (or OPENCODE_SERVER_URL)
  --username           Basic Auth user (default: opencode)
  --password           Basic Auth password (or OPENCODE_SERVER_PASSWORD)
  --session, -s        Session id to highlight / filter
  --all                Keep events from every session
  --protocol           auto | v1 | v2  (default: auto, same probe as the apps)
  --include-heartbeat  Write server.heartbeat frames
  --out, -o            Output JSONL path
  --help, -h

Env:
  OPENCODE_SERVER_URL OPENCODE_SERVER_USERNAME OPENCODE_SERVER_PASSWORD
  OPENCODE_SESSION_ID`);
}

function basicAuthHeader(username, password) {
  return `Basic ${Buffer.from(`${username}:${password}`, "utf8").toString("base64")}`;
}

function normalizeBaseUrl(url) {
  return url.replace(/\/+$/, "");
}

async function fetchJson(url, headers) {
  const response = await fetch(url, {
    headers,
    signal: AbortSignal.timeout(5_000),
  });
  const text = await response.text();
  let body = null;
  try {
    body = text ? JSON.parse(text) : null;
  } catch {
    body = text;
  }
  return { ok: response.ok, status: response.status, body };
}

function classifyProtocol(globalHealth, apiHealth) {
  if (globalHealth?.healthy === true) return "v1";
  if (apiHealth?.pid != null) return "v2";
  if (apiHealth?.healthy === true) return "v1";
  return null;
}

async function probeProtocol(baseUrl, headers) {
  const [globalResult, apiResult] = await Promise.allSettled([
    fetchJson(`${baseUrl}/global/health`, headers),
    fetchJson(`${baseUrl}/api/health`, headers),
  ]);
  const global = globalResult.status === "fulfilled" && globalResult.value.ok
    ? globalResult.value.body
    : null;
  const api = apiResult.status === "fulfilled" && apiResult.value.ok
    ? apiResult.value.body
    : null;
  const protocol = classifyProtocol(global, api);
  const statuses = {
    global: globalResult.status === "fulfilled" ? globalResult.value.status : String(globalResult.reason),
    api: apiResult.status === "fulfilled" ? apiResult.value.status : String(apiResult.reason),
  };
  return { protocol, global, api, statuses };
}

function isCompleteJson(payload) {
  try {
    JSON.parse(payload);
    return true;
  } catch {
    return false;
  }
}

class SseDataLineParser {
  constructor() {
    this.dataBuffer = "";
    this.sseId = null;
    this.sseEvent = null;
  }

  ingest(line) {
    const trimmed = line.trim();
    if (trimmed === "") {
      return this.finish();
    }
    if (trimmed.startsWith(":")) {
      return [];
    }
    if (trimmed.startsWith("id:")) {
      this.sseId = trimmed.slice(3).trim();
      return [];
    }
    if (trimmed.startsWith("event:")) {
      this.sseEvent = trimmed.slice(6).trim();
      return [];
    }
    if (trimmed.startsWith("data:")) {
      const payload = trimmed.slice(5).trimStart();
      if (this.dataBuffer) this.dataBuffer += "\n";
      this.dataBuffer += payload;
      return isCompleteJson(this.dataBuffer) ? this.finish() : [];
    }
    if (trimmed.startsWith("{")) {
      const payloads = this.finish();
      payloads.push({ sseId: this.sseId, sseEvent: this.sseEvent, data: trimmed });
      return payloads;
    }
    return [];
  }

  finish() {
    if (!this.dataBuffer) return [];
    const record = {
      sseId: this.sseId,
      sseEvent: this.sseEvent,
      data: this.dataBuffer,
    };
    this.dataBuffer = "";
    this.sseId = null;
    this.sseEvent = null;
    return [record];
  }
}

function walkStrings(value, visit) {
  if (typeof value === "string") {
    visit(value);
    return;
  }
  if (Array.isArray(value)) {
    for (const item of value) walkStrings(item, visit);
    return;
  }
  if (value && typeof value === "object") {
    for (const [key, child] of Object.entries(value)) {
      if (typeof child === "string" && /session/i.test(key)) visit(child);
      walkStrings(child, visit);
    }
  }
}

function extractSessionIds(parsed) {
  const ids = new Set();
  walkStrings(parsed, (value) => {
    if (typeof value === "string" && value.startsWith("ses_")) ids.add(value);
  });
  return [...ids];
}

function extractType(parsed) {
  if (!parsed || typeof parsed !== "object") return null;
  return (
    parsed.payload?.type ??
    parsed.type ??
    parsed.event ??
    parsed.data?.type ??
    parsed.payload?.syncEvent?.type ??
    null
  );
}

function shortPreview(parsed, type) {
  const data = parsed?.data ?? parsed?.payload?.properties ?? parsed?.properties ?? parsed;
  if (!data || typeof data !== "object") return "";
  const keys = ["messageID", "messageId", "partID", "partId", "status", "error", "text"];
  const bits = [];
  for (const key of keys) {
    const value = data[key];
    if (typeof value === "string" && value) {
      const clipped = value.length > 80 ? `${value.slice(0, 77)}…` : value;
      bits.push(`${key}=${clipped.replaceAll("\n", "\\n")}`);
    }
  }
  if (type?.includes("delta") && typeof data.delta === "string") {
    bits.push(`delta=${JSON.stringify(data.delta).slice(0, 60)}`);
  }
  return bits.slice(0, 3).join(" ");
}

function matchesSession(sessionIds, session) {
  if (!session) return true;
  return sessionIds.includes(session);
}

function isHeartbeat(type) {
  return type === "server.heartbeat";
}

function defaultOutPath(session) {
  const stamp = new Date().toISOString().replaceAll(":", "-");
  const slug = session || "all";
  return join(REPO_ROOT, "build", "sse-captures", `${slug}-${stamp}.jsonl`);
}

function isoNow() {
  return new Date().toISOString();
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  if (!args.password) {
    console.error("Missing password. Pass --password or OPENCODE_SERVER_PASSWORD.");
    process.exit(1);
  }

  const baseUrl = normalizeBaseUrl(args.url);
  const headers = {
    Authorization: basicAuthHeader(args.username, args.password),
    Accept: "application/json",
  };

  let protocol = args.protocol;
  if (protocol === "auto") {
    const probe = await probeProtocol(baseUrl, headers);
    if (!probe.protocol) {
      console.error(
        `Could not detect protocol. /global/health=${probe.statuses.global} /api/health=${probe.statuses.api}`,
      );
      process.exit(1);
    }
    protocol = probe.protocol;
    const version = probe.global?.version ?? probe.api?.version ?? "?";
    console.error(`protocol=${protocol} version=${version} health global=${probe.statuses.global} api=${probe.statuses.api}`);
  } else if (protocol !== "v1" && protocol !== "v2") {
    console.error("--protocol must be auto, v1, or v2");
    process.exit(1);
  }

  const path = protocol === "v2" ? "/api/event" : "/global/event";
  const eventUrl = `${baseUrl}${path}`;
  const outPath = args.out || defaultOutPath(args.session);
  mkdirSync(dirname(outPath), { recursive: true });
  const out = createWriteStream(outPath);

  console.error(`GET ${eventUrl}`);
  if (args.session && !args.allSessions) {
    console.error(`filter session=${args.session} (use --all to keep every session)`);
  } else if (args.session) {
    console.error(`highlight session=${args.session}`);
  }
  console.error(`writing ${outPath}`);

  const controller = new AbortController();
  const shutdown = () => {
    controller.abort();
  };
  process.on("SIGINT", shutdown);
  process.on("SIGTERM", shutdown);

  const response = await fetch(eventUrl, {
    headers: {
      Authorization: headers.Authorization,
      Accept: "text/event-stream",
      "Cache-Control": "no-cache",
      "Accept-Encoding": "identity",
    },
    signal: controller.signal,
  });

  if (!response.ok || !response.body) {
    const body = await response.text().catch(() => "");
    console.error(`SSE HTTP ${response.status}${body ? `: ${body.slice(0, 200)}` : ""}`);
    process.exit(1);
  }

  console.error(`opened status=${response.status}`);

  const parser = new SseDataLineParser();
  const decoder = new TextDecoder();
  let lineBuffer = "";
  let kept = 0;
  let skippedOtherSession = 0;
  let heartbeats = 0;
  let parseFailures = 0;

  const handleRecord = (record) => {
    let parsed = null;
    try {
      parsed = JSON.parse(record.data);
    } catch {
      parseFailures += 1;
      console.error(`${isoNow()} PARSE_FAIL ${record.data.slice(0, 120)}`);
      return;
    }

    const type = extractType(parsed);
    const sessionIds = extractSessionIds(parsed);
    if (isHeartbeat(type) && !args.includeHeartbeat) {
      heartbeats += 1;
      return;
    }
    if (!args.allSessions && args.session && sessionIds.length > 0 && !matchesSession(sessionIds, args.session)) {
      skippedOtherSession += 1;
      return;
    }

    kept += 1;
    const row = {
      n: kept,
      receivedAt: isoNow(),
      protocol,
      path,
      sseId: record.sseId,
      sseEvent: record.sseEvent,
      type,
      sessionIds,
      raw: parsed,
    };
    out.write(`${JSON.stringify(row)}\n`);

    const sessionLabel = sessionIds[0] ?? "-";
    const mark = args.session && sessionIds.includes(args.session) ? "*" : " ";
    const preview = shortPreview(parsed, type);
    console.log(`${row.receivedAt} ${mark} ${String(type ?? "?").padEnd(36)} ${sessionLabel} ${preview}`.trimEnd());
  };

  try {
    for await (const chunk of response.body) {
      lineBuffer += decoder.decode(chunk, { stream: true });
      const lines = lineBuffer.split(/\r?\n/);
      lineBuffer = lines.pop() ?? "";
      for (const line of lines) {
        for (const record of parser.ingest(line)) handleRecord(record);
      }
    }
    lineBuffer += decoder.decode();
    if (lineBuffer) {
      for (const record of parser.ingest(lineBuffer)) handleRecord(record);
    }
    for (const record of parser.finish()) handleRecord(record);
  } catch (error) {
    if (error?.name !== "AbortError") throw error;
  } finally {
    out.end();
    console.error(
      `done kept=${kept} heartbeats=${heartbeats} otherSession=${skippedOtherSession} parseFail=${parseFailures}`,
    );
    console.error(`wrote ${outPath}`);
  }
}

main().catch((error) => {
  if (error?.name === "AbortError") return;
  console.error(error);
  process.exit(1);
});
