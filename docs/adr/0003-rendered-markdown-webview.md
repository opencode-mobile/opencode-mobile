# ADR 0003: Rendered Markdown WebView Island

## Status

Superseded by [ADR 0004: Native Rendered Markdown](0004-native-rendered-markdown.md)

## Context

`opencode-mobile` keeps screens, navigation, state, and reusable chrome native on
iOS and Android. Message text still needs high-quality markdown, code block, and
future diff rendering, where purely native open-source options are incomplete
and React Native would add a broad runtime/tooling stack for one renderer.

## Decision

Use a sandboxed local WKWebView / Android WebView island for **Rendered
Markdown** while keeping surrounding chat rows, controls, navigation, state, and
theme ownership native. The renderer loads bundled assets only, accepts raw
markdown plus resolved design tokens, reports measured height and link taps to
native, and blocks raw HTML, remote images, network navigation, and broad JS
bridges.

Session Chat mounts this island only for complex block markdown, tables, fenced
code, and fenced `diff` so ordinary chat scrolling remains native and smooth.

## Consequences

- This is a deliberate exception to the native UI architecture, not a move to a
  cross-platform app framework.
- Code highlighting and fenced diff rendering can reuse mature web rendering
  techniques without introducing React Native.
- Both apps must keep the native wrapper contract, local bundle, and security
  policy aligned.
- Chat rendering policy should favor native/plain rows when WebView rendering
  does not materially improve the message.
