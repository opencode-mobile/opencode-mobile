# TextMate physical latency plan

**Status:** Wave 2 implemented — Pixel 9 remeasure **MISS** (still ≫ 50 ms)  
**Baseline:** 2026-07-30 Pixel 9 `javascript` scorecard in
[`phase-6-calibration.md`](./phase-6-calibration.md)

## Goal

Make Session Chat TextMate highlighting fast enough on mid-tier Android that warm
`javascript` fences ≤ 2 000 chars leave the Slow band.

| Gate | When | Criterion (Pixel 9, warm, `joni-oniguruma`) |
| --- | --- | --- |
| Wave 1 exit | After Wave 1 remeasure | ≤2k warm p95 ≤ **50 ms** (Tier A UX) |
| Wave 2 exit | Only if Wave 1 misses | Same 50 ms bar; skip Wave 2 if already met |
| Stretch | Later release calibration | Tier B warm 2k p95 ≤ 12 ms |

## Diagnosis

Pixel 9 JS fences (~600–2300 chars) spent ~300–900 ms with 15k–52k
`RegexSearch` events. `NativeRegexSearch` was only ~9–18% of that time. Cost is
search volume × Kotlin per-search tax (always-on per-find metrics, Matcher /
pin / lease churn, try-all-rules at every cursor). Not scheduling, cold grammar,
or RuleResolve misses.

## Wave 1 — Cut tax and fan-out

1. **Gate per-search metrics** — production engine keeps coarse stages
   (Tokenize, Repair, GrammarLoad, IncrementalReuse, …) but does not record
   per-call `RegexSearch` / `NativeRegexSearch` unless
   `recordPerSearchMetrics` is enabled for calibration.
2. **Earliest-match short-circuit** — stop scanning later rules once a match at
   the current cursor is known (rules are ascending `order`).
3. **Cheapen Joni `search`** — reuse `Matcher` per compiled regex + line bytes,
   touch instead of per-search pin/lease, skip lookbehind normalize when unused.

Prove on Pixel 9 with the same JS fence size buckets; write a dated scorecard.

## Wave 2 — Multi-pattern first-match (software OnigScanner)

Joni 2.x has no native RegSet/OnigScanner. Wave 2 adds
`OpenCodeTextMateRegexBackend.findEarliest` with a shared two-pass software
scanner ([TextMateRegexScanSupport](../../shared/markdown-core/src/commonMain/kotlin/dev/opencode/mobile/markdown/OpenCodeTextMateTokenizer.kt)):

1. **At-cursor pass** — probe each rule with a one-position start window; return
   on the first hit (ascending rule id).
2. **Capped remainder** — otherwise find the earliest later match and tighten
   `endIndex` so remaining patterns do not rescan the whole line.

Tokenizer cursor loops call `findEarliest` instead of per-rule full-line
`findNext`. Prove again on Pixel 9 after install.

## Out of scope

Second owner shard; budget rebaseline without evidence; demoting `javascript`
before Wave 1 evidence; full soak / thermal matrices.
