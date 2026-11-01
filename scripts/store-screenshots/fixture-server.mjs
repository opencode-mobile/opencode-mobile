#!/usr/bin/env node
import { createServer } from "node:http";
import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

export const AUTH_USERNAME = "opencode";
export const AUTH_PASSWORD = "store-screenshots";
export const DEFAULT_HOST = "0.0.0.0";
export const DEFAULT_PORT = 4399;
export const HEARTBEAT_MS = 5000;

const SCRIPT_DIR = path.dirname(fileURLToPath(import.meta.url));
const DEFAULT_FIXTURE = path.join(SCRIPT_DIR, "fixtures", "default.json");
const OFFSET_FIELDS = {
  timeCreatedOffsetMs: ["created", "timeCreated"],
  timeUpdatedOffsetMs: ["updated"],
  timeCompletedOffsetMs: ["completed"],
  timeArchivedOffsetMs: ["archived"],
};

export function parseArgs(argv = process.argv.slice(2), startedAtMs = Date.now()) {
  const args = {
    port: DEFAULT_PORT,
    host: DEFAULT_HOST,
    nowMs: startedAtMs,
    fixture: DEFAULT_FIXTURE,
  };
  for (let i = 0; i < argv.length; i += 1) {
    const token = argv[i];
    const next = argv[i + 1];
    switch (token) {
      case "--port":
        args.port = Number.parseInt(next, 10);
        i += 1;
        break;
      case "--host":
        args.host = next;
        i += 1;
        break;
      case "--now":
        args.nowMs = parseNow(next);
        i += 1;
        break;
      case "--fixture":
        args.fixture = path.resolve(next);
        i += 1;
        break;
      case "--help":
      case "-h":
        args.help = true;
        break;
      default:
        throw new Error(`Unknown flag: ${token}`);
    }
  }
  if (!Number.isInteger(args.port) || args.port < 0 || args.port > 65535) {
    throw new Error(`Invalid --port: ${args.port}`);
  }
  return args;
}

export function parseNow(value) {
  const ms = Date.parse(value);
  if (Number.isNaN(ms)) {
    throw new Error(`Invalid --now: ${value}`);
  }
  return ms;
}

export function loadFixtureFile(fixturePath) {
  return JSON.parse(readFileSync(fixturePath, "utf8"));
}

export function resolveFixture(raw, nowMs) {
  const resolved = resolveNode(structuredClone(raw), nowMs);
  const sessions = (resolved.sessions ?? []).map((session) => toSession(session));
  sessions.sort((a, b) => (b.time?.updated ?? 0) - (a.time?.updated ?? 0));
  const messages = {};
  for (const [sessionId, items] of Object.entries(resolved.messages ?? {})) {
    messages[sessionId] = items.map(toMessage);
  }
  const diffs = {};
  for (const [sessionId, items] of Object.entries(resolved.diffs ?? {})) {
    diffs[sessionId] = items.map(toDiff);
  }
  return {
    health: resolved.health,
    bootDirectory: resolved.bootDirectory,
    projects: resolved.projects ?? [],
    sessions,
    messages,
    permissions: resolved.permissions ?? [],
    questions: resolved.questions ?? [],
    forms: resolved.forms ?? [],
    providers: resolved.providers ?? [],
    models: resolved.models ?? [],
    defaultModel: resolved.defaultModel,
    agents: resolved.agents ?? [],
    commands: resolved.commands ?? [],
    skills: resolved.skills ?? [],
    diffs,
    todos: resolved.todos ?? {},
  };
}

function resolveNode(value, nowMs) {
  if (Array.isArray(value)) {
    return value.map((item) => resolveNode(item, nowMs));
  }
  if (value && typeof value === "object") {
    const out = {};
    const time = {};
    let timeCreated;
    for (const [key, child] of Object.entries(value)) {
      const offsetSpec = OFFSET_FIELDS[key];
      if (offsetSpec) {
        const resolved = nowMs + Number(child);
        if (key === "timeCreatedOffsetMs") {
          timeCreated = resolved;
        }
        for (const field of offsetSpec) {
          if (field === "timeCreated") {
            timeCreated = resolved;
          } else {
            time[field] = resolved;
          }
        }
        continue;
      }
      if (key === "textLines") {
        out.text = child.join("\n");
        continue;
      }
      if (key === "patchLines") {
        out.patch = child.join("\n");
        continue;
      }
      out[key] = resolveNode(child, nowMs);
    }
    if (Object.keys(time).length > 0) {
      out.time = { ...(out.time ?? {}), ...time };
    }
    if (timeCreated !== undefined && out.timeCreated === undefined) {
      out.timeCreated = timeCreated;
    }
    return out;
  }
  return value;
}

function toSession(session) {
  const { timeCreatedOffsetMs, timeUpdatedOffsetMs, timeArchivedOffsetMs, active, ...rest } = session;
  return { ...rest, active };
}

function toMessage(message) {
  const { textLines, ...rest } = message;
  if (Array.isArray(rest.content)) {
    rest.content = rest.content.map((part) => {
      if (part.textLines) {
        const { textLines: lines, ...partRest } = part;
        return { ...partRest, text: lines.join("\n") };
      }
      return part;
    });
  }
  return rest;
}

function toDiff(diff) {
  const { patchLines, ...rest } = diff;
  return rest;
}

function queryMap(url) {
  const params = url.searchParams;
  const directory = params.get("directory") ?? params.get("location[directory]");
  return {
    directory,
    parentId: params.get("parentID"),
    cursor: params.get("cursor"),
    limit: params.get("limit"),
    order: params.get("order"),
    mode: params.get("mode"),
    context: params.get("context"),
    path: params.get("path"),
    messageId: params.get("messageID"),
  };
}

function projectForDirectory(world, directory) {
  const wanted = directory || world.bootDirectory;
  return (
    world.projects.find((project) => project.directory === wanted || project.worktree === wanted) ??
    world.projects[0]
  );
}

function locationFor(world, directory) {
  const project = projectForDirectory(world, directory);
  return {
    directory: project.directory,
    workspaceID: project.workspaceID,
    project: {
      id: project.id,
      directory: project.directory,
      canonical: project.directory,
    },
  };
}

function publicProject(project) {
  return {
    id: project.id,
    worktree: project.worktree,
    directory: project.directory,
    vcs: project.vcs,
  };
}

function publicSession(session) {
  return {
    id: session.id,
    title: session.title,
    projectID: session.projectID ?? null,
    parentID: session.parentID ?? null,
    fork: session.fork ?? null,
    cost: session.cost ?? null,
    tokens: session.tokens ?? null,
    time: session.time ?? null,
    location: session.location ?? null,
    revert: session.revert ?? null,
  };
}

function publicMessage(message) {
  return {
    id: message.id,
    type: message.type,
    text: message.text ?? null,
    files: message.files ?? [],
    agents: message.agents ?? [],
    agent: message.agent ?? null,
    model: message.model ?? null,
    content: message.content ?? [],
    cost: message.cost ?? null,
    tokens: message.tokens ?? null,
    error: message.error ?? null,
    time: message.time ?? null,
    reason: message.reason ?? null,
    summary: message.summary ?? null,
    status: message.status ?? null,
    command: message.command ?? null,
    output: message.output ?? null,
    callID: message.callID ?? null,
    name: message.name ?? null,
    description: message.description ?? null,
  };
}

function sessionsFor(world, query) {
  let sessions = world.sessions;
  if (query.parentId) {
    sessions = sessions.filter((session) => session.parentID === query.parentId);
  } else {
    sessions = sessions.filter((session) => !session.parentID);
  }
  if (query.directory) {
    sessions = sessions.filter((session) => session.location?.directory === query.directory);
  }
  return sessions;
}

function listEnvelope(data, cursor = null) {
  return { data, cursor };
}

function locatedEnvelope(world, directory, data) {
  return { location: locationFor(world, directory), data };
}

function defaultModel(world) {
  const ref = world.defaultModel;
  return (
    world.models.find((model) => model.id === ref?.id && model.providerID === ref?.providerID) ??
    world.models.find((model) => model.id === ref?.id) ??
    world.models[0]
  );
}

function authorize(req) {
  const header = req.headers.authorization ?? "";
  if (!header.startsWith("Basic ")) {
    return false;
  }
  const decoded = Buffer.from(header.slice(6), "base64").toString("utf8");
  const sep = decoded.indexOf(":");
  if (sep < 0) {
    return false;
  }
  return decoded.slice(0, sep) === AUTH_USERNAME && decoded.slice(sep + 1) === AUTH_PASSWORD;
}

function sendJson(res, status, body) {
  const payload = Buffer.from(JSON.stringify(body), "utf8");
  res.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "content-length": String(payload.length),
    "cache-control": "no-store",
  });
  res.end(payload);
}

function sendUnauthorized(res) {
  res.writeHead(401, {
    "www-authenticate": 'Basic realm="opencode"',
    "content-type": "application/json; charset=utf-8",
  });
  res.end(JSON.stringify({ error: "unauthorized" }));
}

function sendSse(req, res, world) {
  res.writeHead(200, {
    "content-type": "text/event-stream",
    "cache-control": "no-cache",
    connection: "keep-alive",
    "x-accel-buffering": "no",
  });
  const frame = (type, properties = {}) => `data: ${JSON.stringify({ payload: { type, properties } })}\n\n`;
  // v2 serves todos only as events; resend them so a chat opened later still gets its list.
  const todoFrames = () =>
    Object.entries(world.todos ?? {})
      .map(([sessionID, todos]) => frame("todo.updated", { sessionID, todos }))
      .join("");
  res.write(frame("server.connected") + todoFrames());
  const timer = setInterval(() => {
    if (res.writableEnded) {
      clearInterval(timer);
      return;
    }
    res.write(frame("server.heartbeat") + todoFrames());
  }, HEARTBEAT_MS);
  timer.unref?.();
  const stop = () => {
    clearInterval(timer);
    world.sseTimers.delete(timer);
    if (!res.writableEnded) {
      res.end();
    }
  };
  world.sseTimers.add(timer);
  req.on("close", stop);
  res.on("close", stop);
}

function pageItems(items, query) {
  const order = query.order === "desc" ? "desc" : "asc";
  const ordered = order === "desc" ? [...items].reverse() : items;
  const limit = query.limit ? Number.parseInt(query.limit, 10) : ordered.length;
  let start = 0;
  if (query.cursor) {
    const index = ordered.findIndex((item) => item.id === query.cursor);
    start = index >= 0 ? index + 1 : 0;
  }
  const data = Number.isFinite(limit) ? ordered.slice(start, start + limit) : ordered.slice(start);
  const next = start + data.length < ordered.length ? data[data.length - 1]?.id ?? null : null;
  return { data, cursor: next ? { previous: query.cursor ?? null, next } : null };
}

function handleGet(url, pathname, world, req, res) {
  const query = queryMap(url);
  if (pathname === "/global/health") {
    sendJson(res, 404, { error: "not found" });
    return;
  }
  if (pathname === "/api/health") {
    sendJson(res, 200, world.health);
    return;
  }
  if (pathname === "/api/project") {
    sendJson(res, 200, listEnvelope(world.projects.map(publicProject)));
    return;
  }
  if (pathname === "/api/project/current") {
    sendJson(res, 200, publicProject(projectForDirectory(world, query.directory)));
    return;
  }
  if (pathname === "/api/location") {
    sendJson(res, 200, locationFor(world, query.directory));
    return;
  }
  if (pathname === "/api/fs/list") {
    const project = projectForDirectory(world, query.directory);
    sendJson(res, 200, locatedEnvelope(world, query.directory, project.fs ?? []));
    return;
  }
  if (pathname === "/api/session") {
    sendJson(res, 200, listEnvelope(sessionsFor(world, query).map(publicSession)));
    return;
  }
  if (pathname === "/api/session/active") {
    const data = {};
    for (const session of world.sessions) {
      if (session.active?.type) {
        data[session.id] = { type: session.active.type };
      }
    }
    sendJson(res, 200, { data });
    return;
  }
  if (pathname === "/api/permission/request") {
    let items = world.permissions;
    if (query.directory) {
      const sessionIds = new Set(
        world.sessions.filter((session) => session.location?.directory === query.directory).map((session) => session.id),
      );
      items = items.filter((item) => sessionIds.has(item.sessionID));
    }
    sendJson(res, 200, listEnvelope(items));
    return;
  }
  if (pathname === "/api/question/request") {
    sendJson(res, 200, listEnvelope(world.questions));
    return;
  }
  if (pathname === "/api/form") {
    sendJson(res, 200, locatedEnvelope(world, query.directory, world.forms));
    return;
  }
  if (pathname === "/api/form/request") {
    sendJson(res, 200, listEnvelope(world.forms));
    return;
  }
  if (pathname === "/api/model") {
    sendJson(res, 200, listEnvelope(world.models));
    return;
  }
  if (pathname === "/api/model/default") {
    sendJson(res, 200, locatedEnvelope(world, query.directory, defaultModel(world)));
    return;
  }
  if (pathname === "/api/provider") {
    sendJson(res, 200, listEnvelope(world.providers));
    return;
  }
  if (pathname === "/api/agent") {
    sendJson(res, 200, listEnvelope(world.agents));
    return;
  }
  if (pathname === "/api/command") {
    sendJson(res, 200, listEnvelope(world.commands));
    return;
  }
  if (pathname === "/api/skill") {
    sendJson(res, 200, listEnvelope(world.skills));
    return;
  }
  if (pathname === "/api/pty") {
    sendJson(res, 200, { data: [] });
    return;
  }
  if (pathname === "/api/vcs") {
    const project = projectForDirectory(world, query.directory);
    sendJson(res, 200, locatedEnvelope(world, query.directory, { provider: project.vcs, branch: project.branch }));
    return;
  }
  if (pathname === "/api/vcs/status") {
    const project = projectForDirectory(world, query.directory);
    const files = world.sessions
      .filter((session) => session.location?.directory === project.directory)
      .flatMap((session) => world.diffs[session.id] ?? [])
      .map(({ file, additions, deletions, status }) => ({ file, additions, deletions, status }));
    sendJson(res, 200, locatedEnvelope(world, query.directory, files));
    return;
  }
  if (pathname === "/api/vcs/diff") {
    const project = projectForDirectory(world, query.directory);
    const diffs =
      world.sessions
        .filter((session) => session.location?.directory === project.directory)
        .map((session) => world.diffs[session.id] ?? [])
        .find((items) => items.length > 0) ?? [];
    sendJson(res, 200, locatedEnvelope(world, query.directory, diffs));
    return;
  }
  if (pathname === "/api/event") {
    sendSse(req, res, world);
    return;
  }

  const sessionMatch = pathname.match(/^\/api\/session\/([^/]+)(?:\/(.*))?$/);
  if (sessionMatch) {
    const sessionId = decodeURIComponent(sessionMatch[1]);
    const rest = sessionMatch[2] ?? "";
    const session = world.sessions.find((item) => item.id === sessionId);
    if (!session && rest === "") {
      sendJson(res, 404, { error: "not found" });
      return;
    }
    if (rest === "") {
      sendJson(res, 200, { data: publicSession(session) });
      return;
    }
    if (rest === "children") {
      const children = world.sessions.filter((item) => item.parentID === sessionId).map(publicSession);
      sendJson(res, 200, listEnvelope(children));
      return;
    }
    if (rest === "todo") {
      sendJson(res, 200, listEnvelope([]));
      return;
    }
    if (rest === "inbox") {
      sendJson(res, 200, listEnvelope([]));
      return;
    }
    if (rest === "diff") {
      sendJson(res, 200, listEnvelope(world.diffs[sessionId] ?? []));
      return;
    }
    if (rest === "message") {
      const items = (world.messages[sessionId] ?? []).map(publicMessage);
      const page = pageItems(items, query);
      sendJson(res, 200, listEnvelope(page.data, page.cursor));
      return;
    }
    const messageMatch = rest.match(/^message\/([^/]+)$/);
    if (messageMatch) {
      const message = (world.messages[sessionId] ?? []).find((item) => item.id === messageMatch[1]);
      if (!message) {
        sendJson(res, 404, { error: "not found" });
        return;
      }
      sendJson(res, 200, { data: publicMessage(message) });
      return;
    }
  }

  sendJson(res, 404, { error: "not found" });
}

export function createHandler(world) {
  return (req, res) => {
    const method = (req.method ?? "GET").toUpperCase();
    let url;
    try {
      url = new URL(req.url ?? "/", "http://fixture.local");
    } catch {
      sendJson(res, 400, { error: "bad request" });
      console.log(`${method} ${req.url ?? "/"} -> 400`);
      return;
    }
    const pathname = url.pathname.replace(/\/+$/, "") || "/";
    const logPath = pathname;
    if (!authorize(req)) {
      sendUnauthorized(res);
      console.log(`${method} ${logPath} -> 401`);
      return;
    }
    if (method !== "GET" && method !== "HEAD") {
      sendJson(res, 200, { data: true });
      console.log(`${method} ${logPath} -> 200`);
      return;
    }
    const beforeEnded = res.writableEnded;
    handleGet(url, pathname, world, req, res);
    if (pathname === "/api/event") {
      console.log(`${method} ${logPath} -> 200`);
      return;
    }
    if (!beforeEnded) {
      const status = res.statusCode || 200;
      console.log(`${method} ${logPath} -> ${status}`);
    }
  };
}

export async function startFixtureServer(options = {}) {
  const startedAtMs = options.nowMs ?? Date.now();
  const config = {
    port: options.port ?? DEFAULT_PORT,
    host: options.host ?? DEFAULT_HOST,
    nowMs: options.nowMs ?? startedAtMs,
    fixture: options.fixture ?? DEFAULT_FIXTURE,
  };
  const raw = options.worldRaw ?? loadFixtureFile(config.fixture);
  const world = {
    ...resolveFixture(raw, config.nowMs),
    sseTimers: new Set(),
  };
  const handler = createHandler(world);
  const server = createServer(handler);
  await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(config.port, config.host, () => {
      server.off("error", reject);
      resolve();
    });
  });
  const address = server.address();
  const port = typeof address === "object" && address ? address.port : config.port;
  const close = () =>
    new Promise((resolve, reject) => {
      for (const timer of world.sseTimers) {
        clearInterval(timer);
      }
      world.sseTimers.clear();
      server.close((error) => (error ? reject(error) : resolve()));
      server.closeAllConnections?.();
    });
  return {
    server,
    host: config.host,
    port,
    nowMs: config.nowMs,
    world,
    close,
  };
}

function printHelp() {
  console.log(`Usage: node scripts/store-screenshots/fixture-server.mjs --port 4399 [--host 0.0.0.0] [--now 2026-09-14T09:41:00Z] [--fixture <path>]`);
}

function isMainModule() {
  const entry = process.argv[1];
  if (!entry) {
    return false;
  }
  return import.meta.url === pathToFileURL(path.resolve(entry)).href;
}

if (isMainModule()) {
  try {
    const args = parseArgs(process.argv.slice(2));
    if (args.help) {
      printHelp();
      process.exit(0);
    }
    const started = await startFixtureServer(args);
    console.log(`fixture-server listening on http://${started.host}:${started.port}`);
  } catch (error) {
    console.error(error instanceof Error ? error.message : error);
    process.exit(1);
  }
}
