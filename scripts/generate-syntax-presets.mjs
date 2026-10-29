#!/usr/bin/env node
// Regenerates shared/syntax-presets from the open-source editor themes each preset is named after (#883).
// Theme files come from @shikijs/themes (MIT); credits are in THIRD_PARTY_NOTICES.md.
// Run: npm run themes:generate-syntax && npm run themes:sync
import { readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const REPO_ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const PRESET_DIR = join(REPO_ROOT, "shared/syntax-presets");

// Preset id -> upstream theme per mode. null keeps the hand-tuned FALLBACK values below.
const UPSTREAM = {
  nocturne: { dark: "one-dark-pro", light: "one-light" },
  citrus: { dark: "solarized-dark", light: "solarized-light" },
  paper: { dark: "github-dark-default", light: "github-light-default" },
  neon: { dark: "dracula", light: null },
};

// Dracula has no official light theme; Neon light keeps these Balanced values until #883 decides.
const FALLBACK = {
  neon: {
    light: {
      "syntax-comment": "#6272A4",
      "syntax-keyword": "#9B4DCA",
      "syntax-string": "#8A7A00",
      "syntax-number": "#26824B",
      "syntax-constant": "#26824B",
      "syntax-type": "#6B4DB0",
      "syntax-function": "#007A9B",
      "syntax-property": "#C43A48",
      "syntax-attribute": "#1111D4",
    },
  },
};

// Role -> standard TextMate scopes, tried in order; the first scope any theme rule matches decides the color.
const ROLE_SCOPES = {
  "syntax-keyword": ["keyword.control", "storage.type", "storage.modifier", "keyword"],
  "syntax-string": ["string.quoted.double", "string"],
  "syntax-number": ["constant.numeric.decimal", "constant.numeric"],
  "syntax-constant": ["constant.language", "variable.other.constant", "constant.other", "constant"],
  "syntax-comment": ["comment.line.double-slash", "comment"],
  "syntax-type": ["entity.name.type.class", "entity.name.type", "support.class", "support.type", "entity.name.class"],
  "syntax-function": ["entity.name.function", "support.function"],
  "syntax-property": [
    "variable.other.property",
    "variable.other.object.property",
    "meta.property-name",
    "support.variable.property",
    "variable.other.member",
  ],
  "syntax-attribute": [
    "entity.name.type.annotation",
    "storage.type.annotation",
    "meta.decorator",
    "meta.annotation",
    "entity.name.function.decorator",
    "punctuation.decorator",
  ],
};

// Roles shown as plain text; their tokens follow the editor foreground.
const PLAIN_TOKENS = ["syntax-variable", "syntax-operator", "syntax-punctuation"];

const normalize = (color) => (color ? color.toUpperCase().slice(0, 7) : null);

function rules(theme) {
  const out = [];
  (theme.tokenColors || theme.settings || []).forEach((rule, order) => {
    const foreground = rule.settings?.foreground;
    if (!foreground || !rule.scope) return;
    const scopes = Array.isArray(rule.scope) ? rule.scope : rule.scope.split(",").map((s) => s.trim());
    for (const selector of scopes) {
      // Descendant and exclusion selectors need a scope stack; single scopes cover these roles.
      if (selector.includes(" ") || selector.startsWith("-")) continue;
      out.push({ selector, foreground: normalize(foreground), order });
    }
  });
  return out;
}

// VS Code resolution: the longest selector that is a dot-prefix of the scope wins; later rules win ties.
function resolve(themeRules, scope) {
  let best = null;
  for (const rule of themeRules) {
    if (scope !== rule.selector && !scope.startsWith(`${rule.selector}.`)) continue;
    if (!best || rule.selector.length > best.selector.length || (rule.selector.length === best.selector.length && rule.order > best.order)) {
      best = rule;
    }
  }
  return best;
}

async function tokensFrom(themeId) {
  const theme = (await import(`@shikijs/themes/${themeId}`)).default;
  const foreground = normalize(theme.colors["editor.foreground"]);
  const background = normalize(theme.colors["editor.background"]);
  const themeRules = rules(theme);
  const tokens = {};
  for (const [token, scopes] of Object.entries(ROLE_SCOPES)) {
    const hit = scopes.map((scope) => resolve(themeRules, scope)).find(Boolean);
    // A role the designer left uncolored (or set to the foreground) stays plain.
    tokens[token] = hit ? hit.foreground : foreground;
  }
  for (const token of PLAIN_TOKENS) tokens[token] = foreground;
  tokens["syntax-background"] = background;
  tokens["syntax-foreground"] = foreground;
  return tokens;
}

for (const [id, modes] of Object.entries(UPSTREAM)) {
  const path = join(PRESET_DIR, `${id}.json`);
  const preset = JSON.parse(readFileSync(path, "utf8"));
  for (const [mode, themeId] of Object.entries(modes)) {
    if (themeId) {
      preset[mode] = await tokensFrom(themeId);
    } else {
      // Keep the existing plain-role tokens; replace the colored roles with the fallback values.
      const keep = Object.fromEntries(Object.entries(preset[mode]).filter(([key]) => PLAIN_TOKENS.includes(key)));
      preset[mode] = { ...FALLBACK[id][mode], ...keep };
    }
  }
  writeFileSync(path, `${JSON.stringify(preset, null, 2)}\n`);
  console.log(`${id}: ${Object.entries(modes).map(([mode, theme]) => `${mode} <- ${theme ?? "fallback"}`).join(", ")}`);
}
