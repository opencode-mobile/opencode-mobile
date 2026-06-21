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
    language: "typescript",
    aliases: ["ts"],
    code: `type Session = { id: string; active: boolean }

export function label(session: Session): string {
  return session.active ? "active" : "idle"
}
`,
  },
  {
    language: "javascript",
    aliases: ["js"],
    code: `const answer = 42
console.log({ answer, ok: true })
`,
  },
  {
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
    language: "markdown",
    aliases: ["md"],
    code: `# Title

- item with \`code\`
- [link](https://example.com)
`,
  },
  {
    language: "shellscript",
    aliases: ["shell", "sh", "bash"],
    code: `#!/usr/bin/env bash
set -euo pipefail
echo "building $APP_NAME"
`,
  },
  {
    language: "yaml",
    aliases: ["yml"],
    code: `name: opencode
steps:
  - run: ./gradlew test
`,
  },
  {
    language: "css",
    aliases: [],
    code: `.message {
  color: var(--text);
  padding: 12px;
}
`,
  },
  {
    language: "html",
    aliases: [],
    code: `<section class="message">
  <h1 data-kind="title">Hello</h1>
</section>
`,
  },
  {
    language: "python",
    aliases: ["py"],
    code: `def label(count: int) -> str:
    return f"sessions: {count}"
`,
  },
  {
    language: "rust",
    aliases: ["rs"],
    code: `pub fn label(count: usize) -> String {
    format!("sessions: {count}")
}
`,
  },
  {
    language: "go",
    aliases: [],
    code: `package main

func label(count int) string {
	return fmt.Sprintf("sessions: %d", count)
}
`,
  },
  {
    language: "diff",
    aliases: ["patch"],
    code: `@@ -1,3 +1,3 @@
-old line
+new line
 context
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
        resourceGrammars.set(resourcePath, grammar)

        return {
          name: grammar.name,
          displayName: grammar.displayName,
          scopeName: grammar.scopeName,
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
        name: grammar.name ?? "",
        displayName: grammar.displayName ?? "",
        scopeName: grammar.scopeName ?? "",
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
