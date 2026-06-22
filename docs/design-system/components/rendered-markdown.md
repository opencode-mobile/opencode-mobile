# Rendered Markdown

## Intent

Render message text that contains markdown structure, code blocks, or fenced
diffs while preserving native screen ownership around the rendered content.
Feature screens should use it for formatted message text and future diff-review
surfaces, not for general web content.

## Public API

- Variants: `assistantMessage`, `outgoingMessage`, `standalone`.
- States: default, streaming, collapsed, expanded, empty.
- Required content: raw markdown text and the variant.
- Optional content: link-tap callback, streaming flag, expanded/collapsed state
  for outgoing messages. Legacy initial-height callbacks are compatibility
  shims while chat height caches are removed.

## Token Usage

- Semantic tokens: background, raised surface, text, interactive, border,
  markdown, syntax, and diff tokens.
- Component tokens: `outgoingMessage.*` when composed inside
  `OpenCodeOutgoingMessage`.
- Typography roles: `type.body`, `type.bodySecondary`, `type.caption`,
  `type.code`, `type.heading`.

## Behavior

The component renders a safe GFM subset: paragraphs, headings, emphasis, links,
lists, blockquotes, inline code, fenced code, tables, and fenced `diff` blocks.
Chat callers should mount it whenever markdown markers exist, including simple
inline code, emphasis, and links. Messages with no markdown markers stay on the
plain native text path.

Raw HTML is not interpreted. Remote images, arbitrary navigation, network loads,
WebView runtime, and broad JavaScript bridges are not part of the component.
Link taps are owned by native code and only `http`/`https` URLs may leave the
renderer.

Copy and restore actions use the original raw markdown text. Per-code-block copy
controls are intentionally deferred.

Outgoing messages clamp only when their document is structurally expandable.
Long wrapped inline markdown that cannot expose an expand control stays fully
visible rather than losing content below the collapsed height.

## Platform Notes

- iOS: implemented with SwiftUI text, stacks, scroll views, and attributed text.
  It consumes `OpenCodeMarkdownCore.parseMarkdownJson` and decodes the shared
  render tree into Swift structs.
- Android: implemented with Compose text, stacks, scroll views, and attributed
  strings consuming `shared/markdown-core` directly.

## Previews

- Light: assistant release checklist with a link, inline code, and highlighted
  Kotlin or Swift code.
- Dark: long outgoing review request, rendered through `OpenCodeOutgoingMessage`.
- Default: highlighted TypeScript code, a native table, and a multi-language
  syntax sweep covering Kotlin, Swift, TypeScript, Python, JSON, Shell, and YAML.
- Rich content: blockquote, ordered list, emphasis, link, safe image-alt label,
  and thematic break.
- Diff: fenced session-chat patch with added and deleted lines.
- Streaming: same content re-rendered through native chat updates.
- Empty: native placeholder remains the caller's responsibility.

## Parity And Divergence

No intentional visual divergence is defined. Temporary differences belong in the
Rendered Markdown feature parity gaps.
