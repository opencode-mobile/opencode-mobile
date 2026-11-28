// Product terms: the glossary's meanings, each language's chosen words (l10n/terms.json, from
// consistency.mjs --save) and the OpenCode desktop app's words (l10n/desktop-terms.json).

import { readFileSync } from "node:fs";
import { join } from "node:path";
import { ROOT } from "./strings.mjs";

function readJsonOr(path, fallback) {
  try {
    return JSON.parse(readFileSync(path, "utf8"));
  } catch (e) {
    if (e.code === "ENOENT") return fallback;
    throw e;
  }
}

export const glossary = JSON.parse(readFileSync(join(ROOT, "l10n/glossary.json"), "utf8"));
const desktopTerms = JSON.parse(readFileSync(join(ROOT, "l10n/desktop-terms.json"), "utf8")).languages;
const chosenTerms = readJsonOr(join(ROOT, "l10n/terms.json"), { languages: {} }).languages;
export const ADDRESS = "form of address";

// Scripts written without spaces between words: a chosen word is matched whole.
const NO_SPACES = new Set(["ja", "zh-Hans", "zh-Hant", "th", "lo", "km", "my", "dz"]);

export function englishText(english) {
  return typeof english === "string" ? english : Object.values(english).join(" ");
}

function escapeRegExp(text) {
  return text.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

// Whether English text uses a term, plural and verb forms included.
export function mentions(text, term) {
  return new RegExp(`\\b${escapeRegExp(term).replace(/ /g, "\\s+")}(e?s|ed|ing)?\\b`, "i").test(text);
}

export function glossaryFor(text) {
  const lower = text.toLowerCase();
  return Object.entries(glossary.terms).filter(([term]) =>
    term.split(/\s*\/\s*/).some((t) => lower.includes(t.toLowerCase().split(" ")[0])),
  );
}

export function formOfAddress(language) {
  return chosenTerms[language.ios]?.[ADDRESS];
}

// A language's words for the terms some English text uses: its chosen words first, then the
// desktop app's words for terms it hasn't chosen one for.
export function termsFor(language, text) {
  const chosen = Object.entries(chosenTerms[language.ios] ?? {}).filter(([term]) => term !== ADDRESS && mentions(text, term));
  const covered = new Set(chosen.map(([term]) => term.toLowerCase()));
  const desktop = Object.entries(desktopTerms[language.ios] ?? {}).filter(([term]) => !covered.has(term.toLowerCase()) && mentions(text, term));
  return { chosen, desktop };
}

// The start of a chosen word that its inflected forms share.
function stem(word, language) {
  const w = word.trim().toLowerCase();
  if (NO_SPACES.has(language.ios)) return w;
  const first = w.split(/\s+/)[0];
  return first.slice(0, Math.max(3, Math.min(first.length, Math.ceil(first.length * 0.65))));
}

// Terms the English uses whose chosen word the translation doesn't contain. A hint, not an
// error: the English may use the word in another sense.
export function termMisses(english, translation, language) {
  const en = englishText(english);
  const text = englishText(translation).toLowerCase();
  return Object.entries(chosenTerms[language.ios] ?? {})
    .filter(([term, word]) => term !== ADDRESS && mentions(en, term) && !text.includes(stem(word, language)))
    .map(([term, word]) => ({ term, word }));
}
