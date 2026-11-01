#!/usr/bin/env node
// Scenario harness: a real `opencode serve` (v1 or v2) whose only model is a
// scripted fake, a fault proxy the apps connect through, and a web page to
// trigger scenarios and watch app requests, server events and model calls.

import http from "node:http";
import { readFileSync, writeFileSync, existsSync, mkdirSync } from "node:fs";
import { randomBytes } from "node:crypto";
import { execFileSync } from "node:child_process";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { createModel } from "./model.mjs";
import { createServer } from "./server.mjs";
import { createProxy } from "./proxy.mjs";
import { createClient } from "./client.mjs";
import { SCENARIOS } from "./scenarios.mjs";

const HERE = path.dirname(fileURLToPath(import.meta.url));

const HELP = `Usage: node scripts/scenario-harness/harness.mjs [options]

Starts a real opencode server driven by a scripted fake model, a fault proxy
for the apps, and a web UI to arm scenarios and watch traffic.

  --protocol v1|v2      Server to start first (default v1)
  --v1-bin <path>       Classic opencode binary (default: opencode on PATH)
  --v2-repo <path>      opencode checkout with v2 dev (default ~/Projects/opencode)
  --ui-port <n>         Web UI (default 4500, 127.0.0.1)
  --app-port <n>        Port the apps connect to, via the fault proxy (default 4096, 0.0.0.0)
  --upstream-port <n>   Real opencode server (default 4097, 127.0.0.1)
  --model-port <n>      Fake model (default 4501, 127.0.0.1)
  --state-dir <path>    Data, config and project dirs (default $TMPDIR/opencode-mobile-harness)
  --fresh               Re-seed the throwaway project on start

Apps connect to:
  iOS Simulator     http://127.0.0.1:<app-port>
  Android emulator  http://10.0.2.2:<app-port>
  Username opencode, password printed at startup (stored in <state-dir>/password).

In the app, arm a scenario in the web UI and send any prompt, or send a prompt
containing "scenario: <id>". Unscripted model calls answer "ok"; title and
summary requests are answered automatically.`;

function parseArgs(argv) {
  const args = {
    protocol: "v1",
    v1Bin: null,
    v2Repo: path.join(os.homedir(), "Projects/opencode"),
    uiPort: 4500,
    appPort: 4096,
    upstreamPort: 4097,
    modelPort: 4501,
    stateDir: path.join(os.tmpdir(), "opencode-mobile-harness"),
    fresh: false,
  };
  for (let i = 0; i < argv.length; i += 1) {
    const flag = argv[i];
    const next = () => argv[++i];
    if (flag === "--help" || flag === "-h") args.help = true;
    else if (flag === "--protocol") args.protocol = next();
    else if (flag === "--v1-bin") args.v1Bin = next();
    else if (flag === "--v2-repo") args.v2Repo = path.resolve(next());
    else if (flag === "--ui-port") args.uiPort = Number(next());
    else if (flag === "--app-port") args.appPort = Number(next());
    else if (flag === "--upstream-port") args.upstreamPort = Number(next());
    else if (flag === "--model-port") args.modelPort = Number(next());
    else if (flag === "--state-dir") args.stateDir = path.resolve(next());
    else if (flag === "--fresh") args.fresh = true;
    else throw new Error(`Unknown flag ${flag}`);
  }
  if (!["v1", "v2"].includes(args.protocol)) throw new Error("--protocol must be v1 or v2");
  if (!args.v1Bin) {
    try {
      args.v1Bin = execFileSync("which", ["opencode"]).toString().trim();
    } catch {
      args.v1Bin = "opencode";
    }
  }
  return args;
}

const args = parseArgs(process.argv.slice(2));
if (args.help) {
  console.log(HELP);
  process.exit(0);
}

mkdirSync(args.stateDir, { recursive: true });
const passwordFile = path.join(args.stateDir, "password");
if (!existsSync(passwordFile)) writeFileSync(passwordFile, randomBytes(12).toString("base64url"), { mode: 0o600 });
const password = readFileSync(passwordFile, "utf8").trim();

// ---- event bus to the web UI ----
const clients = new Set();
const history = [];
let seq = 0;
const listeners = new Set();
function emit(type, data) {
  const event = { seq: ++seq, at: Date.now(), type, data };
  if (!type.endsWith(".state")) {
    history.push(event);
    if (history.length > 3000) history.splice(0, history.length - 3000);
  }
  const frame = `data: ${JSON.stringify(event)}\n\n`;
  for (const res of clients) res.write(frame);
  for (const listener of listeners) listener(event);
}

const scenarios = new Map(SCENARIOS.map((s) => [s.id, s]));
const server = createServer({ stateDir: args.stateDir, emit });
const getServer = () => server.state();
const model = createModel({
  scenarios,
  emit,
  getContext: () => ({
    protocol: server.state()?.protocol ?? args.protocol,
    project: server.state()?.project ?? "",
    harnessURL: `http://127.0.0.1:${args.uiPort}`,
  }),
});
const proxy = createProxy({ emit, getUpstream: () => (server.state()?.status === "ready" ? server.state() : null) });
const client = createClient({ getServer, password, emit });

// ---- automation: "another client" steps attached to a Run ----
// automation = { scenario, steps: [{...step, fired}], timers, sessionID, sessions: {name: id} }
let automation = null;
let lastActiveSession = null;

listeners.add((event) => {
  if (event.type === "server.event") {
    const props = event.data.props ?? {};
    const sessionID = props.sessionID ?? props.info?.sessionID ?? props.part?.sessionID;
    if (sessionID && /status|execution|step|message|part|text/.test(event.data.type)) lastActiveSession = sessionID;
    // v1 publishes on both /global/event and /event; fire once per event.
    if (event.data.source.includes("/global/event") && server.state()?.protocol === "v1") return;
    fire(event.data.type, props);
  }
  if (event.type === "model.run" && event.data.phase === "started") fire("harness.run.started", { sessionID: event.data.run.sessionID ?? undefined });
  if (event.type === "model.run" && event.data.phase === "finished") fire("harness.run.finished", { sessionID: event.data.run.sessionID ?? undefined });
});

function fire(type, props) {
  if (!automation) return;
  for (const step of automation.steps) {
    const triggers = Array.isArray(step.on) ? step.on : [step.on];
    if (step.fired || !triggers.includes(type)) continue;
    step.fired = true;
    const timer = setTimeout(() => execute(step, props), step.delayMs ?? 0);
    automation.timers.push(timer);
  }
}

async function latestSession() {
  const list = await client.actions.listSessions();
  const top = (Array.isArray(list) ? list : list?.items ?? []).filter((x) => !x.parentID);
  top.sort((a, b) => (b.time?.created ?? 0) - (a.time?.created ?? 0));
  return top[0]?.id ?? null;
}

// A step's target session: a name created during setup, "latest", or the run's session.
async function targetSession(step, props = {}) {
  if (step.session === "latest") return latestSession();
  if (step.session && automation?.sessions[step.session]) return automation.sessions[step.session];
  return props.sessionID ?? props.info?.metadata?.sessionID ?? automation?.sessionID ?? lastActiveSession;
}

async function runStep(step, props = {}) {
  const sessionID = await targetSession(step, props);
  emit("automation.step", { do: step.do, on: step.on, at: step.at, sessionID });
  const actions = client.actions;
  switch (step.do) {
    case "createSession": {
      const session = await actions.createSession({ title: step.title, model: step.model });
      if (automation) {
        automation.sessionID = session?.id;
        if (step.as) automation.sessions[step.as] = session?.id;
      }
      return session;
    }
    case "prompt":
      return actions.prompt({ sessionID, text: step.text, model: step.model });
    case "seedTurns": {
      // Completed turns for history: unscripted prompts answer "ok" while nothing is armed.
      for (let i = 1; i <= (step.count ?? 1); i += 1) {
        await actions.prompt({ sessionID, text: `${step.text ?? "Seed turn"} ${i}` });
        await new Promise((resolve) => setTimeout(resolve, 300));
        await actions.waitIdle({ sessionID });
      }
      return;
    }
    case "wait":
      return new Promise((resolve) => setTimeout(resolve, step.ms ?? 1000));
    case "abort":
      return actions.abort({ sessionID });
    case "replyPermission": {
      const requestID = props.id ?? props.requestID;
      return actions.replyPermission({ sessionID, requestID, reply: step.reply ?? "once" });
    }
    case "replyQuestion": {
      const requestID = props.form?.id ?? props.id ?? props.requestID;
      return actions.replyQuestion({ sessionID, requestID, answers: step.answers ?? [["ok"]] });
    }
    case "rejectQuestion": {
      const requestID = props.form?.id ?? props.id ?? props.requestID;
      return actions.rejectQuestion({ sessionID, requestID });
    }
    case "release":
      return model.release(step.gate);
    case "touch": {
      const project = server.state()?.project;
      const file = path.join(project, step.path);
      mkdirSync(path.dirname(file), { recursive: true });
      writeFileSync(file, "");
      return;
    }
    case "rename":
      return actions.rename({ sessionID, title: step.title });
    case "remove":
      return actions.remove({ sessionID });
    case "compact":
      return actions.compact({ sessionID, model: step.model });
    case "revert":
      return actions.revert({ sessionID, userTurn: step.userTurn });
    case "revertCommit":
      return actions.revertCommit({ sessionID });
    case "revertClear":
      return actions.revertClear({ sessionID });
    case "listShells":
      return actions.listShells({ sessionID });
    case "readShellOutputs":
      return actions.readShellOutputs({ sessionID });
    case "stopShells":
      return actions.stopShells({ sessionID });
    case "dropStreams":
      return proxy.dropStreams();
    case "offline":
      return proxy.setFaults({ offlineMs: step.ms ?? 3000 });
    case "rejectSseAuth":
      return proxy.setFaults({ rejectSseAuthMs: step.ms ?? 30000 });
    case "stall":
      return proxy.setFaults({ stall: true });
    case "unstall":
      return proxy.setFaults({ stall: false });
    case "heartbeatOnly":
      return proxy.setFaults({ heartbeatOnly: step.value !== false });
    case "clearFaults":
      return proxy.clearFaults();
    default:
      throw new Error(`unknown automation step ${step.do}`);
  }
}

// Runs a scheduled step and records completion, so watchers can tell scheduled from done.
async function execute(step, props) {
  try {
    await runStep(step, props);
  } catch (error) {
    emit("automation.error", { step, error: error.message });
  } finally {
    step.done = true;
  }
}

function clearAutomation() {
  if (!automation) return;
  for (const timer of automation.timers) clearTimeout(timer);
  automation = null;
}

// Puts the throwaway project back to its seeded commit (harness config is git-excluded, so it stays).
function resetProjectFiles() {
  const project = server.state()?.project;
  if (!project) return;
  execFileSync("git", ["reset", "-q", "--hard"], { cwd: project });
  execFileSync("git", ["clean", "-q", "-fd"], { cwd: project });
  emit("harness.project", { reset: project });
}

// A variant is either a list of automation steps or {automation, modelCalls, prompt}.
function variantOf(scenario, name) {
  if (!name) return {};
  const value = scenario.variants?.[name];
  if (!value) throw new Error(`${scenario.id} has no variant ${name}`);
  return Array.isArray(value) ? { automation: value } : value;
}

async function run({ scenario: id, variant, drive, keepFiles }) {
  const scenario = scenarios.get(id);
  if (!scenario) throw new Error(`unknown scenario ${id}`);
  const protocol = server.state()?.protocol;
  if (scenario.protocols && !scenario.protocols.includes(protocol)) throw new Error(`${id} does not run on ${protocol}`);
  const chosen = variantOf(scenario, variant);
  clearAutomation();
  model.arm(null);
  if (!keepFiles) resetProjectFiles();
  proxy.clearFaults();
  proxy.resetCounters();
  const steps = chosen.automation ?? scenario.automation ?? [];
  automation = { scenario: id, steps: steps.map((s) => ({ ...s, fired: false })), timers: [], sessionID: null, sessions: {} };
  emit("harness.run", { scenario: id, variant: variant ?? null, drive: Boolean(drive) });
  // Setup runs before arming, so its seed prompts get unscripted answers.
  for (const step of scenario.setup ?? []) {
    emit("harness.setup", { do: step.do });
    await runStep(step);
  }
  model.arm(id, { modelCalls: chosen.modelCalls, summary: chosen.summary });
  for (const step of automation.steps.filter((s) => s.at !== undefined)) {
    step.fired = true;
    automation.timers.push(setTimeout(() => execute(step, {}), step.at));
  }
  // Drive: the harness itself plays the user (new session + the scenario prompt).
  const prompt = chosen.prompt ?? scenario.prompt;
  if (drive && prompt) {
    const session = await client.actions.createSession({ title: scenario.title, model: scenario.model });
    automation.sessionID = session?.id;
    await client.actions.prompt({ sessionID: session.id, text: prompt, model: scenario.model });
  }
}

async function startServer(protocol, fresh) {
  clearAutomation();
  model.reset();
  proxy.dropStreams();
  client.stopObservers();
  const state = await server.start({
    protocol,
    port: args.upstreamPort,
    password,
    modelURL: `http://127.0.0.1:${args.modelPort}/v1`,
    v1Bin: args.v1Bin,
    v2Repo: args.v2Repo,
    freshProject: fresh,
  });
  client.startObservers();
  return state;
}

function fullState() {
  const lan = Object.values(os.networkInterfaces())
    .flat()
    .filter((i) => i && i.family === "IPv4" && !i.internal)
    .map((i) => i.address);
  return {
    server: server.state(),
    model: model.state(),
    proxy: proxy.state(),
    pending: client.actions.pending(),
    busySessions: client.actions.busySessions(),
    automation: automation && { scenario: automation.scenario, steps: automation.steps.map(({ fired, done, on, at, do: action, delayMs }) => ({ fired, done: Boolean(done), on, at, do: action, delayMs })) },
    connect: {
      ios: `http://127.0.0.1:${args.appPort}`,
      android: `http://10.0.2.2:${args.appPort}`,
      lan: lan.map((ip) => `http://${ip}:${args.appPort}`),
      username: "opencode",
      password,
    },
    scenarios: SCENARIOS.map(({ id, title, category, priority, prompt, expect, protocols, variants, automation: auto, setup, appOnly, checkVariant, modelCalls, summary, optionalCalls, serverChecks, checkTimeoutMs }) => ({
      id,
      title,
      category,
      priority: priority ?? "P0",
      prompt,
      expect,
      protocols: protocols ?? ["v1", "v2"],
      variants: Object.keys(variants ?? {}),
      automated: Boolean(auto?.length || setup?.length),
      appOnly: Boolean(appOnly),
      checkVariant: checkVariant ?? null,
      scripted: Boolean(modelCalls?.length || summary),
      optionalCalls: optionalCalls ?? 0,
      serverChecks: serverChecks ?? null,
      checkTimeoutMs: checkTimeoutMs ?? null,
      variantOptionalCalls: Object.fromEntries(
        Object.entries(variants ?? {})
          .filter(([, value]) => !Array.isArray(value) && value.optionalCalls !== undefined)
          .map(([name, value]) => [name, value.optionalCalls]),
      ),
    })),
  };
}

// ---- HTTP: web UI + control API ----
async function readJson(req) {
  let text = "";
  for await (const part of req) text += part;
  return text ? JSON.parse(text) : {};
}

const routes = {
  "POST /harness/server/start": (body) => startServer(body.protocol ?? server.state()?.protocol ?? args.protocol, Boolean(body.fresh)),
  "POST /harness/server/stop": async () => {
    client.stopObservers();
    await server.stop();
  },
  "POST /harness/arm": (body) => model.arm(body.scenario ?? null),
  "POST /harness/run": (body) => run(body),
  "POST /harness/release": (body) => ({ released: model.release(body.gate) }),
  "POST /harness/pacing": (body) => model.setPacing(body.value),
  "POST /harness/model/abort": () => model.abortAll(),
  "POST /harness/reset": () => {
    clearAutomation();
    model.reset();
  },
  "POST /harness/project/reset": () => resetProjectFiles(),
  "POST /harness/fault": (body) => proxy.setFaults(body),
  "POST /harness/fault/drop": () => proxy.dropStreams(),
  "POST /harness/fault/clear": () => proxy.clearFaults(),
  "POST /harness/action": async (body) => {
    const fn = client.actions[body.name];
    if (!fn) throw new Error(`unknown action ${body.name}`);
    return fn(body);
  },
  "GET /harness/state": () => fullState(),
  "GET /harness/history": () => history,
};

const ui = http.createServer(async (req, res) => {
  const url = new URL(req.url, "http://ui");
  if (req.method === "GET" && (url.pathname === "/" || url.pathname === "/index.html")) {
    res.writeHead(200, { "content-type": "text/html; charset=utf-8", "cache-control": "no-store" });
    res.end(readFileSync(path.join(HERE, "ui.html")));
    return;
  }
  if (req.method === "GET" && url.pathname === "/harness/events") {
    res.writeHead(200, { "content-type": "text/event-stream", "cache-control": "no-cache", connection: "keep-alive" });
    res.write(`data: ${JSON.stringify({ seq: 0, at: Date.now(), type: "hello", data: fullState() })}\n\n`);
    clients.add(res);
    req.on("close", () => clients.delete(res));
    return;
  }
  if (url.pathname.startsWith("/v1/")) return model.handle(req, res);
  // Deterministic pages for the webfetch scenario.
  if (req.method === "GET" && url.pathname.startsWith("/docs/")) {
    const file = path.join(HERE, "fixtures", path.normalize(url.pathname).replace(/^\/+/, ""));
    if (!file.startsWith(path.join(HERE, "fixtures")) || !existsSync(file)) {
      res.writeHead(404);
      res.end("not found");
      return;
    }
    res.writeHead(200, { "content-type": "text/markdown; charset=utf-8" });
    res.end(readFileSync(file));
    return;
  }
  const handler = routes[`${req.method} ${url.pathname}`];
  if (!handler) {
    res.writeHead(404, { "content-type": "application/json" });
    res.end(JSON.stringify({ error: "not found" }));
    return;
  }
  try {
    const result = await handler(req.method === "POST" ? await readJson(req) : {});
    res.writeHead(200, { "content-type": "application/json" });
    res.end(JSON.stringify(result ?? { ok: true }));
  } catch (error) {
    res.writeHead(400, { "content-type": "application/json" });
    res.end(JSON.stringify({ error: error.message }));
  }
});

// The fake model gets its own loopback listener so the server reaches it directly.
const modelListener = http.createServer((req, res) => model.handle(req, res));

setInterval(() => emit("proxy.state", proxy.state()), 2000).unref();

await new Promise((resolve) => modelListener.listen(args.modelPort, "127.0.0.1", resolve));
await new Promise((resolve) => ui.listen(args.uiPort, "127.0.0.1", resolve));
await proxy.listen(args.appPort, "0.0.0.0");

console.log(`Harness UI      http://127.0.0.1:${args.uiPort}`);
console.log(`Apps connect to http://127.0.0.1:${args.appPort} (iOS sim) / http://10.0.2.2:${args.appPort} (Android emulator)`);
console.log(`Credentials     opencode / ${password}`);
console.log(`State dir       ${args.stateDir}`);

try {
  const state = await startServer(args.protocol, args.fresh);
  console.log(`opencode ${state.protocol} ready (pid ${state.pid}), project ${state.project}`);
} catch (error) {
  console.error(`opencode failed to start: ${error.message}`);
}

const shutdown = async () => {
  client.stopObservers();
  await server.stop();
  process.exit(0);
};
process.on("SIGINT", shutdown);
process.on("SIGTERM", shutdown);
