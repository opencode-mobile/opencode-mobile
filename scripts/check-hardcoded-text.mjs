#!/usr/bin/env node
// Counts user-facing string literals in feature UI code that should come from
// the string files (Android strings.xml, iOS Localizable.xcstrings).
//
// Scanned:
//   Android  android/app/src/main/java/dev/opencode/mobile/ui/**/*.kt
//            (feature UI and the design-system components in ui/components)
//   iOS      ios/OpenCode Mobile/OpenCode Mobile/UI/**/*.swift
//            ios/OpenCode Mobile/OpenCode Mobile/Components/**/*.swift
//            ios/OpenCodeKit/Sources/OpenCodeKit/Components/**/*.swift
//            ios/OpenCodeKit/Sources/OpenCodeKit/UI/**/*.swift
// Skipped: the component gallery, developer tools (playground, highlighting
// lab, diagnostics overlay, snapshot hosts), @Preview / #Preview blocks,
// top-level preview and gallery sample declarations (a name containing
// `Preview` or `Sample`), and Compose animation debug labels.
//
// A finding is a string literal with at least one letter passed where users read
// it, for example:
//   Kotlin  Text("…")  title = "…"  label = "…"  FullWidthText("…")  IconCircle(icon, "…")
//   Swift   Text("…")  title: "…"   label: "…"   .fullWidthText(title: "…")  .accessibilityLabel("…")
// Structured keys such as "servers.title" are not findings: they are catalog keys.
// On iOS, `.unlocalized(…)` (app text still built as a String) also counts.
//
// The check is line-based and deterministic, not a parser. It may miss text
// built in helpers; it is a ratchet, not a proof.
//
// Usage: node scripts/check-hardcoded-text.mjs [--strict] [--list]
//   default   print counts and exit 0 (warning only)
//   --strict  exit 1 when anything is found
//   --list    also print every finding as path:line

import { readdirSync, readFileSync, statSync } from "node:fs";
import { dirname, join, relative } from "node:path";
import { fileURLToPath } from "node:url";

const REPO_ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const args = new Set(process.argv.slice(2));
const strict = args.has("--strict");
const list = args.has("--list");

const STRUCTURED_KEY = /^[a-z][a-z0-9_]*(\.[a-z0-9_]+)+$/;

const TEXT_PARAMS =
  "title|subtitle|label|caption|message|hint|placeholder|description|text|actionLabel|actionTitle|accessibilityLabel|contentDescription|blurb|tag|errorMessage";

const PLATFORMS = [
  {
    name: "Android",
    roots: ["android/app/src/main/java/dev/opencode/mobile/ui"],
    extension: ".kt",
    skipDirs: ["componentgallery", "playground", "snapshot", "icons", "diagnostic"],
    preview: /^\s*@Preview\b/,
    sample: /^(?:(?:private|internal|public)\s+)?(?:fun|object|val|class)\s+(?:[\w.<>]+\.)?\w*(?:[Pp]review|[Ss]ample)\w*/,
    // `label = "…"` on animate*/transition calls names the animation for tooling.
    animation: /\b(?:animate\w*|rememberInfiniteTransition|updateTransition|Crossfade|AnimatedContent)\s*\(/,
    patterns: [
      /\bText\(\s*"((?:[^"\\]|\\.)*)"/g,
      new RegExp(`\\b(?:${TEXT_PARAMS})\\s*=\\s*"((?:[^"\\\\]|\\\\.)*)"`, "g"),
      /\b(?:FullWidthText|CompactText|Link|TextLink)\(\s*"((?:[^"\\]|\\.)*)"/g,
      /\bIcon(?:Circle|Square)\([^,()]+,\s*"((?:[^"\\]|\\.)*)"/g,
    ],
    debt: null,
  },
  {
    name: "iOS",
    roots: [
      "ios/OpenCode Mobile/OpenCode Mobile/UI",
      "ios/OpenCode Mobile/OpenCode Mobile/Components",
      "ios/OpenCodeKit/Sources/OpenCodeKit/Components",
      "ios/OpenCodeKit/Sources/OpenCodeKit/UI",
    ],
    extension: ".swift",
    skipDirs: ["Playground", "HighlightLab", "Diagnostic"],
    preview: /^\s*#Preview\b/,
    sample: /^(?:(?:private|fileprivate|internal)\s+)?(?:struct|enum|extension|func|let|var)\s+\w*(?:[Pp]review|[Ss]ample)\w*/,
    animation: null,
    patterns: [
      /\bText\(\s*"((?:[^"\\]|\\.)*)"/g,
      new RegExp(`\\b(?:${TEXT_PARAMS}):\\s*"((?:[^"\\\\]|\\\\.)*)"`, "g"),
      /\.(?:fullWidthText|compactText|link)\(title:\s*"((?:[^"\\]|\\.)*)"/g,
      /\b(?:OpenCodeTag|Button|Label|Toggle)\(\s*"((?:[^"\\]|\\.)*)"/g,
      /\.accessibilityLabel\(\s*"((?:[^"\\]|\\.)*)"/g,
    ],
    debt: /\.unlocalized\(/g,
  },
];

function walk(dir, extension, skipDirs, out) {
  for (const entry of readdirSync(dir).sort()) {
    const path = join(dir, entry);
    if (statSync(path).isDirectory()) {
      if (!skipDirs.includes(entry)) walk(path, extension, skipDirs, out);
    } else if (entry.endsWith(extension)) {
      out.push(path);
    }
  }
  return out;
}

// Letters that only come from interpolations (`$count`, `${a.b}`, `\(name)`) or
// Unicode escapes (`\u{2212}`) are not text: "+$additions" or "\(done)/\(total)"
// are not findings.
function withoutInterpolations(literal) {
  return literal
    .replace(/\$\{[^}]*\}/g, "")
    .replace(/\$[A-Za-z_][A-Za-z0-9_]*/g, "")
    .replace(/\\\([^)]*\)/g, "")
    .replace(/\\u\{[0-9A-Fa-f]+\}|\\u[0-9A-Fa-f]{4}/g, "");
}

function isFinding(literal) {
  return /[A-Za-z]/.test(withoutInterpolations(literal)) && !STRUCTURED_KEY.test(literal);
}

// Compose animation labels name the animation for tooling ("listItemPressScale",
// "opencode-working-fade"); they are never shown.
const ANIMATION_LABEL = /^(?:[a-z]+(?:[A-Z][a-z0-9]*)+|[A-Z][a-z0-9]+(?:[A-Z][a-z0-9]*)+|[a-z0-9]+(?:-[a-z0-9]+)+)$/;

function scanFile(path, platform) {
  const lines = readFileSync(path, "utf8").split("\n");
  const findings = [];
  let inPreview = false;
  // Expression-bodied sample declarations (no brace) end at the first blank line.
  let previewEndsAtBlank = false;
  let lastAnimationLine = -10;
  lines.forEach((line, index) => {
    if (!inPreview && platform.preview.test(line)) {
      inPreview = true;
      previewEndsAtBlank = false;
    } else if (!inPreview && platform.sample.test(line)) {
      inPreview = true;
      previewEndsAtBlank = !line.includes("{");
    }
    if (inPreview) {
      // Previews are top-level declarations: they end at the first column-0 brace.
      if (line.startsWith("}") || line.startsWith(")")) inPreview = false;
      else if (previewEndsAtBlank && line.trim() === "") inPreview = false;
      return;
    }
    const code = line.trimStart();
    if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) return;
    if (platform.animation && platform.animation.test(line)) lastAnimationLine = index;
    const inAnimationCall = index - lastAnimationLine <= 8;
    for (const pattern of platform.patterns) {
      for (const match of line.matchAll(pattern)) {
        const isLabel = platform.animation && /\blabel\s*=\s*"/.test(match[0]);
        if (isLabel && (inAnimationCall || ANIMATION_LABEL.test(match[1]))) continue;
        if (isFinding(match[1])) findings.push({ line: index + 1, kind: "literal" });
      }
    }
    if (platform.debt) {
      for (const _ of line.matchAll(platform.debt)) findings.push({ line: index + 1, kind: "unlocalized" });
    }
  });
  return findings;
}

let total = 0;
const allFindings = [];
for (const platform of PLATFORMS) {
  const files = [];
  for (const root of platform.roots) walk(join(REPO_ROOT, root), platform.extension, platform.skipDirs, files);
  const perFile = [];
  let literals = 0;
  let debt = 0;
  for (const file of files) {
    const findings = scanFile(file, platform);
    if (findings.length === 0) continue;
    const rel = relative(REPO_ROOT, file);
    perFile.push({ rel, count: findings.length });
    literals += findings.filter((f) => f.kind === "literal").length;
    debt += findings.filter((f) => f.kind === "unlocalized").length;
    for (const f of findings) allFindings.push(`${rel}:${f.line} ${f.kind}`);
  }
  perFile.sort((a, b) => b.count - a.count || a.rel.localeCompare(b.rel));
  const count = literals + debt;
  total += count;
  const detail = platform.debt ? ` (${literals} literals, ${debt} .unlocalized)` : "";
  console.log(`${platform.name}: ${count} hardcoded text finding(s) in ${perFile.length} file(s)${detail}`);
  for (const { rel, count: n } of perFile) console.log(`  ${String(n).padStart(4)}  ${rel}`);
}

if (list) {
  console.log("");
  for (const f of allFindings) console.log(f);
}

console.log(`\nTotal: ${total}`);
if (total > 0) {
  const message = "Move user-facing text to strings.xml / Localizable.xcstrings with matching keys (see #886).";
  if (strict) {
    console.error(message);
    process.exit(1);
  }
  console.log(`Warning: ${message}`);
}
