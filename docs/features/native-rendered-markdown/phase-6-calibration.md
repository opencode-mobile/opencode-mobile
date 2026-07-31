# TextMate Phase 6 Calibration

**Status:** provisional host/simulator close for Phase 6  
**Date:** 2026-07-29 (operator workflow expanded 2026-07-30; Pixel 9
`javascript` scorecard 2026-07-30)  
**Host (Tier A table below):** arm64 macOS JVM (`:markdown-core:testDebugUnitTest`,
KotlinRegex backend with synthetic allowlisted grammars)

Physical low/mid-tier release-build Tier B latency, full 30-minute soak on named
devices, and Joni/libonig packaged-grammar matrices remain a **post-Phase-6
validation note**, not an open Phase 6 gate.

This document holds:

1. provisional Tier A numbers that closed Phase 6;
2. how to capture and read **device logbook** evidence for further speed work;
3. how to assess and optimize **per language**.

---

## Allowlist v1

Promoted canonical languages (`ALLOWLIST_VERSION = 1`):

`javascript`, `typescript`, `python`, `kotlin`, `swift`, `json`, `yaml`,
`shellscript`, `rust`, `go`

Non-members stay on complete semantic fallback unless force-enabled under
Developer Tools → **TextMate Languages**. Force-enable is for calibration and
debugging only; shipping promotion still requires the allowlist gate below.

---

## Operator loop (measure → classify → change → prove)

Use this whenever optimizing latency or deciding whether a language stays
promoted / can be promoted.

1. **Prepare** — TextMate Highlighting on. Clear the logbook (or note the
   timestamp) so the capture is attributable. For an unpromoted language, force
   enable it in TextMate Languages.
2. **Reproduce** — open Session Chat with representative fences for that
   language. Prefer a warm pass after the first cold grammar load. Avoid
   judging from a single cold row.
3. **Inspect in-app** — Settings → Developer Tools → **TextMate Logbook**.
   Status pill is Fast / OK / Slow for `Highlighted` / `NoSpans`. Subtitle shows
   wall ms plus `chars/ms` · `spans/ms`. Expand a row for stages, backend,
   throughput, diagnostics.
4. **Pull JSONL** — export the on-disk logbook (below) when you need aggregates,
   diffs across builds, or to attach evidence to this file.
5. **Classify** — use the triage table in [Assessing a language](#assessing-a-language).
6. **Change** — smallest lever first (scheduling → incremental → cache/compile →
   grammar/backend). Keep oracle fixtures green.
7. **Prove** — same fence-size buckets before/after; record warm p50/p95 and
   median chars/ms here or in an adjacent dated note. Promotion still needs both
   backends and soak evidence.

Bands (Fast/OK/Slow) are the **UX** judgment against fence-relative Tier A
budgets (≤2k → 50 ms, ≤8k → 150 ms, else scale). Throughput (`chars/ms`,
`spans/ms`) is for **cross-size comparison** and regression hunting.

---

## Pulling the TextMate logbook

On-disk format: JSON Lines, schema version 1 entries wrapping observation schema
version 2. Cap: 500 entries; file under ~2 MiB. Path is app-private — use
`run-as` / container APIs, not a world-readable export UI (v1).

### In-app (quick)

1. Settings → Developer Tools → TextMate Logbook.
2. Refresh after reproducing.
3. Expand rows for stage timings (stages overlap; they are not additive).
4. Clear only after you have pulled or copied what you need.

### Android (device or emulator)

Application id: `dev.opencode.mobile`  
Relative path: `files/debug/textmate-logbook-v1.jsonl`

```bash
SERIAL="<device-or-emulator-serial>"   # adb devices -l
OUT="${PWD}/.scratch/${SERIAL}-textmate-logbook-v1.jsonl"
mkdir -p .scratch
adb -s "$SERIAL" exec-out run-as dev.opencode.mobile \
  cat files/debug/textmate-logbook-v1.jsonl > "$OUT"
wc -l "$OUT"
```

Debug builds can use `run-as`. Release / Play builds may need a debuggable
artifact or a future in-app share action if `run-as` is denied.

Do not commit raw logbook dumps unless they are curated, content-free evidence
for a calibration note. Prefer summarizing into tables in this file.

### iOS Simulator

Bundle id: `dev.opencode.mobile`  
Relative path under Application Support:
`OpenCodeMobile/Debug/textmate-logbook-v1.jsonl`

```bash
UDID="<booted-simulator-udid>"   # xcrun simctl list devices booted
DATA="$(xcrun simctl get_app_container "$UDID" dev.opencode.mobile data)"
SRC="$DATA/Library/Application Support/OpenCodeMobile/Debug/textmate-logbook-v1.jsonl"
OUT="${PWD}/.scratch/${UDID}-textmate-logbook-v1.jsonl"
mkdir -p .scratch
cp "$SRC" "$OUT"
wc -l "$OUT"
```

### iOS physical device

There is no adb-style `run-as` for the sandbox. Prefer:

1. in-app logbook inspection for triage; or
2. Xcode → Devices and Simulators → download container → copy the same
   Application Support path; or
3. a future share/export action if container download is too heavy for routine
   calibration.

### Summarize a pull (optional)

Keep scripts out of CI; for a local rollup:

```bash
python3 - <<'PY'
import json, statistics
from collections import Counter, defaultdict
from pathlib import Path
path = Path(".scratch/REPLACE-textmate-logbook-v1.jsonl")
rows = [json.loads(l) for l in path.read_text().splitlines() if l.strip()]

def ms(ns): return ns / 1e6

def budget(chars):
    chars = max(chars, 0)
    if chars <= 2000: return 50.0
    if chars <= 8000: return 150.0
    return 150.0 * (chars / 8000.0)

def band(outcome, total_ns, chars):
    if outcome not in ("Highlighted", "NoSpans"): return None
    total = ms(total_ns); b = budget(chars)
    if total <= b * 0.5: return "Fast"
    if total <= b: return "OK"
    return "Slow"

print("n", len(rows), "outcomes", Counter(r["observation"]["outcome"] for r in rows))
by = defaultdict(list)
for r in rows:
    o = r["observation"]
    if o["outcome"] not in ("Highlighted", "NoSpans") or o["totalNanoseconds"] <= 0:
        continue
    lang = o.get("canonicalLanguage") or o.get("requestedLanguage") or "(none)"
    by[lang].append(o)
for lang, items in sorted(by.items()):
    totals = [ms(i["totalNanoseconds"]) for i in items]
    rates = [i["fenceCharacters"] / ms(i["totalNanoseconds"]) for i in items]
    bands = Counter(band(i["outcome"], i["totalNanoseconds"], i["fenceCharacters"]) for i in items)
    print(f"{lang}: n={len(items)} warm_ms_med={statistics.median(totals):.1f} "
          f"ms_p95={sorted(totals)[max(0,int(len(totals)*0.95)-1)]:.1f} "
          f"chars_per_ms_med={statistics.median(rates):.1f} bands={dict(bands)}")
PY
```

---

## What the logbook already records

Enough for most speed work without new instrumentation:

| Field | Use |
| --- | --- |
| `outcome` | Highlighted / NoSpans / Fallback / BudgetExceeded / Cancelled / Failed / … |
| `requestedLanguage` / `canonicalLanguage` | Per-language grouping |
| `fenceCharacters` / `fenceLines` / `spanCount` | Size + density |
| `totalNanoseconds` | Wall time for band + throughput |
| `backend` | `joni-oniguruma` / libonig / null on trim/fallback |
| `stages[]` | GrammarLoad, RegexCompile, Tokenize, RegexSearch, Repair, IncrementalReuse, … |
| `schedulingReason` | e.g. Trim — queue/pressure, not tokenize cost |
| `diagnosticKinds` | e.g. HighlighterFallback |
| `reusedLineCount` / `retokenizedLineCount` / `suffixReusedLineCount` | Incremental health |
| `queueWaitNanoseconds` | Scheduler wait vs work |

Derived in UI (not stored as separate columns): Fast/OK/Slow, `chars/ms`,
`spans/ms`.

### Gaps — add only when a decision is blocked

Do **not** expand observation schema for curiosity. Add a field when triage
cannot choose among levers:

| Gap | When it blocks | Likely addition |
| --- | --- | --- |
| Cold vs warm label | Cannot tell first-hit GrammarLoad from steady state | `coldStart` or “grammar cache miss on this request” flag |
| Tokenize-only ms | Wall time polluted by queue wait | already partly in stages + `queueWaitNanoseconds` — prefer those before new fields |
| Host / build id | Mixing emulator and Pixel dumps | filename / note convention, not schema |
| Content hash of fence | Need stable before/after without storing code | optional short hash of fence bytes |
| Thermal / trim level at start | Physical Tier B correlation | host counters on observation only if Tier B needs it |

Content of fences must never be persisted in the logbook.

---

## Assessing a language

Work **one canonical language** at a time. Group logbook rows by
`canonicalLanguage` (fallback to `requestedLanguage`).

### Include / exclude

- **Include:** `Highlighted` and `NoSpans` with `totalNanoseconds > 0`.
- **Exclude from speed stats:** `BudgetExceeded` / Trim (scheduling),
  `Cancelled`, pure `Fallback` without attempt, and the first cold row of a
  session when comparing warm p95 (still record cold separately).

### Budgets (UX)

| Fence chars | Wall-time budget | Fast | OK | Slow |
| --- | --- | --- | --- | --- |
| ≤ 2 000 | 50 ms | ≤ 25 ms | ≤ 50 ms | > 50 ms |
| ≤ 8 000 | 150 ms | ≤ 75 ms | ≤ 150 ms | > 150 ms |
| > 8 000 | `150 × (chars / 8000)` ms | ≤ 0.5× | ≤ 1× | > 1× |

### Throughput (efficiency)

- `chars/ms = fenceCharacters / (totalNanoseconds / 1e6)`
- `spans/ms = spanCount / (totalNanoseconds / 1e6)`

Compare **same language, same size bucket** across builds. Do not promote a
language on high chars/ms alone if Slow wall time remains common on chat-sized
fences.

### Triage table

| Observation | Interpretation | Next lever |
| --- | --- | --- |
| Many Slow, Tokenize + RegexSearch dominate stages | Real highlight cost | Grammar/regex path, repair, incremental reuse |
| Slow but chars/ms healthy on huge fences | Budget vs size | Confirm bucket; maybe budget rebaseline with evidence, not micro-opt |
| Spike of BudgetExceeded / Trim | Queue / pressure / coalescing | Scheduler limits, trim policy, reduce speculative work |
| Cold Slow then warm Fast/OK | First-hit load/compile | Expected; measure warm separately |
| Fallback + GrammarLookup miss | Gate or missing grammar | Allowlist / packaging — not a speed bug |
| High retokenized, low reused on small edits | Incremental miss | Phase 5 reuse / checkpoint identity |
| queueWait high, tokenize small | Waiting on owner | Scheduling / single-owner capacity |

### Per-language scorecard (fill when calibrating)

Copy a row into a dated subsection when promoting or optimizing:

| Field | Value |
| --- | --- |
| Language (canonical) | |
| Host / build | e.g. Pixel 9 release / iPhone mid-tier |
| Backend | joni-oniguruma / libonig |
| Sample | 1 cold + N warm, fence sizes |
| Warm p50 / p95 ms (2k) | |
| Warm p50 / p95 ms (8k) | |
| Median chars/ms (warm) | |
| Band mix (Fast/OK/Slow) | |
| Dominant stages | |
| Outcome | keep / demote / promote candidate / needs opt |
| Evidence | logbook pull path or commit note |

### Promotion / demotion

A language joins or stays on the versioned allowlist only after:

1. Joni **and** libonig correctness (oracle + representative Session Chat
   fixtures);
2. warm latency within the active Tier budgets on the target host class;
3. fallback / native-lifetime / memory checks;
4. soak evidence appropriate to the release bar (short provisional vs full
   30-minute physical).

Force-enable in Developer Tools does **not** promote. Demote (allowlist
removal or user opt-out default) if physical Tier B shows persistent Slow on
chat-sized fences after optimization attempts, with evidence recorded here.

---

## Dated physical scorecards

### 2026-07-30 — `javascript` on Pixel 9

| Field | Value |
| --- | --- |
| Language (canonical) | `javascript` (requested `js`) |
| Host / build | Pixel 9 (`56040DLAQ005VV`, product `tokay`), Android app `dev.opencode.mobile` |
| Backend | `joni-oniguruma` (packaged grammar; `GrammarLoad` cache hit on all rows) |
| Sample | Session Chat “JavaScript examples”
  (`ses_04dc58725ffeGaW8abiuM8maI4`); 6 `Highlighted` + 1 `Cancelled`
  (Supersession). Fence sizes 586 / 693 / 763 / 1799 / 2287 / 2303 chars.
  Warm only — grammar already loaded; no separate cold row. |
| Warm p50 / p95 ms (≤2k) | **419 / 500** (n=4; budget 50 ms) — max 763 ms @ 1799 chars |
| Warm p50 / p95 ms (~2.3k) | **897 / 876** (n=2; budget 150 ms) — max 919 ms @ 2303 chars |
| Median chars/ms (warm) | **~2.3** |
| Band mix (Fast/OK/Slow) | **0 / 0 / 6** |
| Dominant stages | Tokenize ≈ RegexSearch (~86–91% of tokenize wall). Native onig
  (`NativeRegexSearch`) only ~9–18% of `RegexSearch`. Search volume
  15k–52k events per fence. `queueWait=0`; `incrementalMode=None`. |
| Outcome | **needs opt** — fails active Tier A UX budgets and planned Tier B
  warm 2k p95 ≤ 12 ms on this mid-tier physical host. Provisional JVM
  matrix (JS 2k warm p95 2 ms) does not transfer. |
| Evidence | Local pull
  `.scratch/pixel9-textmate-logbook-v1.jsonl` (7 rows, 2026-07-30);
  do not commit raw dump. |

Stage breakdown (Highlighted only; stages overlap, not additive):

| Chars | Total ms | Tokenize ms | RegexSearch ms | NativeRegexSearch ms | Repair ms | RegexSearch events |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 586 | 499.7 | 472.3 | 408.0 | 74.5 | 25.8 | 15562 |
| 693 | 326.4 | 297.2 | 265.5 | 44.8 | 28.1 | 14852 |
| 763 | 339.1 | 307.1 | 276.5 | 36.0 | 30.6 | 15602 |
| 1799 | 763.3 | 714.5 | 647.3 | 66.3 | 44.9 | 38926 |
| 2287 | 875.9 | 811.0 | 722.4 | 85.0 | 62.1 | 45421 |
| 2303 | 918.6 | 845.5 | 773.9 | 66.8 | 68.4 | 51971 |

Triage: “Many Slow, Tokenize + RegexSearch dominate” → grammar/regex path
(and search volume), not scheduling or cold load.

### 2026-07-30 — `javascript` on Pixel 9 after Wave 1

| Field | Value |
| --- | --- |
| Language (canonical) | `javascript` (requested `js`) |
| Host / build | Pixel 9 (`56040DLAQ005VV`), Wave 1 build (per-search metrics gated;
  earliest-match short-circuit; Joni Matcher reuse) |
| Backend | `joni-oniguruma` |
| Sample | Same session `ses_04dc58725ffeGaW8abiuM8maI4` reopened. Logbook retained
  earlier baseline rows (0–12, still show `RegexSearch` event counts). Wave 1
  evidence = rows with **no** `RegexSearch`/`NativeRegexSearch` stages (13–18).
  Warm subset = no `RegexCompile` (15–18). |
| Warm p50 / p95 ms (≤2k) | **211.5 / 267.8** (n=4; budget 50 ms) — max 335 ms @ 1799 chars |
| Cold-ish ~2.3k | 581 ms @ 2287 (8 compiles); 1762 ms @ 2303 (243 compiles) |
| Median chars/ms (warm ≤2k) | **~3.6** (was ~2.3) |
| Band mix (Fast/OK/Slow) | **0 / 0 / 4** (warm ≤2k); still all Slow |
| Dominant stages | Tokenize + Repair (per-search stages no longer recorded). |
| Outcome | **Wave 1 exit MISS** — ~1.3–3.3× faster than baseline on matching warm
  fences (median ~2.2×) but ≤2k warm p95 **268 ms ≫ 50 ms**. Proceed to Wave 2
  (multi-pattern / OnigScanner-style). |
| Evidence | `.scratch/pixel9-textmate-logbook-v1.jsonl` (19 rows; Wave 1 = last 6) |

Warm Wave 1 vs baseline (same fence sizes):

| Chars | Baseline ms | Wave 1 warm ms | Speedup |
| --- | ---: | ---: | ---: |
| 586 | 499.7 | 151.0 | 3.31× |
| 693 | 326.4 | 155.2 | 2.10× |
| 763 | 339.1 | 267.8 | 1.27× |
| 1799 | 763.3 | 335.3 | 2.28× |

### 2026-07-30 — `javascript` on Pixel 9 after Wave 2

| Field | Value |
| --- | --- |
| Language (canonical) | `javascript` (requested `js`) |
| Host / build | Pixel 9, Wave 2 software OnigScanner (`findEarliest`) |
| Backend | `joni-oniguruma` (per-search metrics gated) |
| Sample | Cleared logbook; 6 `Highlighted`, 0 `BudgetExceeded`. Fence sizes
  2303 / 2287 / 1799 / 763 / 693 / 586. Warm ≤2k = rows with
  `RegexCompile=0` (586–1799). |
| Warm p50 / p95 ms (≤2k) | **175 / 200** (n=4; budget 50 ms) — max 379 ms @ 1799 |
| Cold-ish ~2.3k | 687 ms @ 2287 (8 compiles); 1877 ms @ 2303 (243 compiles) |
| Band mix (Fast/OK/Slow) | **0 / 0 / 4** (warm ≤2k) |
| Outcome | **Wave 2 exit MISS** — still Slow vs Tier A 50 ms. Mixed vs Wave 1
  warm (better on 763, similar on 693, worse on 586 / 1799). Rollup p95
  improved 268→200 ms but not enough to pass. |
| Evidence | `.scratch/pixel9-textmate-logbook-v1.jsonl` (6 rows) |

Before / Wave 1 / Wave 2 (warm ≤2k where available):

| Chars | Baseline ms | Wave 1 ms | Wave 2 ms | W2 vs base | W2 vs W1 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 586 | 499.7 | 151.0 | 200.0 | 2.50× | 0.76× |
| 693 | 326.4 | 155.2 | 150.3 | 2.17× | 1.03× |
| 763 | 339.1 | 267.8 | 146.3 | 2.32× | 1.83× |
| 1799 | 763.3 | 335.3 | 378.6 | 2.02× | 0.89× |

---

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

---

## Soak

| Kind | Platform | Duration | Cycles | Result |
| --- | --- | --- | --- | --- |
| PROVISIONAL_SHORT_SOAK | jvm-kotlinregex | 5 s | 2850 | pass (no crash; harness green) |

Full 30-minute soak remains opt-in via `textmateSoakSeconds=1800` /
`TEXTMATE_SOAK_SECONDS=1800` on Android instrumentation and iOS simulator KMP
suites.

---

## Engine ownership

Single owner retained on both platforms. No second shard — provisional Tier A
queue/throughput did not miss under allowlist v1. Reconsider a second shard
only if measured single-owner Tier A miss persists with non-regression proof
on foreground latency and memory.

---

## Remaining physical-device validation (out of Phase 6 scope)

Before treating calibration as non-provisional / store release with TextMate
default-on:

1. Tier B warm budgets on one low-tier and one mid-tier release build per
   platform (plan table: warm 2k p95 ≤ 12 ms; warm 8k p95 ≤ 40 ms; final 8k
   p95 ≤ 75 / p99 ≤ 150 ms). Use the [operator loop](#operator-loop-measure--classify--change--prove)
   and per-language scorecards above; attach logbook pulls as evidence.
   Started: [2026-07-30 `javascript` on Pixel 9](#2026-07-30--javascript-on-pixel-9)
   — fails budgets; optimization required before non-provisional close.
2. Full 30-minute mixed-language soak on those hosts with retained-memory
   equality checks.
3. Packaged-grammar Joni + libonig matrices (not synthetic grammars).
4. Thermal soak on physical devices where thermal APIs are meaningful.
