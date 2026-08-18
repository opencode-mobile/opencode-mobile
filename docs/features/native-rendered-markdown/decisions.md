# Native Rendered Markdown — Decisions

Still binding:

- Native SwiftUI / Compose rendering. No WebView island.
- Shared KMP parse + TextMate highlighter (`shared/markdown-core`). Android
  Joni; iOS hybrid regex / Oniguruma path as implemented.
- Streaming: format structure immediately; semantic color can arrive later.
  Do not flash unstyled raw markdown as the settled state.
- Grammars are bundled. No network load at highlight time.
- Logbook / diagnostics must not include message source.
