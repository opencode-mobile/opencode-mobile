# Message display rendering — Phase 7b follow-up plan

## Status

Slice 1 (soak coverage) complete — scenarios 4–7 **full** on Android and iOS.
Phase 7a closed the main hardening plan. This document remains the canonical
Phase **7b** umbrella for parked architecture reopen triggers.

Scroll remains **view-owned**. Screen Model does not gain viewport geometry.
Public repository observation stays **list-shaped**.

Companion decisions lock:
[decisions.md](./decisions.md#message-display-rendering-hardening--phase-7b-follow-ups).
Visual companion:
[message-display-rendering-7b-plan.html](./message-display-rendering-7b-plan.html).

Linear: [OPE-86](https://linear.app/opencode-mobile/issue/OPE-86/message-display-rendering-phase-7b)
(umbrella) · [OPE-87](https://linear.app/opencode-mobile/issue/OPE-87/fill-phase-7a-soak-coverage-gaps)
(Slice 1 coverage — Done).
## Objective

Close deferred hardening follow-ups without stuffing high-risk refactors into a
single gate. Ship only work with clear product or reliability value. Park
architecture cleanup that has no consumer, cost signal, or UX request, with
explicit reopen triggers for a future triage — not open options inside 7b.

## Context (locked facts)

- Sole production consumer of `observeDisplayMessages` is `SessionChatTimeline`
  (plus tests).
- Internal `DisplayFrame` lives in the UI package and depends on
  `SessionChatTimelineBlock`; a clean domain observation flip needs a package
  move or a DTO — not a free rename.
- `SessionMessageEngine` is a thin reducer facade; `MessageSync` (~727 LOC) and
  `DisplayMessagePipeline` (~568–655 LOC) remain separate orchestrators.
- Fingerprints remain on the poll/REST authority path only. Phase 7a soak did
  **not** prove measurable fingerprint cost.
- Optional `SessionChangeSet` → pipeline decoration hint was never wired;
  correctness uses full-list fallback.
- `syncHealth` is assert-only inside `MessageSync`.
- No device p95 soak CI exists; `android-test-lab` is Robo, not soak.
- Phase 7a soak partials (repeated overflow, parser failure, rapid switch, and
  related pressure gaps) are **coverage debt**, not architecture debt.

## Locked choices

| # | Question | Lock | Rationale |
| --- | --- | --- | --- |
| Q1 | Public `DisplayFrame` location | **(B)** keep list-shaped forever | Sole consumer is the timeline region; internal UI `DisplayFrame` already feeds the projector. No second consumer justifies a domain package move (A) or lean DTO migration (C). |
| Q2 | Engine absorption DoD | **(C)** defer indefinitely / intentional 3-layer | Facade + Sync + Pipeline is the shipped architecture. True merge (A) or “owns both, types remain” (B) is cleanup without a correctness or product trigger. |
| Q3 | Fingerprint trigger | **(C)** keep / won’t-do for 7b | No soak cost signal. Do not replace with revision now (B). Do not open a measure-then-gate ticket (A) without a regression report. |
| Q4 | Changeset hint transport | **(C)** skip for 7b | Hint never wired; correctness OK. Do not extend `MessageListUpdate` (A) or invent `DisplayFrame.changeSet` (B) while public observation stays list-shaped. |
| Q5 | `syncHealth` UI | **(C)** stay assert-only / out of 7b | Fault matrix already asserts health. No product request for a Session Chat banner/chip (A) or public-frame field (B). |
| Q6 | p95 / soak CI | **(C)** out of 7b | No existing p95 harness; hard CI (A) would be flaky invention. Nightly/manual (B) is optional ops later, not a 7b delivery gate. |
| Q7 | Ticket shape | **(B)** umbrella plan + risk-sliced tickets | Prefer slices over one epic of all six deferred items (A). Prefer not collapsing 7b to “only cheap opts” language that hides the DEFER/OUT locks (C) — the umbrella records parks explicitly. |

## MUST / SHOULD / DEFER / OUT

### MUST (7b umbrella)

| Item | Exit criteria |
| --- | --- |
| Publish this plan + decisions lock; align `progress.md` / `parity-gaps.md` language with these locks | Docs agree: list-shaped observe, intentional 3-layer, fingerprints retained, assert-only health, no 7b architecture mega-ticket |
| Ship a **separate coverage ticket** for Phase 7a soak partials (scenarios 4–7 and named pressure gaps) | Shared matrix rows move from **partial** → **full** on Android and iOS under unit/characterization + MessageSync fault fixtures |
| Preserve view-owned scroll and list-shaped public observation | No Screen Model viewport geometry; no public `DisplayFrame` observation flip |

### SHOULD

| Item | Notes |
| --- | --- |
| Keep soak matrix + parity-gaps inventory current when coverage lands | Update [phase-7a-soak-matrix.md](./phase-7a-soak-matrix.md) (or successor coverage doc) and [parity-gaps.md](./parity-gaps.md) in the same change |
| Prefer additive fixtures over production API churn | Inject parser failure, repeated overflow, and rapid A→B→A switches at existing seams |

### DEFER (not in 7b; reopen only via new triage)

None of these are 7b slices. Reopen triggers below create a **new** planning lock
— they do not reopen options inside this plan.

| Item | Reopen trigger |
| --- | --- |
| Public `DisplayFrame` observation (would require re-triaging Q1 away from B) | A second non-timeline consumer needs frame metadata that `DisplayMessageListUpdate` cannot carry without hacks |
| Absorb Sync + Pipeline into `SessionMessageEngine` | Split ownership causes a shipped correctness bug, or a required single interface-level test surface cannot be expressed without absorption |
| Poll/REST fingerprint → revision gates | Profiler or soak shows measurable poll/REST fingerprint cost on the hot path |
| Optional `SessionChangeSet` → pipeline decoration hint | Decoration invalidation is measurably over-broad on the live path after a recorded cost investigation |
| `syncHealth` banner/chip (or any UI publication) | Product asks for visible recovering / stale / healthy in Session Chat |
| Sole-text `DisplayMessage.renderedMarkdown` bridge deletion | Zero production readers of message-root markdown remain; per-block path is exclusive |
| Device p95 / soak CI | Release process explicitly requires automated responsiveness gates (then choose best-effort vs hard gate in that triage) |

### OUT of 7b (and out of this hardening follow-up)

| Item | Reason |
| --- | --- |
| Public observation flip to `DisplayFrame` | Q1 = B |
| Sync/Pipeline absorption / type deletion | Q2 = C |
| Fingerprint retirement or measure-then-gate ticket | Q3 = C; no cost signal |
| Wiring change-set → pipeline hint | Q4 = C |
| `syncHealth` UI (banner/chip or public-frame field) | Q5 = C |
| Hard or best-effort device p95 CI as a 7b deliverable | Q6 = C |
| Sole-text `renderedMarkdown` bridge deletion | Residual bridge; not coverage debt; not architecture required for reliability |
| Moving scroll / viewport into Screen Model | Forever out of scope for this feature line |
| Forcing Android drag coalesce to match iOS | Intentional parity-gap; revisit only on product-visible divergence |
| One epic that bundles all six deferred architecture items | Q7 = B |

## Recommended slice order

Only Slice 1 is in-scope work. Later numbers exist only if a reopen trigger fires
and a **new** triage supersedes this lock.

### Slice 1 — Soak coverage debt (shipped)

**Ticket:** [OPE-87](https://linear.app/opencode-mobile/issue/OPE-87/fill-phase-7a-soak-coverage-gaps)
under [OPE-86](https://linear.app/opencode-mobile/issue/OPE-86/message-display-rendering-phase-7b)
(not an architecture PR).

**Exit criteria — met**

- Matrix scenarios **4–7** are **full** on Android and iOS.
- No production API shape change.
- Scroll contracts unchanged and still view-owned.
- Coverage doc updated; intentional architecture parks unchanged in
  `parity-gaps.md`.

### Slice 2+ — Architecture reopen (not scheduled)

Do **not** open tickets for public `DisplayFrame`, engine absorption,
fingerprint retirement, change-set hint, `syncHealth` UI, or p95 CI under 7b.
If a reopen trigger fires, create a new triage lock; do not treat this plan as
authorization.

## Phase 7a soak partials

**Belong in a separate coverage ticket** under this umbrella (Slice 1), **not**
in an architecture ticket and **not** as unfinished Phase 5/6 host work.

They are test/coverage debt left after an honest 7a gate (unit/characterization
+ MessageSync fault coverage was gate-sufficient). Closing them does not require
public `DisplayFrame`, engine absorption, or CI invention.

## Sole-text `renderedMarkdown` bridge

**Out of 7b.** Keep the residual sole-text bridge. Per-block Markdown remains
the decoration home (`BlockDecorationCache`, `blockID` + `contentRevision`).
Deletion waits for the DEFER reopen trigger above (no remaining production
readers).

## Non-goals

- Replacing the native Markdown renderers for visual redesign.
- Shared cross-platform UI/runtime.
- Server protocol changes.
- Persistent message storage, offline mode, or pagination.
- Background SSE / push / guaranteed background sync.

## Phase gate (7b complete)

7b is complete when:

1. This plan and the decisions section agree with `progress.md` and
   `parity-gaps.md`.
2. Slice 1 coverage ticket is green on both platforms (scenarios 4–7 full).
3. No architecture mega-migration was smuggled in without a new triage lock.
4. Public observation remains list-shaped; engine remains intentional 3-layer;
   fingerprints retained; `syncHealth` assert-only; scroll view-owned.

Reference device budgets from the original hardening plan remain documentation
only — not 7b CI gates.
