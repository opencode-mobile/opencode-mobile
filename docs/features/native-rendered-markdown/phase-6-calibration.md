# TextMate Phase 6 Calibration

**Status:** provisional host/simulator close for Phase 6  
**Date:** 2026-07-29  
**Host:** arm64 macOS JVM (`:markdown-core:testDebugUnitTest`, KotlinRegex backend with synthetic allowlisted grammars)

Physical low/mid-tier release-build Tier B latency, full 30-minute soak on named devices, and Joni/libonig packaged-grammar matrices remain a **post-Phase-6 validation note**, not an open Phase 6 gate.

## Allowlist v1

Promoted canonical languages (`ALLOWLIST_VERSION = 1`):

`javascript`, `typescript`, `python`, `kotlin`, `swift`, `json`, `yaml`, `shellscript`, `rust`, `go`

## Tier A warm latency (provisional)

Sample model: 1 cold + 30 warm per language × bucket. Warm p95 shown below.

| Language | Bucket | Chars | Cold ms | Warm p50 ms | Warm p95 ms | Tier A budget (Android JVM) |
| --- | --- | --- | --- | --- | --- | --- |
| go | S | 2000 | 40 | 2 | 5 | ≤ 50 |
| go | L | 8000 | 16 | 15 | 17 | ≤ 150 |
| javascript | S | 2000 | 4 | 1 | 2 | ≤ 50 |
| javascript | L | 8000 | 14 | 9 | 16 | ≤ 150 |
| json | S | 2000 | 11 | 3 | 4 | ≤ 50 |
| json | L | 8000 | 34 | 22 | 34 | ≤ 150 |
| kotlin | S | 2000 | 0 | 0 | 0 | ≤ 50 |
| kotlin | L | 8000 | 3 | 3 | 5 | ≤ 150 |
| python | S | 2000 | 0 | 0 | 0 | ≤ 50 |
| python | L | 8000 | 3 | 3 | 4 | ≤ 150 |
| rust | S | 2000 | 0 | 0 | 0 | ≤ 50 |
| rust | L | 8000 | 3 | 3 | 6 | ≤ 150 |
| shellscript | S | 2000 | 0 | 0 | 0 | ≤ 50 |
| shellscript | L | 8000 | 2 | 2 | 2 | ≤ 150 |
| swift | S | 2000 | 0 | 0 | 0 | ≤ 50 |
| swift | L | 8000 | 3 | 3 | 4 | ≤ 150 |
| typescript | S | 2000 | 0 | 0 | 0 | ≤ 50 |
| typescript | L | 8000 | 3 | 3 | 3 | ≤ 150 |
| yaml | S | 2000 | 0 | 0 | 0 | ≤ 50 |
| yaml | L | 8000 | 1 | 1 | 2 | ≤ 150 |

All v1 languages remain promoted under this provisional matrix.

## Soak

| Kind | Platform | Duration | Cycles | Result |
| --- | --- | --- | --- | --- |
| PROVISIONAL_SHORT_SOAK | jvm-kotlinregex | 5 s | 2850 | pass (no crash; harness green) |

Full 30-minute soak remains opt-in via `textmateSoakSeconds=1800` / `TEXTMATE_SOAK_SECONDS=1800` on Android instrumentation and iOS simulator KMP suites.

## Engine ownership

Single owner retained on both platforms. No second shard — provisional Tier A queue/throughput did not miss under allowlist v1.

## Remaining physical-device validation (out of Phase 6 scope)

Before treating calibration as non-provisional / store release with TextMate default-on:

1. Tier B warm budgets on one low-tier and one mid-tier release build per platform (plan table: warm 2k p95 ≤ 12 ms; warm 8k p95 ≤ 40 ms; final 8k p95 ≤ 75 / p99 ≤ 150 ms).
2. Full 30-minute mixed-language soak on those hosts with retained-memory equality checks.
3. Packaged-grammar Joni + libonig matrices (not synthetic grammars).
4. Thermal soak on physical devices where thermal APIs are meaningful.
