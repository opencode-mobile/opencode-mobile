#!/usr/bin/env node
// Translates OpenCode Mobile's text on both platforms with a model, through `opencode run`.
//
// Usage:
//   node scripts/translate.mjs all                  fill in what every language is missing
//   node scripts/translate.mjs de fr                some languages (Android or iOS codes)
//   node scripts/translate.mjs all --redo <key>     translate a key again after its English changed meaning
//   node scripts/translate.mjs --accept <key>       keep a key's translations after an English typo fix
//   node scripts/translate.mjs all --review         then a second model checks what this run translated
//   node scripts/translate.mjs all --review --keys a,b   a second model checks these keys' translations
//   node scripts/translate.mjs all --review-all     a second model checks every translation (a big run)
//   node scripts/translate.mjs de --dry-run         print the first prompt and what the run would send, call no model
//   node scripts/translate.mjs all --server <url>   send every call to one opencode server
//   node scripts/translate.mjs all --handoff <dir>  write the requests as agent tasks (see Hand-off below)
//   node scripts/translate.mjs all --answers <dir>  check and apply those agents' answers instead of calling a model
//
// Each key is translated once and written to both apps when their English matches:
// android/app/src/main/res/values-<code>/strings.xml and the iOS Localizable.xcstrings.
// l10n/source-hashes.json records the English each key was translated from, so a key whose
// English changed is translated again on the next run and scripts/check-translations.mjs
// flags it until then.
//
// A request carries each string once with its context: its key, the comments above it in
// strings.xml and in the catalog, and one code line that uses it. Then, per language: its plural
// forms, its form of address and its words for the terms those strings use (l10n/terms.json,
// then l10n/desktop-terms.json), plus the glossary (l10n/glossary.json). A few changed strings
// go out for many languages in one request; a big run sends one language per request, with some
// of its translations from the same screens. Every answer is checked like
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
import { usageIndex } from "./l10n/usage.mjs";
import { englishText, formOfAddress, glossary, glossaryFor, termsFor } from "./l10n/terms.mjs";
import { answerFor, normalizeAnswer, parseAnswerText } from "./l10n/answers.mjs";

// claude-agent/* runs a local `claude` process per session that stays alive after the call, so
// runs use a private server that is restarted every RESTART_EVERY requests (see PrivateServer).
const DEFAULT_MODEL = "claude-agent/sonnet";
const DEFAULT_REVIEW_MODEL = "claude-agent/opus";
const REVIEW_LOG = join(ROOT, "l10n/review-status.json");

// ---------------------------------------------------------------------------------------------
// Arguments

function parseArgs(argv) {
  const args = { languages: [], redo: new Set(), accept: new Set(), review: false, reviewAll: false, dryRun: false, model: null, reviewModel: null, jobs: 3, batch: 60, keys: null, limit: Infinity };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    const value = () => argv[++i];
    if (a === "--redo") args.redo.add(value());
    else if (a === "--accept") args.accept.add(value());
    else if (a === "--review") args.review = true;
    else if (a === "--review-all") args.reviewAll = true;
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

function readJsonOr(path, fallback) {
  try {
    return JSON.parse(readFileSync(path, "utf8"));
  } catch (e) {
    if (e.code === "ENOENT") return fallback;
    throw e;
  }
}

function area(key) {
  return key.split(/[._]/)[0];
}

// ---------------------------------------------------------------------------------------------
// Prompts
//
// A request lists its strings once, then one section per language: plural forms, form of
// address, the words for the terms these strings use and, for a review, the current
// translations. A small change goes out as one request for many languages, so the strings and
// their context are sent once instead of once per language.

const TRANSLATE_RULES = `You translate the user interface of OpenCode Mobile, a native iPhone and Android app that remotely controls OpenCode, an AI coding agent running on the user's computer. The users are software developers. Translate every string into each language under "Languages".

Rules:
- Translate the meaning the way a well-made native app in that language would say it, not word for word. Keep the tone short, plain and friendly. Use the language's mobile UI conventions (imperative verbs or infinitives on buttons, as its iOS and Android apps do).
- Read each string's context (id, note, and used_in where given) to decide whether it is a button, a title, a label, a status or a sentence, and whether a word is a verb or a noun.
- Keep placeholders exactly: {1}, {2}, … each must appear in your text as often as in the English. You may move them within the sentence. Never add new ones.
- Keep these as written, never translated or transliterated: {keep}. Keep slash commands (/compact), shell commands, file names, paths, code, model and provider names, and text in backticks exactly as written.
- Use each language's "Words" and form of address so the strings match the rest of the app, and its "Desktop app words" so they match the OpenCode desktop app. Follow the "Product terms" meanings.
- Keep line breaks (\\n in the JSON) where the English has them. Keep the ellipsis character … and the middle dot · if the English uses them. Use the language's own quotation marks.
- Buttons, chips, tabs and short labels must stay short; prefer the shortest natural wording.
- Plurals: when a string has "plural", answer with an object holding exactly the language's plural forms. Each form is the full text for that CLDR count category. The {1}-style placeholder holding the count may be left out of a form only where the language naturally says the number as a word (for example "one").
- If a string is already language-neutral (a symbol, a number format, a brand name alone), return it unchanged.
- Answer with one JSON object only: {"translations": {"<language code>": {"<id>": "<text>" or {"<form>": "<text>"}}}}. Include every language and every id it asks for. No prose, no code fences.`;

const REVIEW_RULES = `You are a native localization reviewer for OpenCode Mobile, a native iPhone and Android app that remotely controls OpenCode, an AI coding agent on the user's computer. The users are software developers. Each language under "Languages" lists its current translations of the strings below; make each language's UI read as if a careful native team wrote it.

Fix a translation when any of these is wrong:
- Meaning: wrong sense for this UI, verb vs noun, a button that reads as a title, a status that reads as a command.
- Grammar: case, gender and number agreement, word order, prepositions, articles. Read each placeholder as filled in with a real value ({1} = a number, a name, a duration, as the note and usage say) and make the sentence correct around it.
- Plurals: each plural form must be the grammatically correct text for numbers in that CLDR category (for example one = 1, 21; few = 2-4, 22-24; many = 5-20, 25-30 for Slavic languages). Check noun and adjective endings in every form.
- Naturalness: wording a native app would never use, literal calques of English, awkward or machine-like phrasing.
- Consistency: the language's "Words" and form of address, its "Desktop app words", and the "Product terms" meanings.
- Conventions: the language's punctuation, quotation marks, capitalization and spacing; buttons, chips, tabs and short labels kept short.
- Text left in English that an app in that language would translate. Developer terms its developers keep in English (shell, commit, branch, diff, prompt, token…) may stay as they are; never render "shell" as a literal shell/eggshell word.
Leave correct, natural translations alone; do not restyle for taste.

Keep placeholders {1}, {2}, … exactly as in the English (they may move). Keep these as written: {keep}. Plural strings need exactly the language's plural forms.

Answer with one JSON object only: {"fixes": {"<language code>": {"<id>": "<corrected text>" or {"<form>": "<text>"}}}, "reasons": {"<language code>": {"<id>": "<few words>"}}}. Only include ids you changed. Use {"fixes": {}, "reasons": {}} when everything is right. No prose, no code fences.`;

// lean (agent tasks): a code line only for a bare short string with no note ("Open",
// "{1} queued"), the kind an agent would otherwise have to look up or guess.
function itemFor(unit, text, usage, lean = false) {
  const item = { id: text.id, english: text.english };
  const english = englishText(text.english);
  if (unit.plural) item.plural = true;
  if (text.platform !== "both") item.platform = text.platform === "ios" ? "iPhone app only" : "Android app only";
  // A heading that only repeats the English ("Changes" above changes.title) says nothing.
  const notes = [...new Set(unit.notes)].filter((n) => n.toLowerCase() !== english.toLowerCase());
  if (notes.length) item.note = notes.join(" / ");
  const use = usage?.get(unit.key)?.[0];
  const words = (typeof text.english === "string" ? text.english : text.english.other ?? english).split(/\s+/).length;
  if (use && (!lean || (!item.note && words <= 3))) item.used_in = use;
  return item;
}

// sections: [{ language, ids (null for every string), existing ([english, translation] pairs),
// current ({id: translation}, for a review) }]
function requestPrompt(rules, items, sections) {
  const english = new Map(items.map((i) => [i.id, englishText(i.english)]));
  const product = glossaryFor([...english.values()].join("\n"));
  const parts = [rules.replaceAll("{keep}", glossary.keep.join(", "))];
  if (product.length) parts.push(`Product terms (meanings in this app):\n${product.map(([t, d]) => `- ${t}: ${d}`).join("\n")}`);
  parts.push(`Strings (one JSON object per line, in screen order):\n${items.map((i) => JSON.stringify(i)).join("\n")}`);
  const plural = items.some((i) => i.plural);
  parts.push(`Languages:\n\n${sections.map((s) => languageSection(s, english, plural)).join("\n\n")}`);
  return parts.join("\n\n");
}

function languageSection({ language, ids, existing, current }, english, plural) {
  const text = (ids ?? [...english.keys()]).map((id) => english.get(id)).join("\n");
  const { chosen, desktop } = termsFor(language, text);
  const address = formOfAddress(language);
  const pairs = (list) => list.map(([a, b]) => `${a} → ${b}`).join("; ");
  const lines = [`### ${language.name} (${language.ios})`];
  if (plural) lines.push(`Plural forms: ${language.plurals.join(", ")}`);
  if (address) lines.push(`Form of address: ${address}`);
  if (chosen.length) lines.push(`Words: ${pairs(chosen)}`);
  if (desktop.length) lines.push(`Desktop app words: ${pairs(desktop)}`);
  if (existing?.length) lines.push(`Already translated on the same screens (match their terms and tone): ${pairs(existing.map(([e, t]) => [JSON.stringify(e), JSON.stringify(t)]))}`);
  if (current) lines.push(`Current translations: ${JSON.stringify(current)}`);
  else if (ids) lines.push(`Only these ids: ${ids.join(", ")}`);
  return lines.join("\n");
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

// Translations one request may carry when it covers many languages, and at most how many
// languages: past that, quality drifts in the last ones.
const MAX_STRINGS = 150;
const MAX_LANGUAGES = 10;
// At most this many different strings to do: send them for many languages per request.
const FEW_STRINGS = 20;

// Splits the work ({language → [{unit, text}]}) into requests of [{language, work}] sections.
// A few changed strings: each request carries them for as many languages as fit in MAX_STRINGS.
// Otherwise one language per request, `batch` strings from the same screens each.
function planRequests(workByLanguage, batch) {
  const busy = [...workByLanguage].filter(([, work]) => work.length);
  const ids = new Set(busy.flatMap(([, work]) => work.map((w) => w.text.id)));
  if (busy.length > 1 && ids.size <= FEW_STRINGS) {
    const per = Math.max(1, Math.min(MAX_LANGUAGES, Math.floor(MAX_STRINGS / ids.size)));
    // Languages with the same script and plural forms side by side, so a request's languages
    // share their decisions about gender, plurals and word order.
    const family = ([language]) => `${language.script} ${language.plurals.join(",")}`;
    busy.sort((a, b) => family(a).localeCompare(family(b)));
    const requests = [];
    for (let i = 0; i < busy.length; i += per) requests.push(busy.slice(i, i + per).map(([language, work]) => ({ language, work })));
    return requests;
  }
  return busy.flatMap(([language, work]) => chunksOf(work, batch).map((chunk) => [{ language, work: chunk }]));
}

// The strings of a request, each once.
function itemsOf(sections, usage, lean = false) {
  const items = new Map();
  for (const s of sections) for (const w of s.work) if (!items.has(w.text.id)) items.set(w.text.id, itemFor(w.unit, w.text, usage, lean));
  return [...items.values()];
}

function idsOf(section, items) {
  return section.work.length === items.length ? null : section.work.map((w) => w.text.id);
}

function existingSample(state, chunk, limit = 12) {
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

// For --dry-run: how much a run would send. About 3.5 characters a token for Latin script;
// other scripts take more tokens per character.
function promptSummary(prompts) {
  const chars = prompts.reduce((n, p) => n + p.length, 0);
  return `${prompts.length} requests, ${Math.round(chars / 1000)}k characters of prompt (about ${Math.round(chars / 3500)}k tokens or more)`;
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

// The strings and per-language sections of a translate request. One language: some of its
// translations from the same screens set the tone. Many languages, or an agent task (lean; the
// agent looks context up): the chosen words do that job at a fraction of the size.
function translateSpec(sections, usage, states, lean = false) {
  const items = itemsOf(sections, usage, lean);
  const sample = !lean && sections.length === 1;
  return { items, specs: sections.map((s) => ({ language: s.language, ids: idsOf(s, items), existing: sample ? existingSample(states.get(s.language.ios), s.work) : [] })) };
}

function translateRequest(sections, usage, states) {
  const { items, specs } = translateSpec(sections, usage, states);
  return requestPrompt(TRANSLATE_RULES, items, specs);
}

// Returns the keys it translated.
async function translate(args, languages, units, catalog, lock) {
  const usage = usageIndex(units);
  const stale = new Set(units.filter((u) => lock[u.key] && lock[u.key] !== u.hash).map((u) => u.key));
  for (const key of args.redo) {
    if (!units.some((u) => u.key === key || u.android?.name === key)) throw new Error(`--redo ${key}: no such key`);
    stale.add(units.find((u) => u.key === key || u.android?.name === key).key);
  }
  const states = new Map(languages.map((l) => [l.ios, new LanguageState(l, units, catalog)]));
  const failures = new Map();
  const translated = new Set();
  let copied = 0;

  const workByLanguage = new Map();
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
    workByLanguage.set(language, work);
  }
  const total = [...workByLanguage.values()].reduce((n, work) => n + work.length, 0);
  const requests = planRequests(workByLanguage, args.batch);
  log(`${languages.length} languages: ${total} strings to translate in ${requests.length} requests, ${copied} copied across platforms.`);
  if (args.dryRun) {
    const prompts = requests.map((sections) => translateRequest(sections, usage, states));
    if (prompts.length) console.log(prompts[0]);
    console.log(`\n${promptSummary(prompts)}`);
    return translated;
  }
  for (const state of states.values()) state.flush();
  writeCatalog(catalog);

  const progress = { done: 0, total };
  const tasks = requests.map((sections) => async () => {
    if (args.handoff) return writeHandoff(args.handoff, "translate", TRANSLATE_RULES, translateSpec(sections, usage, states, true));
    let pending = sections;
    for (let attempt = 1; attempt <= (args.answers ? 1 : 3) && pending.length; attempt++) {
      let answer = null;
      if (!args.answers) {
        try {
          answer = await callModel(args.model, translateRequest(pending, usage, states));
        } catch (e) {
          log(`${pending.map((s) => s.language.ios).join(",")}: call failed (attempt ${attempt}): ${e.message.slice(0, 200)}`);
          continue;
        }
      }
      const retry = [];
      for (const { language, work } of pending) {
        const got = args.answers ? readAnswers(args.answers, language, "translations") : answerFor(answer, "translations", language, pending.length === 1);
        const again = [];
        for (const w of work) {
          const value = normalizeAnswer(got[w.text.id], language, w.unit.plural);
          const problems = value === null ? { errors: ["missing from the answer"] } : problemsOf(w.text.english, value, language, w.unit.plural);
          const id = `${language.ios} ${w.text.id}`;
          if (problems.errors.length) {
            again.push(w);
            failures.set(id, problems.errors.join("; "));
          } else {
            failures.delete(id);
            states.get(language.ios).set(w.unit, w.text, value);
            translated.add(w.unit.key);
          }
        }
        if (again.length) retry.push({ language, work: again });
      }
      pending = retry;
    }
    for (const { language } of sections) states.get(language.ios).flush();
    writeCatalog(catalog);
    progress.done += sections.reduce((n, s) => n + s.work.length, 0);
    log(`${progress.done}/${progress.total} strings`);
  });
  await pool(tasks, args.handoff || args.answers ? 64 : args.jobs);
  if (args.handoff) {
    handoffSummary(args.handoff);
    return translated;
  }

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
  return translated;
}

// ---------------------------------------------------------------------------------------------
// Hand-off: agents answer the requests.
//
//   --handoff <dir>  writes each request as an agent task, <dir>/<ios code>/<kind>-NNN.prompt.md
//                    (one language) or <dir>/multi/<kind>-NNN.prompt.md (many), with
//                    <kind>-NNN.task.json for scripts/l10n/check-answer.mjs, and calls no model
//   --answers <dir>  reads every *.answer.json in <dir> instead of calling a model, checks each
//                    answer like a model's and writes the ones that pass
//
// A task lists each string with its notes and each language's words for the terms it uses,
// nothing more. The agent pulls further context with scripts/l10n/lookup.mjs only for strings
// that need it, and checks its own answer with check-answer.mjs until it has no errors. This
// script stays the only thing that writes the string files.
//
// Give each agent one task file: an agent keeps every file it reads in its context and sends
// all of it again on each turn, so a list of files costs far more than the files.

const handoffCounts = new Map();
const TOOLS = join(ROOT, "scripts/l10n");

function writeHandoff(dir, kind, rules, { items, specs }) {
  const folderName = specs.length === 1 ? specs[0].language.ios : "multi";
  const n = (handoffCounts.get(`${folderName}/${kind}`) ?? 0) + 1;
  handoffCounts.set(`${folderName}/${kind}`, n);
  const folder = join(dir, folderName);
  mkdirSync(folder, { recursive: true });
  const base = join(folder, `${kind}-${String(n).padStart(3, "0")}`);
  const task = {
    kind,
    strings: Object.fromEntries(items.map((i) => [i.id, { english: i.english, plural: !!i.plural }])),
    languages: Object.fromEntries(specs.map((s) => [s.language.ios, s.ids ?? items.map((i) => i.id)])),
  };
  writeFileSync(`${base}.task.json`, `${JSON.stringify(task)}\n`);
  writeFileSync(`${base}.prompt.md`, `${agentTask(rules, items, specs, base)}\n`);
}

function agentTask(rules, items, specs, base) {
  return `${requestPrompt(rules, items, specs)}

## How to work

This task holds what most strings need. When a string is unclear (a single word that could be a verb or a noun, a placeholder whose value you can't tell, a term you don't know how this app says), look it up instead of guessing:

- \`node ${TOOLS}/lookup.mjs <id> <language code>\`: where the string is used in code, the strings next to it on the same screen with their translations, and the language's words for its terms.
- \`node ${TOOLS}/lookup.mjs --find "<English words>" <language code>\`: how the app already translates a word or phrase.

Most strings need no lookup; don't look up every one.

1. Write the JSON answer to ${base}.answer.json.
2. Run \`node ${TOOLS}/check-answer.mjs ${base}.answer.json\`. Fix every error and run it again until it reports none. Read each hint and change the string only where the hint points at a real problem.
3. Change no other file. Reply with one line: done, and any string you were unsure about.`;
}

function handoffSummary(dir) {
  let files = 0;
  for (const n of handoffCounts.values()) files += n;
  console.log(`Wrote ${files} agent tasks to ${dir}. Give each agent one task file, not a list.`);
}

let answerFiles = null;
function loadAnswerFiles(dir) {
  if (answerFiles) return answerFiles;
  answerFiles = [];
  for (const folder of readdirSync(dir)) {
    if (!statSync(join(dir, folder)).isDirectory()) continue;
    for (const name of readdirSync(join(dir, folder)).filter((n) => n.endsWith(".answer.json")).sort()) {
      try {
        const text = readFileSync(join(dir, folder, name), "utf8");
        answerFiles.push({ folder, json: parseAnswerText(text) });
      } catch (e) {
        log(`${folder}/${name} isn't valid JSON (${e.message.slice(0, 80)}); skipped`);
      }
    }
  }
  return answerFiles;
}

const answerCache = new Map();
function readAnswers(dir, language, field) {
  const key = `${language.ios}/${field}`;
  if (answerCache.has(key)) return answerCache.get(key);
  const merged = {};
  for (const { folder, json } of loadAnswerFiles(dir)) {
    if (folder === language.ios || folder === "multi") Object.assign(merged, answerFor(json, field, language, folder === language.ios));
  }
  answerCache.set(key, merged);
  return merged;
}

// ---------------------------------------------------------------------------------------------
// Review

function reviewSpec(sections, usage, lean = false) {
  const items = itemsOf(sections, usage, lean);
  return { items, specs: sections.map((s) => ({ language: s.language, ids: idsOf(s, items), current: Object.fromEntries(s.work.map((w) => [w.text.id, w.value])) })) };
}

function reviewRequest(sections, usage) {
  const { items, specs } = reviewSpec(sections, usage);
  return requestPrompt(REVIEW_RULES, items, specs);
}

// Reviews the translations of `keys` (every key when null). recordStatus: a full review, noted
// per language in l10n/review-status.json.
async function review(args, languages, units, catalog, keys, recordStatus) {
  const usage = usageIndex(units);
  const status = readJsonOr(REVIEW_LOG, {});
  const states = new Map(languages.map((l) => [l.ios, new LanguageState(l, units, catalog)]));
  const workByLanguage = new Map();
  const summaries = new Map();
  for (const language of languages) {
    const state = states.get(language.ios);
    const work = [];
    for (const unit of units) {
      if (keys && !keys.has(unit.key)) continue;
      for (const text of textsOf(unit)) {
        const cur = state.current(unit, text);
        const value = cur.android ?? cur.ios;
        if (value === null || value === undefined) continue;
        work.push({ unit, text, value });
      }
    }
    workByLanguage.set(language, work);
    summaries.set(language.ios, { model: args.reviewModel, reviewed: 0, fixed: 0, date: new Date().toISOString().slice(0, 10), total: work.length });
  }
  const requests = planRequests(workByLanguage, args.batch);
  if (args.dryRun) {
    const prompts = requests.map((sections) => reviewRequest(sections, usage));
    if (prompts.length) console.log(prompts[0]);
    console.log(`\nReview: ${promptSummary(prompts)}`);
    return;
  }
  const fixesLog = [];
  const tasks = requests.map((sections) => async () => {
    if (args.handoff) return writeHandoff(args.handoff, "review", REVIEW_RULES, reviewSpec(sections, usage, true));
    let answer = null;
    if (!args.answers) {
      for (let attempt = 1; attempt <= 3 && !answer; attempt++) {
        try {
          answer = await callModel(args.reviewModel, reviewRequest(sections, usage));
        } catch (e) {
          log(`${sections.map((s) => s.language.ios).join(",")}: review call failed (attempt ${attempt}): ${e.message.slice(0, 200)}`);
        }
      }
      if (!answer) return;
    }
    for (const { language, work } of sections) {
      const fixes = args.answers ? readAnswers(args.answers, language, "fixes") : answerFor(answer, "fixes", language, sections.length === 1);
      const reasons = args.answers ? readAnswers(args.answers, language, "reasons") : answerFor(answer, "reasons", language, sections.length === 1);
      const state = states.get(language.ios);
      const summary = summaries.get(language.ios);
      for (const w of work) {
        if (!(w.text.id in fixes)) continue;
        const value = normalizeAnswer(fixes[w.text.id], language, w.unit.plural);
        if (value === null || JSON.stringify(value) === JSON.stringify(w.value)) continue;
        if (problemsOf(w.text.english, value, language, w.unit.plural).errors.length) continue;
        state.set(w.unit, w.text, value);
        summary.fixed++;
        fixesLog.push({ language: language.ios, id: w.text.id, english: w.text.english, before: w.value, after: value, reason: reasons[w.text.id] ?? "" });
      }
      summary.reviewed += work.length;
      state.flush();
      if (recordStatus && summary.reviewed === summary.total) {
        const { total, ...done } = summary;
        status[language.ios] = done;
        writeFileSync(REVIEW_LOG, `${JSON.stringify(sortKeys(status), null, 2)}\n`);
      }
      log(`${language.ios}: reviewed ${summary.reviewed}/${summary.total}, ${summary.fixed} fixed`);
    }
    writeCatalog(catalog);
  });
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
  for (const key of args.keys ?? []) if (!units.some((u) => u.key === key)) throw new Error(`--keys ${key}: no such key`);

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

  // --review-all, or --review on its own keys or answers: review only. Plain --review: review
  // what this run translates, after translating it.
  const reviewOnly = args.reviewAll || (args.review && (args.keys || args.handoff || args.answers));
  if (reviewOnly && args.handoff && !args.keys && !args.reviewAll) throw new Error("--review --handoff needs --keys, or --review-all to review every string");

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
  let reviewed = false;
  try {
    if (reviewOnly) {
      reviewed = true;
      const full = args.reviewAll && !args.keys;
      await review(args, languages, units, catalog, full ? null : args.keys, full);
      if (full && !args.dryRun) await infoPlist(args, languages, lock, true);
    } else {
      const translated = await translate(args, languages, units, catalog, lock);
      if (!args.keys && !args.dryRun) await infoPlist(args, languages, lock, false);
      if (args.review && translated.size && !args.dryRun) {
        reviewed = true;
        await review(args, languages, units, catalog, translated, false);
      }
    }
  } finally {
    privateServer?.stop();
    // A review leaves review-fixes.json in the work folder.
    if (!reviewed) rmSync(workdir, { recursive: true, force: true });
  }
}

// iOS permission prompts (InfoPlist.xcstrings): five sentences, many languages per request.
async function infoPlist(args, languages, lock, reviewing) {
  const { catalog, english } = readInfoPlistCatalog();
  const note = "iOS permission prompt: the system shows it when the app first asks for this access.";
  const items = Object.entries(english).map(([id, value]) => ({ id, english: value, note }));
  const sections = [];
  for (const language of languages) {
    const ids = [];
    const current = {};
    for (const [key, value] of Object.entries(english)) {
      const loc = catalog.strings[key].localizations[language.ios]?.stringUnit?.value;
      const stale = lock[`InfoPlist:${key}`] !== infoPlistHash(value) || args.redo.has(key);
      if (reviewing ? loc : !loc || stale) {
        ids.push(key);
        current[key] = loc;
      }
    }
    if (ids.length) sections.push({ language, ids: ids.length === items.length ? null : ids, ...(reviewing ? { current } : {}) });
  }
  const failures = [];
  let fixed = 0;
  const field = reviewing ? "fixes" : "translations";
  const rules = reviewing ? REVIEW_RULES : TRANSLATE_RULES;
  const per = Math.max(1, Math.floor(MAX_STRINGS / items.length));
  const groups = [];
  for (let i = 0; i < sections.length; i += per) groups.push(sections.slice(i, i + per));
  const tasks = groups.map((group) => async () => {
    if (args.handoff) return writeHandoff(args.handoff, reviewing ? "infoplist-review" : "infoplist", rules, { items, specs: group });
    let pending = group;
    for (let attempt = 1; attempt <= (args.answers ? 1 : 3) && pending.length; attempt++) {
      let answer = null;
      if (!args.answers) {
        try {
          answer = await callModel(reviewing ? args.reviewModel : args.model, requestPrompt(rules, items, pending));
        } catch (e) {
          log(`InfoPlist call failed (attempt ${attempt}): ${e.message.slice(0, 200)}`);
          continue;
        }
      }
      const retry = [];
      for (const section of pending) {
        const values = args.answers ? readAnswers(args.answers, section.language, field) : answerFor(answer, field, section.language, pending.length === 1);
        let ok = true;
        for (const id of section.ids ?? Object.keys(english)) {
          const value = values[id];
          if (reviewing && value === undefined) continue;
          if (typeof value !== "string" || problemsOf(english[id], value, section.language, false).errors.length) {
            ok = false;
            continue;
          }
          catalog.strings[id].localizations[section.language.ios] = { stringUnit: { state: "translated", value } };
          if (reviewing) fixed++;
        }
        if (!ok) retry.push(section);
      }
      pending = retry;
    }
  });
  await pool(tasks, args.handoff || args.answers ? 64 : args.jobs);
  if (args.handoff) return handoffSummary(args.handoff);
  for (const s of sections) for (const id of s.ids ?? Object.keys(english)) if (!catalog.strings[id].localizations[s.language.ios]) failures.push(`${s.language.ios} ${id}`);
  writeCatalog(catalog, IOS_INFOPLIST_CATALOG);
  if (!reviewing && languages.length === LANGUAGES.length && !failures.length) {
    for (const [key, value] of Object.entries(english)) lock[`InfoPlist:${key}`] = infoPlistHash(value);
    writeLock(lock);
  }
  console.log(`InfoPlist.xcstrings: ${failures.length ? `${failures.length} failed (${failures.slice(0, 10).join(", ")})` : "complete"}${reviewing ? `, ${fixed} fixed by review` : ""}.`);
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
