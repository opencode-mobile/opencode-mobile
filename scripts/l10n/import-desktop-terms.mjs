#!/usr/bin/env node
// Imports the OpenCode desktop app's translations of shared terms into l10n/desktop-terms.json,
// so OpenCode Mobile says "session", "agent" or "model" the way the desktop app does.
//
// Usage: node scripts/l10n/import-desktop-terms.mjs <path to an anomalyco/opencode checkout>
//
// It reads packages/app/src/runtime/i18n/<code>.ts and packages/ui/src/i18n/<code>.ts, finds
// the desktop keys whose English is exactly one of TERMS, and records each language's
// translation of them. The desktop app is MIT-licensed (THIRD_PARTY_NOTICES.md).

import { execFileSync } from "node:child_process";
import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { LANGUAGES } from "./languages.mjs";
import { ROOT } from "./strings.mjs";

const TERMS = [
  "Session", "Sessions", "New session", "Server", "Servers", "Agent", "Agents", "Model", "Models",
  "Project", "Projects", "Workspace", "Tool", "Tools", "Permission", "Permissions",
  "Prompt", "Archive", "Archived", "Unarchive", "Settings", "Provider", "Providers", "Subagent",
  "Message", "Messages", "File", "Files", "Folder", "Terminal", "Command", "Commands",
  "Share", "Unshare", "Fork", "Undo", "Redo", "Retry", "Cancel", "Copy", "Copied", "Delete",
  "Rename", "Connect", "Disconnect", "Connected", "Disconnected", "Reconnecting", "Send", "Stop",
  "Allow", "Deny", "Always allow", "Reject", "Approve", "Question", "Questions", "Diff", "Changes",
  "Branch", "Compact", "Context", "Thinking", "Reasoning", "Attachment", "Attachments", "Search",
  "Theme", "Appearance", "Language", "Notifications", "Plan", "Build", "Todo", "Todos",
  "Variant", "Usage", "Cost", "Tokens", "Sign in", "Sign out", "Password", "Username",
  "Pair", "Pairing", "Review", "Revert", "Queued", "Steer", "Skill", "Skills", "Snapshot",
];

function readDict(path) {
  if (!existsSync(path)) return {};
  const dict = {};
  for (const m of readFileSync(path, "utf8").matchAll(/^\s*"([^"]+)":\s*"((?:[^"\\]|\\.)*)",?\s*$/gm)) {
    dict[m[1]] = JSON.parse(`"${m[2]}"`);
  }
  return dict;
}

const checkout = process.argv[2];
if (!checkout || !existsSync(join(checkout, "packages/app/src/runtime/i18n/en.ts"))) {
  console.error("Usage: node scripts/l10n/import-desktop-terms.mjs <path to an anomalyco/opencode checkout>");
  process.exit(1);
}
const folders = ["packages/app/src/runtime/i18n", "packages/ui/src/i18n"].map((f) => join(checkout, f));
const english = Object.assign({}, ...folders.map((f) => readDict(join(f, "en.ts"))));

const keysByTerm = {};
for (const term of TERMS) keysByTerm[term] = Object.keys(english).filter((k) => english[k] === term);

const languages = {};
for (const language of LANGUAGES) {
  const dict = Object.assign({}, ...folders.map((f) => readDict(join(f, `${language.desktop}.ts`))));
  const terms = {};
  for (const term of TERMS) {
    const counts = {};
    for (const key of keysByTerm[term]) {
      const value = dict[key];
      if (value && value !== term) counts[value] = (counts[value] ?? 0) + 1;
    }
    const best = Object.entries(counts).sort((a, b) => b[1] - a[1])[0];
    if (best) terms[term] = best[0];
  }
  languages[language.ios] = terms;
}

const commit = execFileSync("git", ["-C", checkout, "rev-parse", "--short", "HEAD"], { encoding: "utf8" }).trim();
const out = {
  source: `github.com/anomalyco/opencode@${commit} (MIT), packages/app and packages/ui i18n`,
  languages,
};
writeFileSync(join(ROOT, "l10n/desktop-terms.json"), `${JSON.stringify(out, null, 2)}\n`);
const counts = Object.values(languages).map((t) => Object.keys(t).length);
console.log(`Wrote l10n/desktop-terms.json: ${LANGUAGES.length} languages, ${Math.min(...counts)}–${Math.max(...counts)} terms each.`);
