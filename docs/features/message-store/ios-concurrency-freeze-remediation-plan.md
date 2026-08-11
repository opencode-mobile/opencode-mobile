# iOS concurrency freeze remediation plan

Parent feature: [Message Store](plan.md)

Related:

- [OPE-117](https://linear.app/opencode-mobile/issue/OPE-117/ios-production-freeze-on-session-chat-send-and-model-select) —
  MainActor send freeze (landed)
- [OPE-135](https://linear.app/opencode-mobile/issue/OPE-135/ios-off-main-timeline-and-display-pipeline) —
  off-main timeline / pipeline (landed; fan-out follow-up open)
- [OPE-136](https://linear.app/opencode-mobile/issue/OPE-136/ios-diffable-timeline-apply-storm-freezes-session-chat) —
  Diffable apply storm (landed)
- [OPE-134](https://linear.app/opencode-mobile/issue/OPE-134/main-thread-hang-watchdog-for-diagnostic-logs) —
  hang watchdog (Linear Done; **not on `main`**)
- Session Chat: [progress](../session-chat/progress.md)
- Diagnostic Logs: [plan](../diagnostic-logs/plan.md)
- Foreground Event Stream: [plan](../foreground-event-stream/plan.md)

## Status

Stages 1–2 implemented on `feature/ope-140-ios-concurrency-freeze-remediation`
([OPE-140](https://linear.app/opencode-mobile/issue/OPE-140/ios-concurrency-freeze-remediation)).
Stage 0 hang watchdog implemented on
`feature/ope-134-main-thread-hang-watchdog`
([OPE-134](https://linear.app/opencode-mobile/issue/OPE-134/main-thread-hang-watchdog-for-diagnostic-logs));
Manual QA still open. Stages 3–5 remain.

## Objective

Stop residual Session Chat freezes and make the next force-quit diagnosable.
Keep MainActor for UI publish and UIKit Diffable apply only. Do not flip
`SWIFT_DEFAULT_ACTOR_ISOLATION = MainActor` project-wide.

## Context

App target settings that drive the failure mode:

| Setting | Value |
| --- | --- |
| Swift language version | 5.0 |
| Default actor isolation | `MainActor` |
| Approachable Concurrency | `YES` |
| Explicit `SWIFT_STRICT_CONCURRENCY` | unset |

Root pattern: unmarked types and unstructured `Task { }` inherit MainActor.
Heavy ingress or fan-out on that actor starves UI (send / stream / open).

Already shipped mitigations (do not redo):

1. Nonisolated MessageSync / SSE byte loop / API + message domain types
   (OPE-117).
2. Off-main DisplayFrame / timeline projection / DisplayMessagePipeline
   (OPE-135).
3. Declared Diffable apply packets; nil-packet identity-stable harden
   (OPE-136).

## User-visible outcome

- Send, model select, and dense streaming stay interactive on Release /
  TestFlight.
- Warm open of a large cached session with Syncing / Working chrome does not
  force-quit.
- After a hang + restart, Share Diagnostic Logs includes an explicit hang
  signal (not only a multi-minute blackout).

## In scope

1. Hang-watchdog landing or reopen so freezes leave evidence.
2. Unlock-before-resume in `MessageEventStaging`.
3. `nonisolated` footgun cleanup for default-arg / pure adapter types that
   currently warn under default MainActor isolation.
4. Per-SSE MainActor LiveStore fan-out reduction (OPE-135 leftover).
5. Residual MainActor hitch reduction after off-main projection (row rebuild /
  Diffable apply QA).
6. Focused regression tests + Release / TestFlight freeze matrix.

## Out of scope

- Removing `SWIFT_DEFAULT_ACTOR_ISOLATION = MainActor` project-wide.
- Swift 6 language-mode migration as a freeze fix.
- Rewriting Android concurrency (Android already asserts MessageSync off main).
- Public `DisplayFrame` / Sync–Pipeline absorption (parked Phase 7b).
- New design-system components.
- Background SSE, push, or guaranteed background sync.

## Stage map

| Stage | Goal | Risk | Depends on |
| --- | --- | --- | --- |
| 0 | Hang evidence on `main` | Low | — |
| 1 | Smallest safe correctness / warning fixes | Low | — |
| 2 | Cut per-SSE MainActor fan-out | Medium | 0 recommended for soak |
| 3 | Residual timeline MainActor hitch | Medium | 2 preferred |
| 4 | Optional isolation cleanup | Low | 1–3 |
| 5 | Release / TestFlight validation | — | 0–3 (4 optional) |

Stages 0 and 1 may run in parallel. Stage 2 is the primary residual freeze
fix. Do not start Stage 4 until Stages 1–3 are green or explicitly deferred.

---

## Stage 0 — Hang evidence

**Owner feature:** Diagnostic Logs (OPE-134).

### Work

1. Confirm whether hang-watchdog code exists on any branch; if Linear Done but
   absent from `main`, reopen or land the missing PR.
2. iOS + Android `MainThreadHangWatchdog` writing to Diagnostic Logs (`hang`
   area or equivalent).
3. Unit tests for detect / recover logging.
4. Docs under `docs/features/diagnostic-logs/`.

### Done when

- Force-quit after an intentional main-thread stall → restart → Share
  Diagnostic Logs shows hang detection.
- `main` contains the watchdog (not only a Linear Done status).

### Out of stage

Stack-capturing / symbolicated backtraces beyond the agreed OPE-134 scope.

---

## Stage 1 — Smallest safe fixes

**Owner feature:** Message Store (+ Session Ready types as needed).

### 1a. Staging unlock-before-resume

In `MessageEventStaging.deliverLocked` / finish / recovery wakes:

1. Copy the waiter under the lock.
2. Unlock.
3. `resume` outside the lock.

Add or extend a regression that exercises enqueue while a `dequeue`
continuation is waiting (no deadlock under synchronous resume).

### 1b. Default-isolation footguns

Clear current compiler warnings and the same class of footgun:

- Mark pure value / adapter / persistence helpers `nonisolated` when they are
  not UI-owned (same pattern as message DTOs / `MessageDomainAdapter`).
- At minimum: `SessionDomainAdapter` default init site,
  `InMemorySessionReadyPersistence`, `SystemSessionReadyClock`.
- Sweep sibling adapters only when they block off-main call sites or warn.

### Done when

- Staging never resumes a continuation while holding `NSLock`.
- Simulator build has no MainActor default-init warnings for these sites.
- Existing MessageSync / Ready unit suites still pass.

---

## Stage 2 — Per-SSE MainActor fan-out

**Owner features:** Foreground Event Stream + Message Store (OPE-135 leftover).

### Problem

`DefaultForegroundEventStream` parses off-main, then every event hops to
MainActor to publish. `@MainActor` LiveStores (permission, status, question,
todo, session, ready) each iterate the full stream. Dense
`message.part.delta` traffic still taxes MainActor after OPE-117 / OPE-135.

### Target design

1. Keep byte read + line parse off MainActor (already true).
2. Publish from a nonisolated (or actor-isolated) broadcaster that is safe to
   consume off-main.
3. Filter by event type / session **before** any MainActor hop.
4. MainActor hops only for UI-owned subscribers that need that event class.
5. MessageSync remains the high-volume consumer and must not depend on
   MainActor publish to make forward progress.

### Implementation slices (keep PRs small)

| Slice | Change | Verify |
| --- | --- | --- |
| 2.1 | Protocol / stream shape that allows nonisolated subscribe + typed filter | Unit: subscriber receives off-main; MainActor hop count measurable or asserted in test double |
| 2.2 | Wire MessageSync (already detached) to the nonisolated path | `initialLoad_messageLoopDoesNotRunOnMainThread` still green; no send-path deadlock |
| 2.3 | Permission / question / status / todo / session / ready: skip irrelevant types before MainActor work | Under synthetic delta flood, MainActor publish work for those stores stays near zero |
| 2.4 | Docs: decisions + parity note vs Android dispatcher fan-out | Progress checkboxes |

### Done when

- Dense synthetic SSE deltas do not monopolize MainActor solely for LiveStore
  fan-out.
- Chat send + stream remain interactive in Debug and Release configurations.
- Regression coverage exists for “message deltas do not enter permission /
  question refresh paths.”

### Explicit non-goals for Stage 2

- Redesigning LiveStore product behavior.
- Moving Screen Models off MainActor.
- Removing Approachable Concurrency / default isolation.

---

## Stage 3 — Residual timeline MainActor hitch

**Owner feature:** Message Store / Session Chat.

### 3a. Post-projection row materialization

`SessionChatTimeline` already builds `DisplayFrame` / `TimelineFrame` in
`Task.detached`, then rebuilds `SessionChatRow` on MainActor with cooperative
yields. Measure whether large warm opens still hitch; if yes:

- Prefer publishing immutable timeline rows from the detached projection and
  mapping to UI rows with less MainActor CPU; or
- Keep mapping on MainActor but bound work per turn more aggressively.

Optimize only with Instruments / hang-watchdog evidence (Stage 0).

### 3b. Diffable apply QA (OPE-136 soak)

Manual + regression confirmation:

1. Always pass declared `TimelineFrame` apply packet (status chrome must not
   nil it).
2. Warm-cache open with Syncing / Working indicator — no Diffable apply loop,
   no force-quit.
3. Keep
   `nilPacket_identityStable_doesNotReconfigureAllOnRepeatApply` green.

### Done when

- Warm large-session open stays scrollable within the soak expectations in
  message-store progress / Phase 7a matrix.
- No new Diffable storm regression.

---

## Stage 4 — Optional isolation cleanup

Run only after Stages 1–3 are accepted or explicitly deferred.

1. Revisit `@MainActor` on `DefaultMessageRepository` if it still forces
   observation setup onto MainActor without benefit.
2. Prefer `Task { @concurrent in … }` over `Task.detached` where Approachable
   Concurrency makes inheritance the wrong default and detachment is only an
   escape hatch.
3. Do **not** change project-wide default isolation in this stage.

---

## Stage 5 — Validation matrix

Run on Release / TestFlight (Debug alone is insufficient; OPE-117 was
Release-only).

| # | Scenario | Pass |
| --- | --- | --- |
| 1 | Send prompt on live session | UI stays interactive; chat + SSE diagnostic lines continue |
| 2 | Model select then send | Same |
| 3 | Dense streaming reply | Scroll / stop remain usable |
| 4 | Warm open large cached session + Syncing / Working chrome | No freeze / Diffable loop |
| 5 | Intentional hang or residual freeze + force-quit | Diagnostic export shows hang signal (Stage 0) |
| 6 | Permission / question during stream | Correct prompts; no MainActor blackout |

Record durable results in `message-store/progress.md` and
`diagnostic-logs/progress.md`; keep raw exports out of git.

---

## Ticket guidance

Prefer one ticket per stage (or per Stage 2 slice if fan-out PRs need to stay
small):

| Stage | Suggested focus | Existing key |
| --- | --- | --- |
| 0 | Land hang watchdog on `main` | OPE-134 (reopen or follow-up) |
| 1 | Staging unlock + `nonisolated` footguns | new or fold into fan-out ticket |
| 2 | Per-SSE MainActor fan-out | OPE-135 follow-up / new child |
| 3 | Timeline hitch + Diffable soak | OPE-136 soak / new child |
| 4 | Optional cleanup | only if still justified |
| 5 | TestFlight matrix | attach to parent |

Branch naming when ticket-backed: `feature/ope-<n>-<short-slug>` (never
username prefixes).

## Verification rules (every stage)

1. Re-check concurrency build settings before interpreting diagnostics.
2. One category of fix per PR; rebuild and test before the next category.
3. Prefer behavior-preserving isolation moves over architecture rewrites.
4. Do not use `@MainActor`, `@unchecked Sendable`, or `nonisolated(unsafe)` as
   blanket fixes; document invariants when escape hatches are required.
5. Use hang watchdog + Instruments for performance claims; do not guess.

## Artifact ledger

| Artifact | Action |
| --- | --- |
| This plan | Source of truth for staged remediation |
| `message-store/progress.md` | Link remaining freeze work here |
| `message-store/ope-135-plan.md` | Fan-out remains the named follow-up; execution detail lives here |
| `diagnostic-logs/progress.md` | Stage 0 / hang QA status |
| `session-chat/progress.md` | Unchanged unless Stage 3/5 records Session Chat–visible outcomes |
| `parity-gaps.md` | Update only if Stage 2 introduces an intentional iOS/Android fan-out difference |
| `decisions.md` | Record Stage 2 broadcaster shape once implemented |
