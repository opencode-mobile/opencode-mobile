// Tests for the translation tooling: platform text round-trips through neutral form, and the
// rules catch the mistakes a model makes. Run: node --test scripts/l10n/*.test.mjs

import assert from "node:assert/strict";
import { test } from "node:test";
import { languageByCode } from "./languages.mjs";
import { problemsOf } from "./rules.mjs";
import { answerFor, normalizeAnswer } from "./answers.mjs";
import { loadUnits, parseAndroid, readCatalog, setIosTranslation, readIosRaw } from "./strings.mjs";

const de = languageByCode("de");
const ru = languageByCode("ru");
const ja = languageByCode("ja");

test("Android escaping and placeholders survive parsing", () => {
  const [entry] = parseAndroid(`<resources><string name="a">It\\'s %1$d%% of \\"%2$s\\"\\nnext &amp; more</string></resources>`);
  assert.equal(entry.raw, `It's %1$d%% of "%2$s"\nnext & more`);
});

test("every English string converts to neutral form and back unchanged on iOS", () => {
  const units = loadUnits();
  const catalog = readCatalog();
  const fake = { ios: "xx" };
  for (const unit of units.filter((u) => u.ios)) {
    const before = JSON.stringify(readIosRaw(catalog, unit.ios.key, { ios: "en" }));
    setIosTranslation(catalog, unit, fake, unit.ios.english);
    assert.equal(JSON.stringify(readIosRaw(catalog, unit.ios.key, fake)), before, unit.ios.key);
  }
});

test("shared keys are translated once for both platforms", () => {
  const units = loadUnits();
  const shared = units.filter((u) => u.shared);
  assert.ok(shared.length > units.length * 0.9, `${shared.length} of ${units.length} shared`);
});

test("placeholders must match the English", () => {
  assert.deepEqual(problemsOf("Delete {1}?", "{1} löschen?", de, false).errors, []);
  assert.match(problemsOf("Delete {1}?", "Löschen?", de, false).errors[0], /placeholders/);
  assert.match(problemsOf("Delete {1}?", "{1} {2} löschen?", de, false).errors[0], /placeholders/);
});

test("plural forms follow the language's CLDR categories", () => {
  const english = { one: "{1} file", other: "{1} files" };
  assert.deepEqual(problemsOf(english, { one: "{1} файл", few: "{1} файла", many: "{1} файлов", other: "{1} файла" }, ru, true).errors, []);
  assert.match(problemsOf(english, { one: "{1} файл", other: "{1} файла" }, ru, true).errors.join(), /missing plural form "few"/);
  assert.match(problemsOf(english, { one: "{1}", other: "{1}個のファイル" }, ja, true).errors.join(), /isn't used in Japanese/);
});

test("OpenCode and backticked code stay as written", () => {
  assert.match(problemsOf("Open OpenCode", "Ouvrir Opencode", languageByCode("fr"), false).errors[0], /OpenCode/);
  assert.match(problemsOf("Run `opencode pair`", "Führe `opencode koppeln` aus", de, false).errors.join(), /opencode pair/);
});

test("text left in English or in the wrong script is a warning", () => {
  assert.match(problemsOf("Settings", "Settings", de, false).warnings[0], /still in English/);
  assert.match(problemsOf("Settings", "Setting-u", ja, false).warnings[0], /script/);
  assert.deepEqual(problemsOf("Settings", "設定", ja, false).warnings, []);
});

test("a sentence comment describes one string; a heading stays with its own keys", () => {
  const entries = parseAndroid(`<resources>
    <!-- Privacy cover -->
    <string name="privacy_cover_title">A</string>
    <string name="privacy_cover_unlock">B</string>
    <!-- Badge on a new feature. -->
    <string name="privacy_cover_new">C</string>
    <string name="privacy_cover_open">D</string>
    <string name="session_title">E</string>
  </resources>`);
  const notes = Object.fromEntries(entries.map((e) => [e.name, e.notes]));
  assert.deepEqual(notes.privacy_cover_title, ["Privacy cover"]);
  assert.deepEqual(notes.privacy_cover_new, ["Privacy cover", "Badge on a new feature."]);
  assert.deepEqual(notes.privacy_cover_open, ["Privacy cover"]);
  assert.deepEqual(notes.session_title, []);
});

test("answers are read per language, flat only for a one-language task", () => {
  const nested = { translations: { de: { a: "x" } } };
  assert.deepEqual(answerFor(nested, "translations", de, false), { a: "x" });
  assert.deepEqual(answerFor({ translations: { a: "x" } }, "translations", de, true), { a: "x" });
  assert.deepEqual(answerFor({ translations: { a: "x" } }, "translations", de, false), {});
  assert.deepEqual(normalizeAnswer("x", ru, true), { one: "x", few: "x", many: "x", other: "x" });
});
