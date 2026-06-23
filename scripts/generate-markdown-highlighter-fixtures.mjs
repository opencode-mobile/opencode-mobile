#!/usr/bin/env node

import { createRequire } from "node:module"
import { mkdir, rm, writeFile } from "node:fs/promises"
import path from "node:path"
import { fileURLToPath } from "node:url"
import { bundledLanguages, bundledLanguagesInfo, createHighlighter } from "shiki"

const require = createRequire(import.meta.url)
const shikiPackage = require("shiki/package.json")

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..")
const jsonOutputPath = path.join(
  repoRoot,
  "shared/markdown-core/src/commonTest/resources/highlighter-goldens/shiki-semantic-tokens.json",
)
const kotlinOutputPath = path.join(
  repoRoot,
  "shared/markdown-core/src/commonTest/kotlin/dev/opencode/mobile/markdown/HighlighterGoldenFixtures.kt",
)
const manifestOutputPath = path.join(
  repoRoot,
  "shared/markdown-core/src/commonMain/resources/textmate-grammars/manifest.json",
)
const grammarResourcesRoot = path.join(
  repoRoot,
  "shared/markdown-core/src/commonMain/resources/textmate-grammars/grammars",
)
const manifestKotlinOutputPath = path.join(
  repoRoot,
  "shared/markdown-core/src/commonTest/kotlin/dev/opencode/mobile/markdown/TextMateGrammarManifestFixtures.kt",
)
const grammarResourceKotlinOutputPath = path.join(
  repoRoot,
  "shared/markdown-core/src/commonTest/kotlin/dev/opencode/mobile/markdown/TextMateGrammarResourceFixtures.kt",
)

const roleColors = {
  Plain: "#010101",
  Comment: "#010102",
  Keyword: "#010103",
  String: "#010104",
  Number: "#010105",
  Type: "#010106",
  Function: "#010107",
  Property: "#010108",
  Variable: "#010109",
  Constant: "#01010A",
  Operator: "#01010B",
  Punctuation: "#01010C",
  Tag: "#01010D",
  Attribute: "#01010E",
  Inserted: "#01010F",
  Deleted: "#010110",
  Warning: "#010111",
  Critical: "#010112",
}

const colorRoles = new Map(
  Object.entries(roleColors).map(([role, color]) => [color.toLowerCase(), role]),
)

const semanticTheme = {
  name: "opencode-semantic-golden",
  type: "dark",
  colors: {
    "editor.foreground": roleColors.Plain,
    "editor.background": "#000000",
  },
  tokenColors: [
    { scope: ["comment", "punctuation.definition.comment"], settings: { foreground: roleColors.Comment } },
    { scope: ["string", "punctuation.definition.string"], settings: { foreground: roleColors.String } },
    { scope: ["constant.numeric"], settings: { foreground: roleColors.Number } },
    { scope: ["constant.language", "constant.character", "constant.other"], settings: { foreground: roleColors.Constant } },
    { scope: ["keyword", "storage.modifier", "storage.type"], settings: { foreground: roleColors.Keyword } },
    { scope: ["keyword.operator", "keyword.operator.expression"], settings: { foreground: roleColors.Operator } },
    { scope: ["entity.name.type", "entity.name.class", "entity.name.struct", "support.type", "support.class"], settings: { foreground: roleColors.Type } },
    { scope: ["entity.name.function", "support.function", "variable.function"], settings: { foreground: roleColors.Function } },
    { scope: ["variable.other.property", "support.type.property-name", "meta.object-literal.key"], settings: { foreground: roleColors.Property } },
    { scope: ["variable"], settings: { foreground: roleColors.Variable } },
    { scope: ["entity.name.tag"], settings: { foreground: roleColors.Tag } },
    { scope: ["entity.other.attribute-name"], settings: { foreground: roleColors.Attribute } },
    { scope: ["markup.inserted"], settings: { foreground: roleColors.Inserted } },
    { scope: ["markup.deleted"], settings: { foreground: roleColors.Deleted } },
    { scope: ["markup.changed", "meta.diff.header"], settings: { foreground: roleColors.Warning } },
    { scope: ["invalid", "invalid.illegal", "invalid.deprecated"], settings: { foreground: roleColors.Critical } },
    { scope: ["punctuation", "meta.brace"], settings: { foreground: roleColors.Punctuation } },
  ],
}

const fixtures = [
  {
    id: "kotlin-basic",
    language: "kotlin",
    aliases: ["kt"],
    code: `package dev.opencode.demo

data class Session(val id: String, val active: Boolean)

fun render(count: Int): String {
  return "sessions: $count"
}
`,
  },
  {
    id: "swift-basic",
    language: "swift",
    aliases: [],
    code: `import SwiftUI

struct SessionRow: View {
    let title: String
    var body: some View {
        Text(title).font(.body)
    }
}
`,
  },
  {
    id: "typescript-basic",
    language: "typescript",
    aliases: ["ts"],
    code: `type Session = { id: string; active: boolean }

export function label(session: Session): string {
  return session.active ? "active" : "idle"
}
`,
  },
  {
    id: "javascript-basic",
    language: "javascript",
    aliases: ["js"],
    code: `const answer = 42
console.log({ answer, ok: true })
`,
  },
  {
    id: "javascript-request-repro",
    language: "javascript",
    aliases: ["js"],
    code: `  // Core request method
  async request(method, path, options = {}) {
    let config = {
      method,
      url: \`\${this.#baseUrl}\${path}\`,
      headers: Object.fromEntries(this.#headers),
      body: options.body || null,
      params: options.params || {},
      signal: options.signal || null,
    };

    // Apply request interceptors
    for (const interceptor of this.#interceptors.request) {
      config = await interceptor(config);
    }
`,
  },
  {
    id: "javascript-http-error-repro",
    language: "javascript",
    aliases: ["js"],
    code: `// Custom error class
class HttpError extends Error {
  constructor(status, statusText, data) {
    super(\`HTTP \${status}: \${statusText}\`);
    this.name = "HttpError";
    this.status = status;
    this.statusText = statusText;
    this.data = data;
  }
}

// Retry wrapper with exponential backoff
async function withRetry(fn, options = {}) {
  const { maxRetries = 3, baseDelay = 1000, maxDelay = 10000, retryOn = [429, 500, 502, 503] } = options;

  let lastError;
  for (let attempt = 0; attempt <= maxRetries; attempt++) {
`,
  },
  {
    id: "javascript-private-field-template",
    language: "javascript",
    aliases: ["js"],
    code: `class Client {
  #baseUrl;
  greet(path) {
    return \`\${this.#baseUrl}\${path}\`;
  }
}
`,
  },
  {
    id: "javascript-event-emitter-repro",
    language: "javascript",
    aliases: ["js"],
    code: `// EventEmitter - A simple pub/sub implementation
class EventEmitter {
  constructor() {
    this.events = new Map();
  }

  on(event, callback) {
    if (!this.events.has(event)) {
      this.events.set(event, []);
    }
    this.events.get(event).push(callback);
    return () => this.off(event, callback);
  }

  off(event, callback) {
    if (!this.events.has(event)) return;
    const callbacks = this.events.get(event);
    const index = callbacks.indexOf(callback);
    if (index > -1) callbacks.splice(index, 1);
  }

  emit(event, data) {
    if (!this.events.has(event)) return;
    this.events.get(event).forEach(callback => callback(data));
  }
}
`,
  },
  {
    id: "javascript-http-client-class-repro",
    language: "javascript",
    aliases: ["js"],
    code: `// HTTP Client with interceptors, retry logic
class HttpClient {
  #baseUrl;
  #headers;
  #interceptors;
  #timeout;

  constructor(options = {}) {
    this.#baseUrl = options.baseUrl || "";
    this.#headers = new Map(Object.entries(options.headers || {}));
    this.#interceptors = { request: [], response: [] };
    this.#timeout = options.timeout || 30000;
  }

  addRequestInterceptor(fn) {
    this.#interceptors.request.push(fn);
  }
}
`,
  },
  {
    id: "javascript-patch-path-repro",
    language: "javascript",
    aliases: ["js"],
    code: `patch(path, body, options) {
  return this.request("PATCH", path, body, options);
}
`,
  },
  {
    id: "json-basic",
    language: "json",
    aliases: [],
    code: `{
  "name": "opencode",
  "enabled": true,
  "limit": 3
}
`,
  },
  {
    id: "markdown-basic",
    language: "markdown",
    aliases: ["md"],
    code: `# Title

- item with \`code\`
- [link](https://example.com)
`,
  },
  {
    id: "shellscript-basic",
    language: "shellscript",
    aliases: ["shell", "sh", "bash"],
    code: `#!/usr/bin/env bash
set -euo pipefail
echo "building $APP_NAME"
`,
  },
  {
    id: "yaml-basic",
    language: "yaml",
    aliases: ["yml"],
    code: `name: opencode
steps:
  - run: ./gradlew test
`,
  },
  {
    id: "css-basic",
    language: "css",
    aliases: [],
    code: `.message {
  color: var(--text);
  padding: 12px;
}
`,
  },
  {
    id: "html-basic",
    language: "html",
    aliases: [],
    code: `<section class="message">
  <h1 data-kind="title">Hello</h1>
</section>
`,
  },
  {
    id: "python-basic",
    language: "python",
    aliases: ["py"],
    code: `def label(count: int) -> str:
    return f"sessions: {count}"
`,
  },
  {
    id: "rust-basic",
    language: "rust",
    aliases: ["rs"],
    code: `pub fn label(count: usize) -> String {
    format!("sessions: {count}")
}
`,
  },
  {
    id: "go-basic",
    language: "go",
    aliases: [],
    code: `package main

func label(count int) string {
	return fmt.Sprintf("sessions: %d", count)
}
`,
  },
  {
    id: "diff-basic",
    language: "diff",
    aliases: ["patch"],
    code: `@@ -1,3 +1,3 @@
-old line
+new line
 context
`,
  },
  {
    id: "java-basic",
    language: "java",
    aliases: [],
    code: `public class Main {
    public static void main(String[] args) {
        // Create an array
        int[] numbers = {1, 2, 3, 4, 5};

        // Loop through array
        for (int i = 0; i < numbers.length; i++) {
            System.out.println(numbers[i]);
        }

        // Try-catch block
        try {
            int result = 10 / 0;
        } catch (ArithmeticException e) {
            System.err.println("Division by zero!");
        }
    }
}
`,
  },
  {
    id: "csharp-basic",
    language: "csharp",
    aliases: ["cs"],
    code: `using System;

public sealed class SessionService
{
    public string Label(string id, int count)
    {
        return $"{id}: {count}";
    }
}
`,
  },
  {
    id: "c-basic",
    language: "c",
    aliases: [],
    code: `#include <stdio.h>

int main(void) {
    // greet the caller
    const char *name = "opencode";
    printf("hello %s\\n", name);
    return 0;
}
`,
  },
  {
    id: "cpp-basic",
    language: "cpp",
    aliases: ["cc", "cxx"],
    code: `#include <iostream>
#include <string>

int main() {
    std::string label = "sessions";
  std::cout << label << ": " << 3 << std::endl;
    return 0;
}
`,
  },
  {
    id: "ruby-basic",
    language: "ruby",
    aliases: ["rb"],
    code: `class Session
  def label(count)
    "sessions: #{count}"
  end
end
`,
  },
  {
    id: "php-basic",
    language: "php",
    aliases: [],
    code: `<?php

function label(int $count): string {
    return "sessions: {$count}";
}
`,
  },
  {
    id: "sql-basic",
    language: "sql",
    aliases: [],
    code: `-- active sessions
SELECT id, title
FROM sessions
WHERE active = TRUE
LIMIT 10;
`,
  },
  {
    id: "toml-basic",
    language: "toml",
    aliases: [],
    code: `[project]
name = "opencode"
enabled = true
limit = 3
`,
  },
  {
    id: "xml-basic",
    language: "xml",
    aliases: [],
    code: `<?xml version="1.0" encoding="UTF-8"?>
<session id="alpha" active="true">
  <title>Review</title>
</session>
`,
  },
  {
    id: "scala-basic",
    language: "scala",
    aliases: [],
    code: `object Sessions {
  def label(count: Int): String =
    s"sessions: $count"
}
`,
  },
  {
    id: "lua-basic",
    language: "lua",
    aliases: [],
    code: `local function label(count)
  -- format a label
  return "sessions: " .. count
end
`,
  },
  {
    id: "docker-basic",
    language: "docker",
    aliases: [],
    code: `FROM node:22-alpine
WORKDIR /app
COPY package.json .
RUN npm install --omit=dev
CMD ["node", "server.js"]
`,
  },
  {
    id: "vue-basic",
    language: "vue",
    aliases: [],
    code: `<script setup lang="ts">
const title = "OpenCode"
</script>

<template>
  <h1>{{ title }}</h1>
</template>
`,
  },
  {
    id: "svelte-basic",
    language: "svelte",
    aliases: [],
    code: `<script>
  let count = 0
</script>

<p>Sessions: {count}</p>
`,
  },
  {
    id: "graphql-basic",
    language: "graphql",
    aliases: [],
    code: `type Session {
  id: ID!
  title: String!
  active: Boolean!
}

query ActiveSessions {
  sessions(active: true) {
    id
    title
  }
}
`,
  },
  {
    id: "powershell-basic",
    language: "powershell",
    aliases: ["pwsh"],
    code: `function Get-SessionLabel([int]$Count) {
    # build a label
    return "sessions: $Count"
}
`,
  },
  {
    id: "perl-basic",
    language: "perl",
    aliases: [],
    code: `sub label {
    my ($count) = @_;
    return "sessions: $count";
}
`,
  },
  {
    id: "haskell-basic",
    language: "haskell",
    aliases: [],
    code: `label :: Int -> String
label count = "sessions: " ++ show count
`,
  },
  {
    id: "elixir-basic",
    language: "elixir",
    aliases: [],
    code: `defmodule Sessions do
  def label(count) do
    "sessions: #{count}"
  end
end
`,
  },
  {
    id: "erlang-basic",
    language: "erlang",
    aliases: [],
    code: `-module(sessions).
-export([label/1]).

label(Count) ->
    io_lib:format("sessions: ~p", [Count]).
`,
  },
  {
    id: "r-basic",
    language: "r",
    aliases: [],
    code: `label <- function(count) {
  # format a label
  paste0("sessions: ", count)
}
`,
  },
  {
    id: "zig-basic",
    language: "zig",
    aliases: [],
    code: `const std = @import("std");

pub fn label(count: u32) []const u8 {
    return std.fmt.allocPrint(std.heap.page_allocator, "sessions: {d}", .{count}) catch "sessions: ?";
}
`,
  },
  {
    id: "make-basic",
    language: "make",
    aliases: ["makefile"],
    code: `# Makefile
.PHONY: help build test

help:
\t@echo "Available commands:"
\t@echo "  make build - Build the project"
\t@echo "  make test  - Run tests"

build:
\t@echo "Building project..."
\t@npm run build

test:
\t@echo "Running tests..."
\t@npm test
`,
  },
]

const shikiLangs = [...new Set(fixtures.map((fixture) => fixture.language))]

const highlighter = await createHighlighter({
  themes: [semanticTheme],
  langs: shikiLangs,
})

const highlightedFixtures = fixtures.map((fixture) => {
  const result = highlighter.codeToTokens(fixture.code, {
    lang: fixture.language,
    theme: semanticTheme.name,
  })
  const tokens = []

  for (const line of result.tokens) {
    for (const token of line) {
      const role = colorRoles.get((token.color ?? roleColors.Plain).toLowerCase()) ?? "Plain"
      const start = token.offset
      const end = start + token.content.length
      if (role !== "Plain" && start < end) {
        tokens.push({
          start,
          end,
          role,
          text: token.content,
        })
      }
    }
  }

  return {
    id: fixture.id ?? fixture.language,
    ...fixture,
    shikiLanguage: fixture.language,
    tokens,
  }
})

const golden = {
  schemaVersion: 1,
  generator: "scripts/generate-markdown-highlighter-fixtures.mjs",
  shikiVersion: shikiPackage.version,
  theme: semanticTheme.name,
  roleColors,
  fixtures: highlightedFixtures,
}
const manifest = await buildGrammarManifest()
const grammarResourceFixtures = buildGrammarResourceFixtures(manifest)

await mkdir(path.dirname(jsonOutputPath), { recursive: true })
await mkdir(path.dirname(kotlinOutputPath), { recursive: true })
await mkdir(path.dirname(manifestOutputPath), { recursive: true })
await mkdir(path.dirname(manifestKotlinOutputPath), { recursive: true })
await mkdir(path.dirname(grammarResourceKotlinOutputPath), { recursive: true })
await rm(grammarResourcesRoot, { recursive: true, force: true })
await mkdir(grammarResourcesRoot, { recursive: true })
await writeGrammarResources(manifest)
await writeFile(jsonOutputPath, `${JSON.stringify(golden, null, 2)}\n`)
await writeFile(kotlinOutputPath, `${renderKotlin(golden)}\n`)
await writeFile(manifestOutputPath, `${JSON.stringify(manifest, null, 2)}\n`)
await writeFile(manifestKotlinOutputPath, `${renderManifestKotlin(manifest)}\n`)
await writeFile(grammarResourceKotlinOutputPath, `${renderGrammarResourceKotlin(grammarResourceFixtures)}\n`)

console.log(`Wrote ${path.relative(repoRoot, jsonOutputPath)}`)
console.log(`Wrote ${path.relative(repoRoot, kotlinOutputPath)}`)
console.log(`Wrote ${path.relative(repoRoot, manifestOutputPath)}`)
console.log(`Wrote ${path.relative(repoRoot, manifestKotlinOutputPath)}`)
console.log(`Wrote ${path.relative(repoRoot, grammarResourceKotlinOutputPath)}`)

function renderKotlin(golden) {
  const fixtureEntries = golden.fixtures.map(renderFixture).join(",\n")
  return `package dev.opencode.mobile.markdown

// Generated by ${golden.generator}. Do not edit by hand.

data class HighlighterGoldenFixture(
    val id: String,
    val language: String,
    val aliases: List<String>,
    val code: String,
    val tokens: List<HighlighterGoldenToken>,
)

data class HighlighterGoldenToken(
    val start: Int,
    val end: Int,
    val role: CodeTokenRole,
    val text: String,
)

object HighlighterGoldenFixtures {
    const val schemaVersion: Int = ${golden.schemaVersion}
    const val shikiVersion: String = ${kotlinString(golden.shikiVersion)}
    const val theme: String = ${kotlinString(golden.theme)}

    val all: List<HighlighterGoldenFixture> =
        listOf(
${indent(fixtureEntries, 12)}
        )
}
`
}

function renderFixture(fixture) {
  const aliases = fixture.aliases.map(kotlinString).join(", ")
  const tokens = fixture.tokens.map(renderToken).join(",\n")
  return `HighlighterGoldenFixture(
    id = ${kotlinString(fixture.id ?? fixture.language)},
    language = ${kotlinString(fixture.language)},
    aliases = listOf(${aliases}),
    code = ${kotlinString(fixture.code)},
    tokens = listOf(
${indent(tokens, 8)}
    ),
)`
}

function renderToken(token) {
  return `HighlighterGoldenToken(${token.start}, ${token.end}, CodeTokenRole.${token.role}, ${kotlinString(token.text)})`
}

async function buildGrammarManifest() {
  const entries = []
  const resourceGrammars = new Map()

  for (const language of shikiLangs) {
    const info =
      bundledLanguagesInfo.find((candidate) => candidate.id === language || candidate.aliases?.includes(language))
    const module = await bundledLanguages[language]()
    const grammars = toArray(module.default ?? module)

    entries.push({
      language,
      displayName: info?.name ?? grammars[0]?.displayName ?? language,
      aliases: [...new Set([...(info?.aliases ?? []), ...(fixtures.find((fixture) => fixture.language === language)?.aliases ?? [])])].sort(),
      grammarModule: `shiki/dist/langs/${language}.mjs`,
      grammars: grammars.map((grammar) => {
        const resourceName = `${slugify(grammar.name ?? grammar.scopeName ?? language)}.json`
        const resourcePath = `textmate-grammars/grammars/${resourceName}`
        const name = grammar.name ?? slugify(grammar.scopeName ?? language)
        const scopeName = grammar.scopeName ?? grammar.name ?? `source.${slugify(language)}`
        const displayName = grammar.displayName ?? grammar.name ?? grammar.scopeName ?? language
        resourceGrammars.set(resourcePath, {
          ...grammar,
          name,
          displayName,
          scopeName,
        })

        return {
          name,
          displayName,
          scopeName,
          resourcePath,
          embeddedLanguages: grammar.embeddedLangs ?? [],
          aliases: grammar.aliases ?? [],
        }
      }),
    })
  }

  return {
    schemaVersion: 1,
    generator: "scripts/generate-markdown-highlighter-fixtures.mjs",
    shikiVersion: shikiPackage.version,
    languages: entries,
    resourceGrammars: Object.fromEntries(resourceGrammars),
  }
}

async function writeGrammarResources(manifest) {
  for (const [resourcePath, grammar] of Object.entries(manifest.resourceGrammars)) {
    await writeFile(
      path.join(repoRoot, "shared/markdown-core/src/commonMain/resources", resourcePath),
      `${JSON.stringify(grammar, null, 2)}\n`,
    )
  }
  delete manifest.resourceGrammars
}

function buildGrammarResourceFixtures(manifest) {
  return Object.entries(manifest.resourceGrammars)
    .map(([resourcePath, grammar]) => {
      const json = `${JSON.stringify(grammar, null, 2)}\n`
      return {
        resourcePath,
        name: grammar.name ?? slugify(grammar.scopeName ?? ""),
        displayName: grammar.displayName ?? grammar.name ?? grammar.scopeName ?? "",
        scopeName: grammar.scopeName ?? grammar.name ?? "",
        byteSize: Buffer.byteLength(json, "utf8"),
      }
    })
    .sort((left, right) => left.resourcePath.localeCompare(right.resourcePath))
}

function renderManifestKotlin(manifest) {
  const entries = manifest.languages.map(renderManifestEntry).join(",\n")
  return `package dev.opencode.mobile.markdown

// Generated by ${manifest.generator}. Do not edit by hand.

object TextMateGrammarManifestFixtures {
    const val schemaVersion: Int = ${manifest.schemaVersion}
    const val shikiVersion: String = ${kotlinString(manifest.shikiVersion)}

    val languages: List<TextMateGrammarManifestEntry> =
        listOf(
${indent(entries, 12)}
        )
}
`
}

function renderGrammarResourceKotlin(resources) {
  const entries = resources.map(renderGrammarResource).join(",\n")
  return `package dev.opencode.mobile.markdown

// Generated by scripts/generate-markdown-highlighter-fixtures.mjs. Do not edit by hand.

data class TextMateGrammarResourceFixture(
    val resourcePath: String,
    val name: String,
    val displayName: String,
    val scopeName: String,
    val byteSize: Int,
)

object TextMateGrammarResourceFixtures {
    val all: List<TextMateGrammarResourceFixture> =
        listOf(
${indent(entries, 12)}
        )
}
`
}

function renderGrammarResource(resource) {
  return `TextMateGrammarResourceFixture(
    resourcePath = ${kotlinString(resource.resourcePath)},
    name = ${kotlinString(resource.name)},
    displayName = ${kotlinString(resource.displayName)},
    scopeName = ${kotlinString(resource.scopeName)},
    byteSize = ${resource.byteSize},
)`
}

function renderManifestEntry(entry) {
  return `TextMateGrammarManifestEntry(
    language = ${kotlinString(entry.language)},
    displayName = ${kotlinString(entry.displayName)},
    aliases = listOf(${entry.aliases.map(kotlinString).join(", ")}),
    grammarModule = ${kotlinString(entry.grammarModule)},
    grammars = listOf(
${indent(entry.grammars.map(renderGrammarDescriptor).join(",\n"), 8)}
    ),
)`
}

function renderGrammarDescriptor(grammar) {
  return `TextMateGrammarDescriptor(
    name = ${kotlinString(grammar.name ?? "")},
    displayName = ${kotlinString(grammar.displayName ?? "")},
    scopeName = ${kotlinString(grammar.scopeName ?? "")},
    resourcePath = ${kotlinString(grammar.resourcePath ?? "")},
    embeddedLanguages = listOf(${grammar.embeddedLanguages.map(kotlinString).join(", ")}),
    aliases = listOf(${grammar.aliases.map(kotlinString).join(", ")}),
)`
}

function toArray(value) {
  return Array.isArray(value) ? value : [value]
}

function slugify(value) {
  return String(value)
    .toLowerCase()
    .replaceAll(/[^a-z0-9._-]+/g, "-")
    .replaceAll(/^-+|-+$/g, "")
}

function kotlinString(value) {
  return JSON.stringify(value).replaceAll("$", "\\$")
}

function indent(value, spaces) {
  const pad = " ".repeat(spaces)
  return value
    .split("\n")
    .map((line) => (line.length === 0 ? line : `${pad}${line}`))
    .join("\n")
}
