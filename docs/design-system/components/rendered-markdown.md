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
- Optional content: initial height, measured-height callback, link-tap callback,
  streaming flag, expanded/collapsed state for outgoing messages.

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
Chat callers should mount it only for complex block markdown, tables, fenced
code, and fenced `diff`; inline-only markdown should stay native/plain in chat
so scrolling remains smooth.

Raw HTML, remote images, arbitrary navigation, network loads, and broad
JavaScript bridges are disabled. Link taps are intercepted by native code and
only `http`/`https` URLs may leave the renderer.

Copy and restore actions use the original raw markdown text. Per-code-block copy
controls are intentionally deferred.

## Platform Notes

- iOS: implemented with `WKWebView` inside SwiftUI. The WebView is non-scrolling,
  transparent, uses cached local HTML and a shared process pool, and reports
  content height through a narrow script message.
- Android: implemented with `WebView` inside Compose. The WebView is
  non-scrolling, transparent, supports Compose view reuse, and reports content
  height through a narrow JavaScript interface.

## Previews

- Light: assistant message with heading, list, and code.
- Dark: outgoing message with fenced code.
- Default: markdown text.
- Streaming: same content re-rendered through debounced updates in chat.
- Empty: native placeholder remains the caller's responsibility.

## Parity And Divergence

No intentional visual divergence is defined. Temporary differences belong in the
Rendered Markdown feature parity gaps.
