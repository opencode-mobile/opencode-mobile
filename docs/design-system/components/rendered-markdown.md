# Rendered Markdown

## Intent

Render message text that contains markdown structure, code blocks, or fenced
diffs while preserving native screen ownership around the rendered content.
Feature screens should use it for formatted message text and future diff-review
surfaces, not for general web content.

## Public API

- Variants: `assistantMessage`, `outgoingMessage`, `standalone`.
- States: default, streaming, collapsed, expanded, empty.
- Required content: a parsed native markdown document and the variant. Raw
  markdown remains owned by the message/domain layer.
- Optional content: link-tap callback, streaming flag, expanded/collapsed state
  for outgoing messages, and platform-specific height reporting used by
  timeline layout.

## Token Usage

- Semantic tokens: background, raised surface, text, interactive, border,
  markdown, syntax, and diff tokens.
- Code-block syntax colors follow the web-aligned `CodeTokenRole` mapping in
  `docs/design-system/tokens.md` (`Variable` → `syntax-variable`, `Function` →
  `syntax-primitive`, `Property` / `Constant` → `syntax-constant`, `String` →
  `syntax-string`, `Number` → `syntax-property`, `Operator` →
  `syntax-operator`, punctuation → `syntax-punctuation`).
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

Streaming callers pass the streaming state through the public API. Large live
messages skip markdown preparation and remain native plain text until the final
message snapshot. When a prepared streaming document already exists, large live
code fences render as plain monospace text instead of painting all syntax spans.
Finalization may briefly reuse the last streaming document while the complete
TextMate document is prepared. Large finalized code, diff, and inline
attributed-string construction must stay off the UI thread; a temporary plain
representation is preferable to blocking scroll or input.

Fenced code blocks remain fully visible on both platforms. The renderer may
defer styling work while content is streaming, but it must not truncate the
source or require a platform-specific expand action to reveal later lines.

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
  render tree into Swift structs. Message preparation and document caching run
  outside the UI actor; rendering remains SwiftUI-owned. Code blocks render
  their complete source, matching Android.
- Android: implemented with Compose text, stacks, scroll views, and attributed
  strings consuming `shared/markdown-core` directly. Large finalized
  attributed strings are prepared on a background dispatcher before Compose
  replaces the temporary plain representation.

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
