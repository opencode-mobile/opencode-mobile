# OPE-92 — Format-always code blocks; color later

**Status:** implemented (Android unit tests green; device smoke pending)  
**Issue:** [OPE-92](https://linear.app/opencode-mobile/issue/OPE-92/structure-first-code-blocks-while-streaming)  
**Parent feature:** [`native-rendered-markdown`](./plan.md)

## User goal

Never show unprocessed markdown for markdown-bearing content. Fenced code must
render as a real code block (chrome + monospace + spacing) as soon as the fence
is detectable. Token colors are a separate step and may arrive later.

## Contract

### TextMate enabled

1. Load / stream messages
2. Parse markdown structure (blocks, fences, nested blocks)
3. Structure-only highlight — code chrome, neutral monospace, **no** token colors
4. Display scaffold
5. TextMate applies colors later (Final / `attachTextMateResult`; spans only)

### TextMate disabled

1. Load / stream messages
2. Parse markdown structure
3. Full semantic-fallback highlighting
4. Display — done (no TextMate pass)

## In scope

- Shared `OpenCodeStructureOnlyHighlighter` (empty spans).
- Streaming text-lane publish always attaches a formatted `MarkdownDocument`
  when markdown is detected (no size-based plain-source escape hatch).
- TextMate on → structure-only first paint; skip streaming semantic decoration
  so colors do not flash before TextMate.
- TextMate off → semantic-fallback first paint.
- Write scaffold into `BlockDecorationCache` for multi-block rows.
- FinalPlaceholder / Final TextMate path unchanged for color upgrade.
- Android + iOS parity.

## Out of scope

- Progressive TextMate while streaming (colors still land on Final).
- Changing Tier A latency budgets or TextMate tokenizer performance.

## Expected results

1. Streaming fence (any size, markdown detected) → first coalesce frame has
   `CodeBlock` chrome, not raw source `Text`.
2. TextMate on → first-paint code spans empty; colors arrive on Final TextMate.
3. TextMate off → first paint has semantic-fallback spans.
4. Blocked TextMate/decoration preparer does not delay format-first paint.
5. No streaming path leaves detectable markdown as unprocessed plain source.
6. History open / scroll-back: markdown-bearing messages publish formatted
   (structure or semantic) before TextMate enrichment; enrichment must not skip
   scaffold docs that still await TextMate colors.

## Entry points

- Android: `DisplayMessagePipeline.publishPlainStreamingFrame`
- iOS: `DisplayMessagePipeline.publishPlainStreamingFrame`
- Shared: `OpenCodeStructureOnlyHighlighter`, `MarkdownParseRequest.structureOnly`
