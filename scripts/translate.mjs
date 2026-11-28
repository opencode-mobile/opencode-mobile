#!/usr/bin/env node
// Keeps OpenCode Mobile's translations up to date on both apps. It calls no model: it finds the
// text that needs translating, writes it as small tasks for the agent working on the change
// (the translate skill, .agents/skills/translate), and applies the agent's answers once they
// pass the same checks as CI.
//
// Usage:
//   node scripts/translate.mjs status [languages]          what is missing or stale, per language
//   node scripts/translate.mjs tasks [languages] [options] write tasks for what is missing or stale
//       --keys a,b     only these keys
//       --redo <key>   translate a key again in every language (its English changed meaning)
//       --review       review tasks for the current translations of --keys (or --all strings)
//       --out <dir>    where to write the tasks (default: a new temporary folder)
//   node scripts/translate.mjs apply <dir>                 check the answers in <dir>, write the ones that pass
//   node scripts/translate.mjs accept <key>…               keep a key's translations after an English typo fix
//
// Languages are Android or iOS codes; none or `all` means every language.
//
// Each key is translated once and written to both apps when their English matches:
// android/app/src/main/res/values-<code>/strings.xml and the iOS Localizable.xcstrings, plus
// InfoPlist.xcstrings for the iOS permission prompts. l10n/source-hashes.json records the English
// each key was translated from, so a key whose English changed shows up as stale until it is
// translated again, and scripts/check-translations.mjs fails CI until then.

import { spawnSync } from "node:child_process";
import { mkdirSync, mkdtempSync, readdirSync, readFileSync, statSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
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

const INFOPLIST_NOTE = "iOS permission prompt: the system shows it when the app first asks for this access.";

function area(key) {
  return key.split(/[._]/)[0];
}

// ---------------------------------------------------------------------------------------------
// Task text
// A code line only for a bare short string with no note ("Open", "{1} queued"), the kind an
// agent would otherwise have to look up or guess.
function itemFor(unit, text, usage) {
  const item = { id: text.id, english: text.english };
  const english = englishText(text.english);
  if (unit.plural) item.plural = true;
  if (text.platform !== "both") item.platform = text.platform === "ios" ? "iPhone app only" : "Android app only";
  // A heading that only repeats the English ("Changes" above changes.title) says nothing.
  const notes = [...new Set(unit.notes)].filter((n) => n.toLowerCase() !== english.toLowerCase());
  if (notes.length) item.note = notes.join(" / ");
  const use = usage?.get(unit.key)?.[0];
  const words = (typeof text.english === "string" ? text.english : text.english.other ?? english).split(/\s+/).length;
  if (use && !item.note && words <= 3) item.used_in = use;
  return item;
}

// sections: [{ language, ids (null for every string), current ({id: translation}, for a review) }]
// A task is data only; how to translate or review lives in the translate skill, which the agent
// loads once rather than reading again in every task.
function taskText(kind, items, sections, answerPath) {
  const english = new Map(items.map((i) => [i.id, englishText(i.english)]));
  const all = [...english.values()].join("\n");
  const product = glossaryFor(all);
  const keep = glossary.keep.filter((term) => all.includes(term));
  const title = kind.includes("review") ? "Review task" : "Translation task";
  const parts = [
    `# ${title}: OpenCode Mobile${kind.startsWith("infoplist") ? " iOS permission prompts" : ""}

Work it with the translate skill (.agents/skills/translate/SKILL.md).
Answer file: ${answerPath}
Check: node scripts/l10n/check-answer.mjs ${answerPath}`,
  ];
  if (keep.length) parts.push(`Keep as written: ${keep.join(", ")}`);
  if (product.length) parts.push(`Product terms (meanings in this app):\n${product.map(([t, d]) => `- ${t}: ${d}`).join("\n")}`);
  parts.push(`Strings (one JSON object per line, in screen order):\n${items.map((i) => JSON.stringify(i)).join("\n")}`);
  const plural = items.some((i) => i.plural);
  parts.push(`Languages:\n\n${sections.map((s) => languageSection(s, english, plural)).join("\n\n")}`);
  return parts.join("\n\n");
}

function languageSection({ language, ids, current }, english, plural) {
  const text = (ids ?? [...english.keys()]).map((id) => english.get(id)).join("\n");
  const { chosen, desktop } = termsFor(language, text);
  const address = formOfAddress(language);
  const pairs = (list) => list.map(([a, b]) => `${a} → ${b}`).join("; ");
  const lines = [`### ${language.name} (${language.ios})`];
  if (plural) lines.push(`Plural forms: ${language.plurals.join(", ")}`);
  if (address) lines.push(`Form of address: ${address}`);
  if (chosen.length) lines.push(`Words: ${pairs(chosen)}`);
  if (desktop.length) lines.push(`Desktop app words: ${pairs(desktop)}`);
  if (current) lines.push(`Current translations: ${JSON.stringify(current)}`);
  else if (ids) lines.push(`Only these ids: ${ids.join(", ")}`);
  return lines.join("\n");
}

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
// Splitting the work into tasks

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
function planRequests(workByLanguage, batch = 60) {
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
function itemsOf(sections, usage) {
  const items = new Map();
  for (const s of sections) for (const w of s.work) if (!items.has(w.text.id)) items.set(w.text.id, itemFor(w.unit, w.text, usage));
  return [...items.values()];
}

function idsOf(section, items) {
  return section.work.length === items.length ? null : section.work.map((w) => w.text.id);
}

// ---------------------------------------------------------------------------------------------
// Finding the work
//
// l10n/source-hashes.json records the English each key was translated from. A key is stale when
// its English changed since (or --redo names it): every language translates it again. A key with
// no entry is new: only the languages that lack it translate it. A key that is translated
// everywhere but has no entry (a hand translation) is unrecorded; `accept` records it.

function findUnit(units, key) {
  const unit = units.find((u) => u.key === key || u.android?.name === key);
  if (!unit) throw new Error(`${key}: no such key`);
  return unit;
}

// --redo keys split into string keys and iOS permission prompt keys.
function splitRedo(units, redo) {
  const plist = readInfoPlistCatalog().english;
  const plistKeys = new Set([...redo].filter((key) => key in plist));
  const unitKeys = new Set([...redo].filter((key) => !plistKeys.has(key)).map((key) => findUnit(units, key).key));
  return { unitKeys, plistKeys };
}

// Strings each language needs translated. A string one platform already has is copied to the
// other when it passes the checks, rather than translated again.
function translateWork(units, catalog, languages, lock, { keys, redo }) {
  const stale = new Set(units.filter((u) => lock[u.key] && lock[u.key] !== u.hash).map((u) => u.key));
  for (const key of redo) stale.add(key);
  const states = new Map(languages.map((l) => [l.ios, new LanguageState(l, units, catalog)]));
  const work = new Map();
  let copied = 0;
  for (const language of languages) {
    const state = states.get(language.ios);
    const list = [];
    for (const unit of units) {
      if (keys && !keys.has(unit.key)) continue;
      for (const text of textsOf(unit)) {
        const cur = state.current(unit, text);
        const values = [cur.android, cur.ios].filter((v) => v !== undefined);
        if (!stale.has(unit.key) && values.every((v) => v !== null)) continue;
        const have = values.find((v) => v !== null);
        if (!stale.has(unit.key) && have && problemsOf(text.english, have, language, unit.plural).errors.length === 0) {
          state.set(unit, text, have);
          copied++;
          continue;
        }
        list.push({ unit, text });
      }
    }
    work.set(language, list);
  }
  return { work, states, stale, copied };
}

function infoPlistWork(languages, lock, redo) {
  const { catalog, english } = readInfoPlistCatalog();
  const stale = new Set(Object.entries(english).filter(([key, value]) => (lock[`InfoPlist:${key}`] && lock[`InfoPlist:${key}`] !== infoPlistHash(value)) || redo.has(key)).map(([key]) => key));
  const work = [];
  for (const language of languages) {
    const ids = Object.keys(english).filter((key) => stale.has(key) || !catalog.strings[key].localizations[language.ios]?.stringUnit?.value);
    if (ids.length) work.push({ language, ids });
  }
  return { english, work, stale };
}

// Keys translated everywhere that source-hashes.json doesn't record: CI fails them until `accept`.
function unrecorded(units, catalog, lock) {
  const states = LANGUAGES.map((l) => new LanguageState(l, units, catalog));
  const keys = units.filter((u) => !lock[u.key] && states.every((s) => textsOf(u).every((t) => Object.values(s.current(u, t)).every((v) => v !== null)))).map((u) => u.key);
  const plist = readInfoPlistCatalog();
  for (const key of Object.keys(plist.english)) {
    if (!lock[`InfoPlist:${key}`] && LANGUAGES.every((l) => plist.catalog.strings[key].localizations[l.ios]?.stringUnit?.value)) keys.push(key);
  }
  return keys;
}

// ---------------------------------------------------------------------------------------------
// status

function status(languages) {
  const units = loadUnits();
  const catalog = readCatalog();
  const lock = readLock();
  const { work, stale } = translateWork(units, catalog, languages, lock, { keys: null, redo: new Set() });
  const plist = infoPlistWork(languages, lock, new Set());
  const keys = new Set();
  let total = 0;
  for (const [language, list] of work) {
    const n = list.length + (plist.work.find((w) => w.language === language)?.ids.length ?? 0);
    if (n) console.log(`${language.ios}: ${n}`);
    total += n;
    for (const w of list) keys.add(w.unit.key);
  }
  const loose = unrecorded(units, catalog, lock);
  if (loose.length) console.log(`Translated everywhere but not recorded in l10n/source-hashes.json (CI fails them): ${loose.join(", ")}. If their translations match the current English, run: node scripts/translate.mjs accept ${loose.join(" ")}`);
  if (!total) return console.log("Nothing to translate: every language is up to date.");
  console.log(`\n${total} strings to translate${stale.size ? `; ${stale.size} key(s) are stale because their English changed` : ""}.`);
  console.log(`Keys: ${[...keys].slice(0, 30).join(", ")}${keys.size > 30 ? ` and ${keys.size - 30} more` : ""}`);
  if (plist.work.length) console.log("Plus iOS permission prompts (InfoPlist.xcstrings).");
}

// ---------------------------------------------------------------------------------------------
// tasks
//
// A task holds what most strings need: each string once with its notes (and one code line when
// it is a bare short string with no note), then per language its form of address and its words
// for the terms the strings use. The agent pulls more with scripts/l10n/lookup.mjs where a string
// needs it, and checks its answers with scripts/l10n/check-answer.mjs.

// Small enough to print in full, so the agent reads the tasks without opening each file.
const PRINT_LIMIT = 16000;

function writeTask(out, counts, kind, { items, specs }) {
  const folderName = specs.length === 1 ? specs[0].language.ios : "multi";
  const n = (counts.get(`${folderName}/${kind}`) ?? 0) + 1;
  counts.set(`${folderName}/${kind}`, n);
  mkdirSync(join(out, folderName), { recursive: true });
  const base = join(out, folderName, `${kind}-${String(n).padStart(3, "0")}`);
  const task = {
    kind,
    strings: Object.fromEntries(items.map((i) => [i.id, { english: i.english, plural: !!i.plural }])),
    languages: Object.fromEntries(specs.map((s) => [s.language.ios, s.ids ?? items.map((i) => i.id)])),
  };
  writeFileSync(`${base}.task.json`, `${JSON.stringify(task)}\n`);
  writeFileSync(`${base}.prompt.md`, `${taskText(kind, items, specs, `${base}.answer.json`)}\n`);
  return `${base}.prompt.md`;
}

function tasks(languages, args) {
  const units = loadUnits();
  const catalog = readCatalog();
  markUntranslatable(catalog);
  const lock = readLock();
  const usage = usageIndex(units);
  const redo = splitRedo(units, args.redo);
  const out = args.out ?? mkdtempSync(join(tmpdir(), "opencode-mobile-l10n-"));
  mkdirSync(out, { recursive: true });
  if (readdirSync(out).length) throw new Error(`${out} isn't empty; give tasks a new folder`);
  const counts = new Map();
  const files = [];
  // What apply needs to update source-hashes.json: the keys these tasks cover, and which of them
  // were stale, so a key whose tasks didn't reach every language stays stale.
  const plan = { kind: args.review ? "review" : "translate", languages: languages.map((l) => l.ios), keys: [], stale: [], infoplist: [], infoplistStale: [] };

  if (args.review) {
    if (!args.keys && !args.all) throw new Error("tasks --review needs --keys a,b, or --all to review every string (a large run)");
    const work = new Map();
    for (const language of languages) {
      const state = new LanguageState(language, units, catalog);
      const list = [];
      for (const unit of units) {
        if (args.keys && !args.keys.has(unit.key)) continue;
        for (const text of textsOf(unit)) {
          const cur = state.current(unit, text);
          const value = cur.android ?? cur.ios;
          if (value !== null && value !== undefined) list.push({ unit, text, value });
        }
      }
      work.set(language, list);
    }
    for (const sections of planRequests(work)) {
      const items = itemsOf(sections, usage);
      const specs = sections.map((s) => ({ language: s.language, ids: idsOf(s, items), current: Object.fromEntries(s.work.map((w) => [w.text.id, w.value])) }));
      files.push(writeTask(out, counts, "review", { items, specs }));
    }
  } else {
    const { work, states, stale, copied } = translateWork(units, catalog, languages, lock, { keys: args.keys, redo: redo.unitKeys });
    const covered = new Set([...work.values()].flatMap((list) => list.map((w) => w.unit.key)));
    plan.keys = [...covered];
    plan.stale = [...covered].filter((key) => stale.has(key));
    for (const state of states.values()) state.flush();
    if (copied) {
      writeCatalog(catalog);
      console.log(`Copied ${copied} strings that one platform already had to the other (written to the string files).`);
    }
    for (const sections of planRequests(work)) {
      const items = itemsOf(sections, usage);
      files.push(writeTask(out, counts, "translate", { items, specs: sections.map((s) => ({ language: s.language, ids: idsOf(s, items) })) }));
    }
  }

  // iOS permission prompts: with every string (no --keys), or when --redo names one.
  if (!args.keys || redo.plistKeys.size) {
    const plist = args.review ? infoPlistReviewWork(languages) : infoPlistWork(languages, lock, redo.plistKeys);
    if (!args.review) {
      plan.infoplist = [...new Set(plist.work.flatMap((w) => w.ids))];
      plan.infoplistStale = plan.infoplist.filter((key) => plist.stale.has(key));
    }
    const items = Object.entries(plist.english).map(([id, value]) => ({ id, english: value, note: INFOPLIST_NOTE }));
    const per = Math.max(1, Math.min(MAX_LANGUAGES, Math.floor(MAX_STRINGS / items.length)));
    for (let i = 0; i < plist.work.length; i += per) {
      const specs = plist.work.slice(i, i + per).map((w) => ({ language: w.language, ids: w.ids.length === items.length ? null : w.ids, ...(w.current ? { current: w.current } : {}) }));
      files.push(writeTask(out, counts, args.review ? "infoplist-review" : "infoplist", { items, specs }));
    }
  }

  writeFileSync(join(out, "plan.json"), `${JSON.stringify(plan, null, 2)}\n`);
  if (!files.length) return console.log(args.review ? "Nothing to review." : "Nothing to translate: every language is up to date.");
  const texts = files.map((f) => readFileSync(f, "utf8"));
  const size = texts.reduce((n, t) => n + t.length, 0);
  console.log(`${files.length} task(s), ${Math.round(size / 1000)}k characters, in ${out}`);
  if (size <= PRINT_LIMIT) for (const text of texts) console.log(`\n${text}`);
  else for (const f of files) console.log(`  ${f}`);
  console.log(`\nWhen every answer is written: node scripts/translate.mjs apply ${out}`);
}

function infoPlistReviewWork(languages) {
  const { catalog, english } = readInfoPlistCatalog();
  const work = [];
  for (const language of languages) {
    const current = {};
    for (const key of Object.keys(english)) {
      const value = catalog.strings[key].localizations[language.ios]?.stringUnit?.value;
      if (value) current[key] = value;
    }
    if (Object.keys(current).length) work.push({ language, ids: Object.keys(current), current });
  }
  return { english, work };
}

// ---------------------------------------------------------------------------------------------
// apply

function taskFiles(dir) {
  const found = [];
  for (const folder of readdirSync(dir)) {
    if (!statSync(join(dir, folder)).isDirectory()) continue;
    for (const name of readdirSync(join(dir, folder)).filter((n) => n.endsWith(".task.json")).sort()) {
      found.push(join(dir, folder, name.replace(/\.task\.json$/, "")));
    }
  }
  return found;
}

function runScript(script, ...scriptArgs) {
  return spawnSync(process.execPath, [join(ROOT, script), ...scriptArgs], { cwd: ROOT, stdio: "inherit" }).status;
}

// Checks the answers (check-answer.mjs: errors and hints), writes the ones that pass, updates
// source-hashes.json, then runs check-translations.mjs.
function apply(dir) {
  const plan = JSON.parse(readFileSync(join(dir, "plan.json"), "utf8"));
  runScript("scripts/l10n/check-answer.mjs", dir);
  const units = loadUnits();
  const catalog = readCatalog();
  markUntranslatable(catalog);
  const lock = readLock();
  const plist = readInfoPlistCatalog();
  const byId = new Map(units.flatMap((unit) => textsOf(unit).map((text) => [text.id, { unit, text }])));
  const states = new Map();
  const stateOf = (language) => {
    if (!states.has(language.ios)) states.set(language.ios, new LanguageState(language, units, catalog));
    return states.get(language.ios);
  };
  const failures = [];
  const unanswered = [];
  const failedKeys = new Set();
  let written = 0;

  for (const base of taskFiles(dir)) {
    const task = JSON.parse(readFileSync(`${base}.task.json`, "utf8"));
    const reviewing = task.kind.includes("review");
    const infoplist = task.kind.startsWith("infoplist");
    const codes = Object.keys(task.languages);
    let answer;
    try {
      answer = parseAnswerText(readFileSync(`${base}.answer.json`, "utf8"));
    } catch (e) {
      unanswered.push(`${base}.answer.json (${e.code === "ENOENT" ? "missing" : "not valid JSON"})`);
      for (const ids of Object.values(task.languages)) for (const id of ids) failedKeys.add(id.split("#")[0]);
      continue;
    }
    for (const code of codes) {
      const [language] = resolveLanguages([code]);
      const got = answerFor(answer, reviewing ? "fixes" : "translations", language, codes.length === 1);
      for (const id of task.languages[code]) {
        const plural = task.strings[id].plural;
        if (got[id] === undefined) {
          if (!reviewing) {
            failures.push(`${code} ${id}: missing from the answer`);
            failedKeys.add(id.split("#")[0]);
          }
          continue;
        }
        const value = normalizeAnswer(got[id], language, plural);
        const problems = value === null ? [`expected ${plural ? "plural forms" : "text"}`] : problemsOf(task.strings[id].english, value, language, plural).errors;
        if (problems.length) {
          failures.push(`${code} ${id}: ${problems.join("; ")}`);
          failedKeys.add(id.split("#")[0]);
          continue;
        }
        if (infoplist) plist.catalog.strings[id].localizations[language.ios] = { stringUnit: { state: "translated", value } };
        else {
          const { unit, text } = byId.get(id) ?? {};
          if (!unit) {
            failures.push(`${code} ${id}: no such string any more`);
            continue;
          }
          stateOf(language).set(unit, text, value);
        }
        written++;
      }
    }
  }

  for (const state of states.values()) state.flush();
  writeCatalog(catalog);
  writeCatalog(plist.catalog, IOS_INFOPLIST_CATALOG);

  if (plan.kind === "translate") {
    // Record a key these tasks covered once every language has it. A stale key also needs these
    // tasks to have covered every language, or its other languages still hold the old English.
    const everyLanguage = plan.languages.length === LANGUAGES.length;
    const allStates = LANGUAGES.map(stateOf);
    for (const key of plan.keys) {
      const unit = units.find((u) => u.key === key);
      if (!unit || failedKeys.has(key) || (plan.stale.includes(key) && !everyLanguage)) continue;
      if (allStates.every((s) => textsOf(unit).every((t) => Object.values(s.current(unit, t)).every((v) => v !== null)))) lock[key] = unit.hash;
    }
    for (const key of plan.infoplist) {
      if (failedKeys.has(key) || (plan.infoplistStale.includes(key) && !everyLanguage)) continue;
      if (LANGUAGES.every((l) => plist.catalog.strings[key].localizations[l.ios]?.stringUnit?.value)) lock[`InfoPlist:${key}`] = infoPlistHash(plist.english[key]);
    }
    writeLock(lock);
  }

  console.log(`\n${plan.kind === "review" ? "Applied" : "Wrote"} ${written} strings.`);
  for (const line of unanswered) console.log(`  no answer: ${line}`);
  for (const line of failures.slice(0, 40)) console.log(`  failed: ${line}`);
  if (failures.length > 40) console.log(`  … and ${failures.length - 40} more`);
  if (unanswered.length || failures.length) {
    console.log("Fix those answers and apply again; the strings that passed are written.");
    process.exitCode = 1;
    return;
  }
  if (runScript("scripts/check-translations.mjs") !== 0) process.exitCode = 1;
}

// ---------------------------------------------------------------------------------------------

function accept(keys) {
  const units = loadUnits();
  const lock = readLock();
  for (const key of keys) {
    const unit = findUnit(units, key);
    lock[unit.key] = unit.hash;
  }
  writeLock(lock);
  console.log(`Kept the translations of ${keys.length} key(s) for their current English.`);
}

function parseTaskArgs(argv) {
  const args = { languages: [], keys: null, redo: new Set(), review: false, all: false, out: null };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === "--keys") args.keys = new Set(argv[++i].split(",").map((k) => k.trim()).filter(Boolean));
    else if (a === "--redo") args.redo.add(argv[++i]);
    else if (a === "--review") args.review = true;
    else if (a === "--all") args.all = true;
    else if (a === "--out") args.out = argv[++i];
    else if (a.startsWith("--")) throw new Error(`Unknown option ${a}`);
    else args.languages.push(a);
  }
  return args;
}

function usage() {
  console.log(readFileSync(new URL(import.meta.url), "utf8").split("\n").slice(1).filter((l) => l.startsWith("//")).map((l) => l.slice(3)).join("\n"));
}

function main() {
  const [command, ...rest] = process.argv.slice(2);
  if (command === "status") return status(resolveLanguages(rest));
  if (command === "tasks") {
    const args = parseTaskArgs(rest);
    const units = loadUnits();
    if (args.keys) args.keys = new Set([...args.keys].map((key) => findUnit(units, key).key));
    return tasks(resolveLanguages(args.languages), args);
  }
  if (command === "apply") {
    if (!rest[0]) throw new Error("apply needs the task folder");
    return apply(rest[0]);
  }
  if (command === "accept") {
    if (!rest.length) throw new Error("accept needs one or more keys");
    return accept(rest);
  }
  usage();
  if (command && command !== "--help" && command !== "help") process.exitCode = 2;
}

try {
  main();
} catch (e) {
  console.error(e.message);
  process.exit(1);
}

// Android marks sample data translatable="false"; mirror that in the catalog so Xcode agrees.
function markUntranslatable(catalog) {
  const names = parseAndroid(readFileSync(ANDROID_ENGLISH, "utf8")).filter((e) => !e.translatable).map((e) => e.name);
  for (const [key, entry] of Object.entries(catalog.strings)) {
    if (names.includes(key.replaceAll(".", "_"))) entry.shouldTranslate = false;
  }
}
