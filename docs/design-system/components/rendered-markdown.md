# Rendered Markdown

## Intent

Render message text that contains markdown structure, code blocks, or fenced
diffs while preserving native screen ownership around the rendered content.
Feature screens should use it for formatted message text and future diff-review
surfaces, not for general web content.

## Public API

- Variants: `assistantMessage`, `outgoingMessage`, `standalone`.
- States: default, streaming, empty. Collapsed and expanded are not public
  message states in this version.
- Required content: a parsed native markdown document and the variant. Raw
  markdown remains owned by the message/domain layer.
- Optional content: link-tap callback, streaming flag, initial height seed for
  timeline layout (`initialHeight`), and platform-specific measured-height
  reporting callbacks.

## Token Usage

- Semantic tokens: background, raised surface, text, interactive, border,
  markdown, syntax, and diff tokens.
- Code-block syntax colors follow the shared `CodeTokenRole` mapping in
  `docs/design-system/tokens.md`: each colored role has its own syntax token
  (`Function` → `syntax-function`, `Number` → `syntax-number`, and so on), and
  variables, operators, and punctuation draw in the plain code text color.
- Component tokens: `outgoingMessage.*` when composed inside
  `OpenCodeOutgoingMessage`; `assistantMessage.*` when composed inside
  `OpenCodeAssistantMessage`.
- Typography roles: `type.body` for assistant/outgoing prose (paragraphs, lists,
  quotes), `type.bodySecondary` for table cells, `type.caption`, `type.code`,
  `type.heading`.

## Behavior

The component renders a safe GFM subset: paragraphs, headings, emphasis, links,
lists, blockquotes, inline code, fenced code, tables, and fenced `diff` blocks.
Bare `http(s)` URLs and `<https://…>` autolinks are promoted to the same link
nodes as `[label](url)`. Links use bold + underline on `markdown-link-*` (body
strong), not interactive accent. Blockquote bars use muted `markdown-quote-bar`
(`border-weak-base`). Inline backtick code uses monospace medium weight plus
`markdown-code-text` / `markdown-code-bg`, aligned with desktop session-ui chrome
(tinted chip; native attributed strings approximate horizontal padding with thin
spaces and omit desktop radius).
Chat callers should mount it whenever markdown markers exist, including simple
inline code, emphasis, and links. Messages with no markdown markers stay on the
plain native text path.

Streaming callers pass the streaming state through the public API. When
markdown is detected, the text lane always attaches a structure-only
formatted scaffold (OPE-92). Token colors from highlight-core (#872) arrive
later; the renderer must not strip structure to unprocessed plain source.
Finalization may briefly reuse the last streaming document while the colored
document is prepared. Large
finalized code, diff, and inline attributed-string construction must stay off
the UI thread; a temporary uncolored representation is preferable to blocking
scroll or input.

Fenced code blocks remain fully visible on both platforms. The renderer may
defer styling work while content is streaming, but it must not truncate the
source or require a platform-specific expand action to reveal later lines.

Raw HTML is not interpreted. Remote images, arbitrary navigation, network loads,
WebView runtime, and broad JavaScript bridges are not part of the component.
Link taps are owned by native code and only `http`/`https` URLs may leave the
renderer.

Copy and restore actions use the original raw markdown text. Per-code-block copy
controls are intentionally deferred.

Outgoing messages always render at full height through `OpenCodeOutgoingMessage`;
this component does not expose in-message collapse controls for outgoing
variants.

## Platform Notes

- iOS: implemented with SwiftUI text, stacks, scroll views, and attributed text.
  It consumes `OpenCodeMarkdownCore.parseMarkdownJson` and decodes the shared
  render tree into Swift structs. Message preparation and document caching run
  outside the UI actor; rendering remains SwiftUI-owned. Code blocks render
  their complete source, matching Android. List markers share one column width
  (sized to the widest marker, trailing-aligned) so item text lines up.
- Android: implemented with Compose text, stacks, scroll views, and attributed
  strings consuming `shared/markdown-core` directly. Large finalized
  attributed strings are prepared on a background dispatcher before Compose
  replaces the temporary plain representation. List marker columns match iOS
  (measure widest marker, end-aligned).

## Previews

- Light: assistant release checklist with a link, inline code, and highlighted
  Kotlin or Swift code.
- Dark: long outgoing review request, rendered through `OpenCodeOutgoingMessage`.
- Default: highlighted TypeScript code, a native table, and a multi-language
  syntax sweep covering Kotlin, Swift, TypeScript, Python, JSON, Shell, and YAML.
- Rich content: blockquote, ordered list, emphasis, link, safe image-alt label,
  and thematic break. Tables share one measured width per column across all
  rows so vertical borders stay aligned, then expand those columns to fill the
  available width when content is narrower than the container.
- Diff: fenced session-chat patch with added and deleted lines.
- Streaming: same content re-rendered through native chat updates.
- Empty: native placeholder remains the caller's responsibility.

## Parity And Divergence

No intentional visual divergence is defined. Track temporary differences on
the GitHub issue.
