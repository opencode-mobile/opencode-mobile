# ADR 0004: Native Rendered Markdown

## Status

Accepted

## Context

Session Chat needs formatted markdown, code blocks, and fenced diff display, but
the WebView island from ADR 0003 created startup cost, height feedback loops, and
scroll instability in message timelines. The product direction still favors
native SwiftUI and Compose screens, and React Native remains out of scope.

The hard part is no longer basic markdown layout. It is broad, high-quality code
highlighting close to Shiki and VS Code, which depends on TextMate grammars and
an Oniguruma-compatible regex engine.

## Decision

Use native Rendered Markdown:

- shared Kotlin Multiplatform markdown parsing and render-tree types;
- native Compose rendering on Android;
- native SwiftUI rendering on iOS;
- no WebView, React Native, JavaScript renderer, network loading, or broad
  bridge;
- a TextMate/Oniguruma feasibility gate before claiming Shiki-level syntax
  highlighting.

The shared parser emits an app-owned render tree rather than toolkit-specific
views. Android consumes that shared tree directly. iOS builds
`OpenCodeMarkdownCore`, calls the versioned JSON parser boundary, decodes the
render tree into Swift structs, and renders it with SwiftUI.

## Consequences

- Chat rows no longer depend on WebView creation, local HTML assets, JavaScript
  bridges, or measured-height script callbacks.
- The public component term remains **Rendered Markdown**, but its implementation
  returns to the native app architecture.
- Markdown safety is native-owned: raw HTML is literal text, images are omitted,
  and only `http`/`https` links may be opened externally.
- Code blocks use native semantic fallback highlighting until the TextMate gate
  passes; this is a documented implementation gap, not the final target.
- The future session diff viewer can reuse the same render tree and native diff
  line roles.
