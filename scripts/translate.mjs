#!/usr/bin/env node
// Translates OpenCode Mobile's text on both platforms with a model, through `opencode run`.
//
// Usage:
//   node scripts/translate.mjs all                  fill in what every language is missing
//   node scripts/translate.mjs de fr                some languages (Android or iOS codes)
//   node scripts/translate.mjs all --redo <key>     translate a key again after its English changed meaning
//   node scripts/translate.mjs --accept <key>       keep a key's translations after an English typo fix
//   node scripts/translate.mjs all --review         a second model checks every translation and fixes wrong ones
//   node scripts/translate.mjs de --dry-run         print the first prompt and the work list, call no model
//   node scripts/translate.mjs all --server <url>   send every call to one opencode server
//   node scripts/translate.mjs all --handoff <dir>  write the requests as files for other agents
//   node scripts/translate.mjs all --answers <dir>  read those agents' answers instead of calling a model
//
// Each key is translated once and written to both apps when their English matches:
// android/app/src/main/res/values-<code>/strings.xml and the iOS Localizable.xcstrings.
// l10n/source-hashes.json records the English each key was translated from, so a key whose
// English changed is translated again on the next run and scripts/check-translations.mjs
// flags it until then.
//
// A request carries each string's context: its key, the comments above it in strings.xml and
// in the catalog, the code lines that use it, its neighbours on the same screen, the glossary
// (l10n/glossary.json), the desktop app's terms (l10n/desktop-terms.json) and the language's
// existing translations from the same area. Every answer is checked like
// check-translations.mjs checks the files; a string that fails is asked for again and left
// out if it still fails (it shows in English and the check lists it).
//
// Models: --model (default claude-agent/sonnet) translates, --review-model (default
// claude-agent/opus) reviews. Any model `opencode run -m` accepts works. Calls go to a private
// `opencode serve` that the script restarts every 25 requests, because claude-agent keeps a local
// `claude` process alive per call. Before each request a memory guard waits while free memory is
// under 30% or more than 12 local agent processes run. Default concurrency is 3 (--jobs).
// docs/translating.md has the details.

import { execFileSync, spawn } from "node:child_process";
import { mkdtempSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync, mkdirSync } from "node:fs";
import { homedir, tmpdir } from "node:os";
import { join } from "node:path";
import { LANGUAGES, resolveLanguages } from "./l10n/languages.mjs";
import {
  ROOT,
  loadUnits,
  textsOf,
  readCatalog,
  writeCatalog,
  readAndroidNeutral,
  readIosNeutral,
  setIosTranslation,
  writeAndroid,
  readLock,
  writeLock,
  parseAndroid,
  ANDROID_ENGLISH,
  IOS_INFOPLIST_CATALOG,
  readInfoPlistCatalog,
  infoPlistHash,
} from "./l10n/strings.mjs";
import { problemsOf } from "./l10n/rules.mjs";

// claude-agent/* runs a local `claude` process per session that stays alive after the call, so
// runs use a private server that is restarted every RESTART_EVERY requests (see PrivateServer).
const DEFAULT_MODEL = "claude-agent/sonnet";
const DEFAULT_REVIEW_MODEL = "claude-agent/opus";
const REVIEW_LOG = join(ROOT, "l10n/review-status.json");

// ---------------------------------------------------------------------------------------------
// Arguments

function parseArgs(argv) {
  const args = { languages: [], redo: new Set(), accept: new Set(), review: false, dryRun: false, model: null, reviewModel: null, jobs: 3, batch: 60, keys: null, limit: Infinity };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    const value = () => argv[++i];
    if (a === "--redo") args.redo.add(value());
    else if (a === "--accept") args.accept.add(value());
    else if (a === "--review") args.review = true;
    else if (a === "--dry-run") args.dryRun = true;
    else if (a === "--model") args.model = value();
    else if (a === "--review-model") args.reviewModel = value();
    else if (a === "--jobs") args.jobs = Number(value());
    else if (a === "--batch") args.batch = Number(value());
    else if (a === "--keys") args.keys = new Set(value().split(","));
    else if (a === "--limit") args.limit = Number(value());
    else if (a === "--server") args.server = value();
    else if (a === "--port") args.port = Number(value());
    else if (a === "--handoff") args.handoff = value();
    else if (a === "--answers") args.answers = value();
    else if (a === "--help" || a === "-h") {
      console.log(readFileSync(new URL(import.meta.url)).toString().split("\n").filter((l) => l.startsWith("//")).map((l) => l.slice(3)).join("\n"));
      process.exit(0);
    } else if (a.startsWith("--")) throw new Error(`Unknown option ${a}`);
    else args.languages.push(a);
  }
  return args;
}

// ---------------------------------------------------------------------------------------------
// Context

// Where each key is used in code: file and the trimmed line, at most two places.
function usageIndex(units) {
  const androidNames = new Map(units.filter((u) => u.android).map((u) => [u.android.name, u.key]));
  const iosKeys = new Set(units.filter((u) => u.ios).map((u) => u.key));
  const index = new Map();
  const add = (key, file, line) => {
    const list = index.get(key) ?? [];
    if (list.length < 2) list.push(`${file}: ${line.trim().slice(0, 180)}`);
    index.set(key, list);
  };
  const walk = (dir, ext, visit) => {
    for (const name of readdirSync(dir)) {
      if (name === "build" || name.startsWith(".")) continue;
      const path = join(dir, name);
      if (statSync(path).isDirectory()) walk(path, ext, visit);
      else if (name.endsWith(ext)) visit(path);
    }
  };
  walk(join(ROOT, "android/app/src/main/java"), ".kt", (path) => {
    const file = path.split("/").pop();
    for (const line of readFileSync(path, "utf8").split("\n")) {
      for (const m of line.matchAll(/R\.(?:string|plurals)\.(\w+)/g)) {
        const key = androidNames.get(m[1]);
        if (key) add(key, file, line);
      }
    }
  });
  for (const dir of ["ios/OpenCode Mobile/OpenCode Mobile", "ios/OpenCodeKit"]) {
    walk(join(ROOT, dir), ".swift", (path) => {
      const file = path.split("/").pop();
      for (const line of readFileSync(path, "utf8").split("\n")) {
        for (const m of line.matchAll(/"([a-z][a-z0-9_]*(?:\.[a-z0-9_]+)+)"/g)) {
          if (iosKeys.has(m[1])) add(m[1], file, line);
        }
      }
    });
  }
  return index;
}

const glossary = JSON.parse(readFileSync(join(ROOT, "l10n/glossary.json"), "utf8"));
const desktopTerms = JSON.parse(readFileSync(join(ROOT, "l10n/desktop-terms.json"), "utf8")).languages;

function englishText(english) {
  return typeof english === "string" ? english : Object.values(english).join(" ");
}

function relevantTerms(language, batchText) {
  const lower = batchText.toLowerCase();
  const glossaryTerms = Object.entries(glossary.terms).filter(([term]) =>
    term.split(/\s*\/\s*/).some((t) => lower.includes(t.toLowerCase().split(" ")[0])),
  );
  const desktop = Object.entries(desktopTerms[language.ios] ?? {}).filter(([term]) =>
    new RegExp(`\\b${term.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}\\b`, "i").test(batchText),
  );
  return { glossaryTerms, desktop };
}

function area(key) {
  return key.split(/[._]/)[0];
}

// ---------------------------------------------------------------------------------------------
// Prompts

const RULES = `You translate the user interface of OpenCode Mobile, a native iPhone and Android app that remotely controls OpenCode, an AI coding agent running on the user's computer. The users are software developers. Translate into {name} ({code}).

Rules:
- Translate the meaning the way a well-made native {name} app would say it, not word for word. Keep the tone short, plain and friendly. Use the UI conventions of {name} mobile apps (imperative verbs or infinitives on buttons, as that language's iOS and Android apps do).
- Read each string's context (key, note, code usage and neighbours) to decide whether it is a button, a title, a label, a status or a sentence, and whether a word is a verb or a noun.
- Keep placeholders exactly: {1}, {2}, … each must appear in your text as often as in the English. You may move them within the sentence. Never add new ones.
- Keep these as written, never translated or transliterated: {keep}. Keep slash commands (/compact), shell commands, file names, paths, code, model and provider names, and text in backticks exactly as written.
- Use the "Desktop terms" below for those words so the app matches the OpenCode desktop app. Follow the "Product terms" meanings.
- Keep line breaks (\\n in the JSON) where the English has them. Keep the ellipsis character … and the middle dot · if the English uses them. Keep typographic quotes natural for {name}.
- Buttons, chips, tabs and short labels must stay short; prefer the shortest natural wording.
- Plurals: when an item has "plural", answer with an object holding exactly these forms: {plurals}. Each form is the full text for that count category of {name} (CLDR rules). The {1}-style placeholder holding the count may be left out of a form only where {name} naturally says the number as a word (for example "one").
- If a string is already language-neutral (a symbol, a number format, a brand name alone), return it unchanged.
- Answer with one JSON object only: {"translations": {"<id>": "<text>" or {"<form>": "<text>"}}}. Include every id. No prose, no code fences.`;

function rulesFor(language) {
  return RULES.replaceAll("{name}", language.name)
    .replaceAll("{code}", language.ios)
    .replaceAll("{keep}", glossary.keep.join(", "))
    .replaceAll("{plurals}", language.plurals.join(", "));
}

function itemFor(unit, text, usage) {
  const item = { id: text.id, english: text.english };
  if (unit.plural) item.plural = true;
  if (text.platform !== "both") item.platform = text.platform === "ios" ? "iPhone app only" : "Android app only";
  if (unit.notes.length) item.note = [...new Set(unit.notes)].join(" / ");
  const uses = usage.get(unit.key);
  if (uses?.length) item.used_in = uses;
  return item;
}

function contextBlock(language, chunkText, existing) {
  const { glossaryTerms, desktop } = relevantTerms(language, chunkText);
  const parts = [];
  if (glossaryTerms.length) parts.push(`Product terms (meanings in this app):\n${glossaryTerms.map(([t, d]) => `- ${t}: ${d}`).join("\n")}`);
  if (desktop.length) parts.push(`Desktop terms (the OpenCode desktop app's ${language.name} words):\n${desktop.map(([t, d]) => `- ${t} → ${d}`).join("\n")}`);
  if (existing.length) parts.push(`Already translated strings from the same screens (match their terms and tone):\n${existing.map(([en, tr]) => `- ${JSON.stringify(en)} → ${JSON.stringify(tr)}`).join("\n")}`);
  return parts.join("\n\n");
}

function translatePrompt(language, items, existing) {
  const chunkText = items.map((i) => englishText(i.english)).join("\n");
  return `${rulesFor(language)}

${contextBlock(language, chunkText, existing)}

Strings to translate (JSON, in screen order):
${JSON.stringify(items, null, 1)}`;
}

const REVIEW_RULES = `You are a native {name} ({code}) localization reviewer for OpenCode Mobile, a native iPhone and Android app that remotely controls OpenCode, an AI coding agent on the user's computer. The users are software developers. Your job is to make the {name} UI read as if a careful native {name} team wrote it.

For each item you get the English, its context (key, note, code usage) and the current translation. Fix a translation when any of these is wrong:
- Meaning: wrong sense for this UI, verb vs noun, a button that reads as a title, a status that reads as a command.
- Grammar: case, gender and number agreement, word order, prepositions, articles. Read each placeholder as filled in with a real value ({1} = a number, a name, a duration, as the note and usage say) and make the sentence correct around it.
- Plurals: each plural form must be the grammatically correct text for numbers in that CLDR category of {name} (for example one = 1, 21; few = 2-4, 22-24; many = 5-20, 25-30 for Slavic languages). Check noun and adjective endings in every form.
- Naturalness: wording a native app would never use, literal calques of English, awkward or machine-like phrasing.
- Consistency: one {name} word per product term across the app ({name} words for session, server, project, agent, model, archive, worktree, permission…), the "Desktop terms" and "Product terms" below, and one register (informal or formal address, as {name} developer apps usually use) applied the same way everywhere.
- Conventions: {name} punctuation, quotation marks, capitalization and spacing; buttons, chips, tabs and short labels kept short.
- Text left in English that a {name} app would translate. Developer terms that {name} developers keep in English (shell, commit, branch, diff, prompt, token…) may stay as they are; never render "shell" as a literal shell/eggshell word.
Leave correct, natural translations alone; do not restyle for taste.

Keep placeholders {1}, {2}, … exactly as in the English (they may move). Keep these as written: {keep}. Plural items need exactly these forms: {plurals}.

Answer with one JSON object only: {"fixes": {"<id>": "<corrected text>" or {"<form>": "<text>"}}, "reasons": {"<id>": "<few words>"}}. Only include ids you changed. Use {"fixes": {}, "reasons": {}} when everything is right. No prose, no code fences.`;

function reviewPrompt(language, items) {
  const chunkText = items.map((i) => englishText(i.english)).join("\n");
  const rules = REVIEW_RULES.replaceAll("{name}", language.name)
    .replaceAll("{code}", language.ios)
    .replaceAll("{keep}", glossary.keep.join(", "))
    .replaceAll("{plurals}", language.plurals.join(", "));
  return `${rules}

${contextBlock(language, chunkText, [])}

Items (JSON):
${JSON.stringify(items, null, 1)}`;
}

// ---------------------------------------------------------------------------------------------
// Model calls

const AGENT = `---
description: Translates and reviews app interface text. Answers with JSON only.
mode: primary
permission:
  "*": deny
---
You translate and review user interface text for a mobile app. You answer with a single JSON object and nothing else.
`;

let workdir;
function makeWorkdir() {
  workdir = mkdtempSync(join(tmpdir(), "opencode-mobile-translate-"));
  mkdirSync(join(workdir, ".opencode/agent"), { recursive: true });
  writeFileSync(join(workdir, ".opencode/agent/translator.md"), AGENT);
  // The translator answers from the prompt alone: turn off the person's MCP servers for these
  // calls so each one doesn't start them.
  const mcp = {};
  try {
    const global = JSON.parse(readFileSync(join(homedir(), ".config/opencode/opencode.json"), "utf8"));
    for (const [name, server] of Object.entries(global.mcp ?? {})) mcp[name] = { ...server, enabled: false };
  } catch {
    // No global config, or not plain JSON: nothing to turn off.
  }
  writeFileSync(join(workdir, "opencode.json"), JSON.stringify({ $schema: "https://opencode.ai/config.json", mcp }, null, 2));
}

// --server points every call at one opencode server (for example a private `opencode serve`),
// so a long run neither loads nor depends on the background service.
let serverArgs = [];
let serverEnv = {};

// A private `opencode serve` the script owns. Providers that start a local agent process per
// session (claude-agent) leave those processes running; restarting the server every
// RESTART_EVERY requests ends them, so memory stays flat over a long run.
const RESTART_EVERY = Number(process.env.L10N_RESTART_EVERY ?? 25);
class PrivateServer {
  constructor(port) {
    this.port = port;
    this.password = Math.random().toString(36).slice(2) + Math.random().toString(36).slice(2);
    this.child = null;
    this.requests = 0;
    this.inFlight = 0;
    this.restarting = null;
  }

  async start() {
    this.child = spawn("opencode", ["serve", "--hostname", "127.0.0.1", "--port", String(this.port)], {
      cwd: workdir,
      env: { ...process.env, PWD: workdir, OPENCODE_SERVER_PASSWORD: this.password },
      stdio: "ignore",
      detached: true,
    });
    serverArgs = ["--server", `http://127.0.0.1:${this.port}`];
    serverEnv = { OPENCODE_SERVER_PASSWORD: this.password };
    const auth = Buffer.from(`opencode:${this.password}`).toString("base64");
    for (let i = 0; i < 60; i++) {
      try {
        const res = await fetch(`http://127.0.0.1:${this.port}/api/info`, { headers: { authorization: `Basic ${auth}` } });
        if (res.ok) return;
      } catch {
        // Not listening yet.
      }
      await new Promise((r) => setTimeout(r, 500));
    }
    throw new Error("the private opencode server didn't start");
  }

  // Ends the server and every process it started (the leaked agent processes included).
  stop() {
    if (!this.child) return;
    const pids = descendants(this.child.pid);
    try {
      process.kill(-this.child.pid, "SIGTERM");
    } catch {
      // Already gone.
    }
    for (const pid of pids) {
      try {
        process.kill(pid, "SIGKILL");
      } catch {
        // Already gone.
      }
    }
    this.child = null;
  }

  // Call before each request: restarts the server once RESTART_EVERY requests are done and no
  // request is in flight. Requests wait while it restarts.
  async before() {
    while (this.restarting) await this.restarting;
    if (this.requests >= RESTART_EVERY) {
      this.restarting = (async () => {
        while (this.inFlight > 0) await new Promise((r) => setTimeout(r, 500));
        this.stop();
        await new Promise((r) => setTimeout(r, 1000));
        await this.start();
        this.requests = 0;
        log(`private server restarted (${machineSummary()})`);
      })();
      await this.restarting;
      this.restarting = null;
    }
    this.requests++;
    this.inFlight++;
  }

  after() {
    this.inFlight--;
  }
}

function descendants(pid) {
  const found = [];
  const queue = [pid];
  while (queue.length) {
    const parent = queue.shift();
    let out = "";
    try {
      out = execFileSync("pgrep", ["-P", String(parent)], { encoding: "utf8" });
    } catch {
      continue;
    }
    for (const child of out.trim().split("\n").filter(Boolean).map(Number)) {
      found.push(child);
      queue.push(child);
    }
  }
  return found;
}

let privateServer = null;

function runOpencode(args, input, timeoutMs = 5 * 60 * 1000) {
  return new Promise((resolve, reject) => {
    const child = spawn("opencode", [...args, ...serverArgs], { cwd: workdir, env: { ...process.env, ...serverEnv, PWD: workdir }, stdio: ["pipe", "pipe", "pipe"] });
    let stdout = "";
    let stderr = "";
    const timer = setTimeout(() => child.kill("SIGKILL"), timeoutMs);
    child.stdout.on("data", (d) => (stdout += d));
    child.stderr.on("data", (d) => (stderr += d));
    child.on("error", reject);
    // The child can exit before reading its prompt; that shows up as its exit, not a crash here.
    child.stdin.on("error", () => {});
    child.on("close", (code) => {
      clearTimeout(timer);
      resolve({ code, stdout, stderr });
    });
    if (input !== undefined) child.stdin.end(input);
    else child.stdin.end();
  });
}

// Errors that mean the opencode server is restarting or busy, not that the answer was wrong.
const UNREACHABLE = /Could not reach server|ECONNREFUSED|ECONNRESET|socket connection was closed|operation timed out/;

async function callModel(model, prompt) {
  if (privateServer) await privateServer.before();
  try {
    return await callModelRetrying(model, prompt);
  } finally {
    if (privateServer) privateServer.after();
  }
}

async function callModelRetrying(model, prompt) {
  for (let wait = 0; ; wait++) {
    try {
      return await callModelOnce(model, prompt);
    } catch (e) {
      if (!UNREACHABLE.test(e.message) || wait >= 40) throw e;
      await new Promise((r) => setTimeout(r, 15000));
    }
  }
}

async function callModelOnce(model, prompt) {
  const { stdout, stderr } = await runOpencode(["run", "--agent", "translator", "--format", "json", "-m", model, "Follow the instructions below."], prompt);
  let text = "";
  let session = null;
  let error = null;
  for (const line of stdout.split("\n")) {
    if (!line.trim()) continue;
    let event;
    try {
      event = JSON.parse(line);
    } catch {
      continue;
    }
    session ??= event.sessionID;
    if (event.type === "text") text += event.part?.text ?? "";
    if (event.type === "error") error = JSON.stringify(event.error ?? event).slice(0, 300);
  }
  // A one-off call shouldn't stay in the session list.
  if (session) await runOpencode(["session", "delete", session], undefined, 30 * 1000);
  const start = text.indexOf("{");
  const end = text.lastIndexOf("}");
  if (start < 0 || end < start) throw new Error(`no JSON in the answer${error ? `: ${error}` : ""} ${stderr.slice(-300)}`);
  return JSON.parse(text.slice(start, end + 1));
}

// ---------------------------------------------------------------------------------------------
// State of one language

class LanguageState {
  constructor(language, units, catalog) {
    this.language = language;
    this.units = units;
    this.catalog = catalog;
    this.android = readAndroidNeutral(language);
    this.dirtyAndroid = false;
  }

  // The current translation of a text, or null when it's missing on any platform it serves.
  current(unit, text) {
    const a = text.platform !== "ios" && unit.android ? this.android.get(unit.android.name) ?? null : undefined;
    const i = text.platform !== "android" && unit.ios ? readIosNeutral(this.catalog, unit, this.language)?.text ?? null : undefined;
    return { android: a, ios: i };
  }

  set(unit, text, neutral, state = "translated") {
    if (text.platform !== "ios" && unit.android) {
      this.android.set(unit.android.name, neutral);
      this.dirtyAndroid = true;
    }
    if (text.platform !== "android" && unit.ios) setIosTranslation(this.catalog, unit, this.language, neutral, state);
  }

  flush() {
    if (this.dirtyAndroid) writeAndroid(this.units, this.language, this.android);
    this.dirtyAndroid = false;
  }
}

// ---------------------------------------------------------------------------------------------
// Translate

function chunksOf(work, size) {
  const chunks = [];
  let current = [];
  let currentArea = null;
  for (const w of work) {
    const a = area(w.unit.key);
    if (current.length >= size || (current.length >= size / 2 && a !== currentArea)) {
      chunks.push(current);
      current = [];
    }
    current.push(w);
    currentArea = a;
  }
  if (current.length) chunks.push(current);
  return chunks;
}

function existingSample(state, chunk, limit = 30) {
  const areas = new Set(chunk.map((w) => area(w.unit.key)));
  const ids = new Set(chunk.map((w) => w.text.id));
  const sample = [];
  for (const unit of state.units) {
    if (!areas.has(area(unit.key)) || unit.plural) continue;
    for (const text of textsOf(unit)) {
      if (ids.has(text.id)) continue;
      const cur = state.current(unit, text);
      const value = cur.android ?? cur.ios;
      if (typeof value === "string" && typeof text.english === "string") sample.push([text.english, value]);
      if (sample.length >= limit) return sample;
    }
  }
  return sample;
}

// Memory guard: before each request, wait while the machine is short of memory or a provider
// has left too many local agent processes running; give up after 10 minutes rather than push on.
const MIN_FREE_PERCENT = 30;
const MAX_AGENT_PROCESSES = 12;

function machineSummary() {
  const { free, agents } = machineState();
  return `${free}% memory free, ${agents} agent processes`;
}

function machineState() {
  let free = 100;
  try {
    const out = execFileSync("memory_pressure", { encoding: "utf8", stdio: ["ignore", "pipe", "ignore"] });
    free = Number(out.match(/free percentage: (\d+)%/)?.[1] ?? 100);
  } catch {
    // Not macOS: rely on the process count alone.
  }
  let agents = 0;
  try {
    agents = execFileSync("pgrep", ["-f", ".local/bin/claude"], { encoding: "utf8" }).trim().split("\n").filter(Boolean).length;
  } catch {
    // pgrep exits 1 when nothing matches.
  }
  return { free, agents };
}

let guardWaitSince = null;
// Off for --handoff and --answers, which call no model.
let guardEnabled = true;
async function waitForHeadroom() {
  for (;;) {
    if (!guardEnabled) return;
    const { free, agents } = machineState();
    if (free >= MIN_FREE_PERCENT && agents <= MAX_AGENT_PROCESSES) {
      guardWaitSince = null;
      return;
    }
    guardWaitSince ??= Date.now();
    if (Date.now() - guardWaitSince > 10 * 60 * 1000) {
      log(`memory guard: ${free}% free, ${agents} agent processes for 10 minutes; stopping`);
      process.exit(3);
    }
    log(`memory guard: ${free}% free, ${agents} agent processes; waiting`);
    await new Promise((r) => setTimeout(r, 15000));
  }
}

async function pool(tasks, jobs) {
  let next = 0;
  const worker = async () => {
    while (next < tasks.length) {
      await waitForHeadroom();
      if (next >= tasks.length) break;
      const task = tasks[next++];
      await task();
    }
  };
  await Promise.all(Array.from({ length: Math.min(jobs, tasks.length) }, worker));
}

async function translate(args, languages, units, catalog, lock) {
  const usage = usageIndex(units);
  const stale = new Set(units.filter((u) => lock[u.key] && lock[u.key] !== u.hash).map((u) => u.key));
  for (const key of args.redo) {
    if (!units.some((u) => u.key === key || u.android?.name === key)) throw new Error(`--redo ${key}: no such key`);
    stale.add(units.find((u) => u.key === key || u.android?.name === key).key);
  }
  const states = new Map(languages.map((l) => [l.ios, new LanguageState(l, units, catalog)]));
  const tasks = [];
  const failures = new Map();
  let copied = 0;
  let total = 0;
  const done = new Map(); // key -> languages finished

  for (const language of languages) {
    const state = states.get(language.ios);
    const work = [];
    for (const unit of units) {
      if (args.keys && !args.keys.has(unit.key)) continue;
      for (const text of textsOf(unit)) {
        const cur = state.current(unit, text);
        const values = [cur.android, cur.ios].filter((v) => v !== undefined);
        const redo = stale.has(unit.key);
        if (!redo && values.every((v) => v !== null)) continue;
        // One platform already has it (a hand fix or an older run): copy it to the other.
        const have = values.find((v) => v !== null);
        if (!redo && have && problemsOf(text.english, have, language, unit.plural).errors.length === 0) {
          state.set(unit, text, have);
          copied++;
          continue;
        }
        work.push({ unit, text });
      }
    }
    if (work.length > args.limit) work.length = args.limit;
    total += work.length;
    for (const chunk of chunksOf(work, args.batch)) {
      tasks.push(async () => {
        const items = chunk.map((w) => itemFor(w.unit, w.text, usage));
        const prompt = translatePrompt(language, items, existingSample(state, chunk));
        if (args.dryRun) return;
        if (args.handoff) return writeHandoff(args.handoff, language, "translate", prompt);
        let pending = chunk;
        for (let attempt = 1; attempt <= (args.answers ? 1 : 3) && pending.length; attempt++) {
          let answer;
          try {
            const p = attempt === 1 ? prompt : translatePrompt(language, pending.map((w) => itemFor(w.unit, w.text, usage)), existingSample(state, pending));
            answer = args.answers ? readAnswers(args.answers, language, "translations") : (await callModel(args.model, p)).translations ?? {};
          } catch (e) {
            log(`${language.ios}: call failed (attempt ${attempt}): ${e.message.slice(0, 200)}`);
            continue;
          }
          const retry = [];
          for (const w of pending) {
            const value = normalizeAnswer(answer[w.text.id], language, w.unit.plural);
            const problems = value === null ? { errors: ["missing from the answer"] } : problemsOf(w.text.english, value, language, w.unit.plural);
            if (problems.errors.length) {
              retry.push(w);
              failures.set(`${language.ios} ${w.text.id}`, problems.errors.join("; "));
            } else {
              failures.delete(`${language.ios} ${w.text.id}`);
              state.set(w.unit, w.text, value);
            }
          }
          pending = retry;
        }
        state.flush();
        writeCatalog(catalog);
        progress.done += chunk.length;
        log(`${language.ios}: ${progress.done}/${progress.total} strings`);
      });
    }
  }

  const progress = { done: 0, total };
  log(`${languages.length} languages: ${total} strings to translate in ${tasks.length} requests, ${copied} copied across platforms.`);
  if (args.dryRun) {
    const first = languages.find(Boolean);
    const state = states.get(first.ios);
    const work = units.filter((u) => !args.keys || args.keys.has(u.key)).slice(0, 8).flatMap((u) => textsOf(u).map((t) => ({ unit: u, text: t })));
    console.log(translatePrompt(first, work.map((w) => itemFor(w.unit, w.text, usage)), existingSample(state, work)));
    return;
  }
  for (const state of states.values()) state.flush();
  writeCatalog(catalog);
  await pool(tasks, args.handoff || args.answers ? 64 : args.jobs);
  if (args.handoff) return handoffSummary(args.handoff);

  // A key is current once every language has it from this English.
  const complete = languages.length === LANGUAGES.length;
  for (const unit of units) {
    const missing = [...failures.keys()].some((f) => f.split(" ")[1].split("#")[0] === unit.key);
    if (complete && !missing) lock[unit.key] = unit.hash;
  }
  writeLock(lock);
  if (failures.size) {
    console.log(`\n${failures.size} strings failed and stay in English for now; run the command again:`);
    for (const [id, why] of [...failures].slice(0, 40)) console.log(`  ${id}: ${why}`);
  }
  console.log(`Done: ${total - failures.size} translated, ${copied} copied, ${failures.size} failed.`);
}

// ---------------------------------------------------------------------------------------------
// Hand-off: another agent answers the requests.
//
//   --handoff <dir>  writes each request as <dir>/<ios code>/<kind>-NNN.prompt.md, with the path
//                    its answer goes to (<kind>-NNN.answer.json), and calls no model
//   --answers <dir>  reads every *.answer.json of a language in <dir> instead of calling a model,
//                    checks each answer like a model's and writes the ones that pass
//
// Subagents can then do the model work in parallel while this script alone writes the string
// files. Strings whose answer is missing or fails a check stay listed; export again for them.

const handoffCounts = new Map();

function writeHandoff(dir, language, kind, prompt) {
  const n = (handoffCounts.get(`${language.ios}/${kind}`) ?? 0) + 1;
  handoffCounts.set(`${language.ios}/${kind}`, n);
  const folder = join(dir, language.ios);
  mkdirSync(folder, { recursive: true });
  const base = join(folder, `${kind}-${String(n).padStart(3, "0")}`);
  writeFileSync(
    `${base}.prompt.md`,
    `${prompt}\n\nWrite only the JSON object to ${base}.answer.json (UTF-8, no code fences). Make sure it parses as JSON: escape double quotes and backslashes inside strings, and write line breaks as \\n.\n`,
  );
}

function handoffSummary(dir) {
  let files = 0;
  for (const n of handoffCounts.values()) files += n;
  console.log(`Wrote ${files} requests for ${handoffCounts.size} language/kind pairs to ${dir}.`);
}

const answerCache = new Map();
function readAnswers(dir, language, field) {
  const key = `${language.ios}/${field}`;
  if (answerCache.has(key)) return answerCache.get(key);
  const merged = {};
  const folder = join(dir, language.ios);
  let names = [];
  try {
    names = readdirSync(folder).filter((n) => n.endsWith(".answer.json")).sort();
  } catch {
    // No answers for this language.
  }
  for (const name of names) {
    try {
      const text = readFileSync(join(folder, name), "utf8");
      const json = JSON.parse(text.slice(text.indexOf("{"), text.lastIndexOf("}") + 1));
      Object.assign(merged, json[field] ?? {});
    } catch (e) {
      log(`${language.ios}: ${name} isn't valid JSON (${e.message.slice(0, 80)}); skipped`);
    }
  }
  answerCache.set(key, merged);
  return merged;
}

function normalizeAnswer(value, language, plural) {
  if (value === undefined || value === null) return null;
  if (plural) {
    if (typeof value === "string") {
      const forms = {};
      for (const q of language.plurals) forms[q] = value;
      return forms;
    }
    if (typeof value !== "object") return null;
    const forms = {};
    for (const q of language.plurals) if (typeof value[q] === "string") forms[q] = value[q];
    return forms;
  }
  return typeof value === "string" ? value : null;
}

// ---------------------------------------------------------------------------------------------
// Review

async function review(args, languages, units, catalog) {
  const usage = usageIndex(units);
  const status = (() => {
    try {
      return JSON.parse(readFileSync(REVIEW_LOG, "utf8"));
    } catch {
      return {};
    }
  })();
  const tasks = [];
  const fixesLog = [];
  for (const language of languages) {
    const state = new LanguageState(language, units, catalog);
    const work = [];
    for (const unit of units) {
      if (args.keys && !args.keys.has(unit.key)) continue;
      for (const text of textsOf(unit)) {
        const cur = state.current(unit, text);
        const value = cur.android ?? cur.ios;
        if (value === null || value === undefined) continue;
        work.push({ unit, text, value });
      }
    }
    const summary = { model: args.reviewModel, reviewed: 0, fixed: 0, date: new Date().toISOString().slice(0, 10) };
    const chunks = chunksOf(work, args.batch);
    let finished = 0;
    for (const chunk of chunks) {
      tasks.push(async () => {
        const items = chunk.map((w) => ({ ...itemFor(w.unit, w.text, usage), translation: w.value }));
        if (args.dryRun) return;
        if (args.handoff) return writeHandoff(args.handoff, language, "review", reviewPrompt(language, items));
        let answer = null;
        for (let attempt = 1; attempt <= 3 && !answer; attempt++) {
          try {
            answer = args.answers
              ? { fixes: readAnswers(args.answers, language, "fixes"), reasons: readAnswers(args.answers, language, "reasons") }
              : await callModel(args.reviewModel, reviewPrompt(language, items));
          } catch (e) {
            log(`${language.ios}: review call failed (attempt ${attempt}): ${e.message.slice(0, 200)}`);
          }
        }
        if (!answer) return;
        const fixes = answer.fixes ?? {};
        for (const w of chunk) {
          if (!(w.text.id in fixes)) continue;
          const value = normalizeAnswer(fixes[w.text.id], language, w.unit.plural);
          if (value === null || JSON.stringify(value) === JSON.stringify(w.value)) continue;
          if (problemsOf(w.text.english, value, language, w.unit.plural).errors.length) continue;
          state.set(w.unit, w.text, value);
          summary.fixed++;
          fixesLog.push({ language: language.ios, id: w.text.id, english: w.text.english, before: w.value, after: value, reason: answer.reasons?.[w.text.id] ?? "" });
        }
        summary.reviewed += chunk.length;
        finished++;
        state.flush();
        writeCatalog(catalog);
        if (finished === chunks.length && !args.keys) {
          status[language.ios] = summary;
          writeFileSync(REVIEW_LOG, `${JSON.stringify(sortKeys(status), null, 2)}\n`);
        }
        log(`${language.ios}: reviewed ${summary.reviewed}/${work.length}, ${summary.fixed} fixed`);
      });
    }
  }
  if (args.dryRun) {
    console.log(`${tasks.length} review requests.`);
    return;
  }
  await pool(tasks, args.handoff || args.answers ? 64 : args.jobs);
  if (args.handoff) return handoffSummary(args.handoff);
  const out = join(args.answers ?? workdir, "review-fixes.json");
  writeFileSync(out, JSON.stringify(fixesLog, null, 1));
  console.log(`Review done: ${fixesLog.length} fixes. Details: ${out}`);
}

function sortKeys(object) {
  return Object.fromEntries(Object.keys(object).sort().map((k) => [k, object[k]]));
}

function log(message) {
  process.stderr.write(`[${new Date().toISOString().slice(11, 19)}] ${message}\n`);
}

// ---------------------------------------------------------------------------------------------

async function main() {
  const args = parseArgs(process.argv.slice(2));
  args.model ??= DEFAULT_MODEL;
  args.reviewModel ??= DEFAULT_REVIEW_MODEL;
  if (args.server) serverArgs = ["--server", args.server];
  if (args.handoff || args.answers) guardEnabled = false;
  const units = loadUnits();
  const lock = readLock();

  if (args.accept.size) {
    for (const key of args.accept) {
      const unit = units.find((u) => u.key === key || u.android?.name === key);
      if (!unit) throw new Error(`--accept ${key}: no such key`);
      lock[unit.key] = unit.hash;
    }
    writeLock(lock);
    console.log(`Accepted the current English of ${args.accept.size} key(s).`);
    if (!args.languages.length) return;
  }

  const languages = resolveLanguages(args.languages);
  const catalog = readCatalog();
  markUntranslatable(catalog);
  makeWorkdir();
  // Unless --server names one, calls go to a private server the script restarts as it goes.
  if (!args.server && !args.dryRun && !args.handoff && !args.answers) {
    privateServer = new PrivateServer(args.port ?? 4620);
    await privateServer.start();
    const stop = () => {
      privateServer?.stop();
      process.exit(130);
    };
    process.on("SIGINT", stop);
    process.on("SIGTERM", stop);
  }
  try {
    if (args.review) await review(args, languages, units, catalog);
    else await translate(args, languages, units, catalog, lock);
    if (!args.keys && !args.dryRun) await infoPlist(args, languages, lock);
  } finally {
    privateServer?.stop();
    if (!args.review) rmSync(workdir, { recursive: true, force: true });
  }
}

// iOS permission prompts (InfoPlist.xcstrings): five sentences, one request per language.
async function infoPlist(args, languages, lock) {
  const { catalog, english } = readInfoPlistCatalog();
  const failures = [];
  let fixed = 0;
  const tasks = languages.map((language) => async () => {
    const items = [];
    for (const [key, value] of Object.entries(english)) {
      const loc = catalog.strings[key].localizations[language.ios]?.stringUnit?.value;
      const stale = lock[`InfoPlist:${key}`] !== infoPlistHash(value) || args.redo.has(key);
      if (args.review ? loc : !loc || stale) items.push({ id: key, english: value, note: "iOS permission prompt: the system shows it when the app first asks for this access.", ...(args.review ? { translation: loc } : {}) });
    }
    if (!items.length) return;
    const prompt = args.review ? reviewPrompt(language, items) : translatePrompt(language, items, []);
    if (args.handoff) return writeHandoff(args.handoff, language, args.review ? "infoplist-review" : "infoplist", prompt);
    for (let attempt = 1; attempt <= (args.answers ? 1 : 3); attempt++) {
      try {
        const field = args.review ? "fixes" : "translations";
        const answer = args.answers ? { [field]: readAnswers(args.answers, language, field) } : await callModel(args.review ? args.reviewModel : args.model, prompt);
        const values = args.review ? answer.fixes ?? {} : answer.translations ?? {};
        let ok = true;
        for (const item of items) {
          const value = values[item.id];
          if (args.review && value === undefined) continue;
          if (typeof value !== "string" || problemsOf(item.english, value, language, false).errors.length) {
            ok = false;
            continue;
          }
          catalog.strings[item.id].localizations[language.ios] = { stringUnit: { state: "translated", value } };
          if (args.review) fixed++;
        }
        if (ok) break;
      } catch (e) {
        log(`${language.ios}: InfoPlist call failed (attempt ${attempt}): ${e.message.slice(0, 200)}`);
      }
    }
    for (const item of items) if (!catalog.strings[item.id].localizations[language.ios]) failures.push(`${language.ios} ${item.id}`);
  });
  await pool(tasks, args.handoff || args.answers ? 64 : args.jobs);
  if (args.handoff) return handoffSummary(args.handoff);
  writeCatalog(catalog, IOS_INFOPLIST_CATALOG);
  if (!args.review && languages.length === LANGUAGES.length && !failures.length) {
    for (const [key, value] of Object.entries(english)) lock[`InfoPlist:${key}`] = infoPlistHash(value);
    writeLock(lock);
  }
  console.log(`InfoPlist.xcstrings: ${failures.length ? `${failures.length} failed (${failures.slice(0, 10).join(", ")})` : "complete"}${args.review ? `, ${fixed} fixed by review` : ""}.`);
}

// Android marks sample data translatable="false"; mirror that in the catalog so Xcode agrees.
function markUntranslatable(catalog) {
  const names = parseAndroid(readFileSync(ANDROID_ENGLISH, "utf8")).filter((e) => !e.translatable).map((e) => e.name);
  for (const [key, entry] of Object.entries(catalog.strings)) {
    if (names.includes(key.replaceAll(".", "_"))) entry.shouldTranslate = false;
  }
}

main().catch((e) => {
  console.error(e.stack ?? e.message);
  process.exit(1);
});
