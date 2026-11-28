// The rules one translation must meet, shared by scripts/translate.mjs (to check a model's
// answer before writing it) and scripts/check-translations.mjs (to check the files in CI).
//
// problemsOf(english, translation, language, plural) returns { errors, warnings }. Both texts are
// neutral (scripts/l10n/strings.mjs): placeholders as {1}, {2}, …

import { readFileSync } from "node:fs";
import { join } from "node:path";
import { SCRIPT_RANGES } from "./languages.mjs";
import { ROOT, neutralTokens } from "./strings.mjs";

const glossary = JSON.parse(readFileSync(join(ROOT, "l10n/glossary.json"), "utf8"));

// Names that must survive translation unchanged whenever the English has them.
const MUST_KEEP = ["OpenCode", "opencode"];

function sameTokens(a, b) {
  return JSON.stringify(neutralTokens(a)) === JSON.stringify(neutralTokens(b));
}

function stripKept(text) {
  let out = text.replace(/\{\d+\}/g, " ").replace(/`[^`]*`/g, " ");
  for (const term of [...glossary.keep].sort((a, b) => b.length - a.length)) out = out.replaceAll(term, " ");
  return out;
}

function hasWords(text) {
  return /\p{L}{2,}/u.test(stripKept(text));
}

function usesScript(text, script) {
  const ranges = SCRIPT_RANGES[script];
  if (!ranges) return true;
  for (const ch of text) {
    const cp = ch.codePointAt(0);
    if (ranges.some(([lo, hi]) => cp >= lo && cp <= hi)) return true;
  }
  return false;
}

function checkOne(english, text, language, label, errors, warnings, { tokensMustMatch = true } = {}) {
  const at = label ? ` (${label})` : "";
  if (typeof text !== "string" || !text.trim()) {
    errors.push(`empty${at}`);
    return;
  }
  if (tokensMustMatch && !sameTokens(english, text)) {
    errors.push(`placeholders ${JSON.stringify(neutralTokens(text))} differ from the English ${JSON.stringify(neutralTokens(english))}${at}`);
  }
  for (const term of MUST_KEEP) {
    const count = (s) => s.split(term).length - 1;
    if (count(english) > count(text)) errors.push(`"${term}" must stay as written${at}`);
  }
  for (const code of english.match(/`[^`]+`/g) ?? []) {
    if (!text.includes(code)) errors.push(`${code} must stay as written${at}`);
  }
  if (/[{}]/.test(text.replace(/\{\d+\}/g, "")) && !/[{}]/.test(english.replace(/\{\d+\}/g, ""))) {
    errors.push(`stray brace${at}`);
  }
  if ((english.match(/\n/g) ?? []).length !== (text.match(/\n/g) ?? []).length) {
    warnings.push(`line breaks differ from the English${at}`);
  }
  if (hasWords(english)) {
    if (text === english && language.android !== "en") warnings.push(`still in English: ${JSON.stringify(text)}${at}`);
    else if (language.script !== "Latin" && !usesScript(text, language.script) && hasWords(text)) {
      warnings.push(`not in ${language.script} script: ${JSON.stringify(text)}${at}`);
    }
  }
  if (english.length <= 18 && text.length > Math.max(28, english.length * 2.6)) {
    warnings.push(`much longer than the English (${text.length} vs ${english.length} characters)${at}`);
  }
}

export function problemsOf(english, translation, language, plural) {
  const errors = [];
  const warnings = [];
  if (!plural) {
    if (typeof translation !== "string") errors.push("expected text, got plural forms");
    else checkOne(english, translation, language, "", errors, warnings);
    return { errors, warnings };
  }
  if (typeof translation !== "object" || translation === null) {
    errors.push("expected plural forms");
    return { errors, warnings };
  }
  const englishOther = english.other ?? Object.values(english).at(-1);
  const allowed = new Set(Object.values(english).flatMap((t) => neutralTokens(t)));
  for (const quantity of language.plurals) {
    const text = translation[quantity];
    if (text === undefined) {
      errors.push(`missing plural form "${quantity}"`);
      continue;
    }
    if (quantity === "other") {
      checkOne(englishOther, text, language, quantity, errors, warnings);
    } else {
      checkOne(englishOther, text, language, quantity, errors, warnings, { tokensMustMatch: false });
      const extra = neutralTokens(text).filter((t) => !allowed.has(t));
      if (extra.length) errors.push(`placeholders {${extra.join("}, {")}} aren't in the English (${quantity})`);
      // Forms other than "other" may drop the count (one placeholder), but not the other arguments.
      const missing = [...new Set(neutralTokens(englishOther))].filter((t) => !neutralTokens(text).includes(t));
      if (missing.length > 1) errors.push(`placeholders {${missing.join("}, {")}} are missing (${quantity})`);
    }
  }
  for (const quantity of Object.keys(translation)) {
    if (!language.plurals.includes(quantity)) errors.push(`plural form "${quantity}" isn't used in ${language.name}`);
  }
  return { errors, warnings };
}
