# OPE-116 — Session Compact richer status chrome

Task plan for
[OPE-116](https://linear.app/opencode-mobile/issue/OPE-116/session-compact-richer-status-chrome).
Feature contract: [plan.md](./plan.md). Prototype verdict: **B**.

## Goal

While Commands → compact runs, show **Compacting…**; when it finishes, toast a
context usage delta when possible.

## Current baseline (OPE-58)

- Commands `[compact]` → `summarizeSession` → snackbar `Session compacted.` →
  refresh messages + session status.
- During the awaited HTTP call, server busy usually surfaces as **Working**.

## Work items

### 1. Local compacting flag

- Set when Compact is chosen (after Commands dismiss, before/at summarize start).
- Clear on summarize success or failure (and on session unbind/cancel if needed).
- Capture `contextUsageFraction` **before** the call when available.

### 2. Display override

- While compacting is true, Session Chat busy chrome shows **Compacting…**
  instead of **Working** (status label / turn-active surfaces that currently
  mirror `sessionStatusLabel`).
- Stop stays available (compacting counts as turn-active).
- Do not change Home list status mapping in this ticket unless it already
  shares the same label helper and stays consistent for free.

### 3. Success snackbar

- After successful summarize (chrome already cleared):
  - if before and after fractions exist:
    `Context compacted · {before}% → {after}%` (integer percents);
  - else: `Session compacted.`
  - After fraction comes from the SSE-updated timeline + light status refresh,
    not a full message reload that gates clearing Compacting….
- Keep failure copy: `Couldn't compact session context.` (or current equivalent).

### 4. Tests

- Compacting label while summarize is in flight.
- Success snackbar delta when fractions known.
- Fallback success copy when fractions missing.
- Failure clears compacting and shows error.

### 5. Parity smoke

- iOS + Android: compact a session with known high context → see Compacting… →
  see delta toast → context ring lower.

## Acceptance

- [x] Compacting… visible for the in-flight summarize window on iOS and Android
- [x] Delta snackbar when before/after measurable; fallback otherwise
- [x] Failure clears compacting and errors clearly
- [x] No timeline compaction card; no float pill
- [x] Unit coverage above; smoke on both platforms — device smoke still open

## Follow-up — remote Compacting…

Observers with the same session open show Compacting… when the server turn is
busy and the active turn has a compaction part or assistant mode/agent
`"compaction"`. Domain keeps compaction parts for presence; timeline still does
not render them. Web’s “History compacted” divider remains out of scope.

## Out of scope

- Prototype A/C surfaces
- Rendering `compaction` parts as timeline rows / web divider
- New summarize Commands row
- Design-system API changes for the context ring (optional local pulse only)
- Relying on `session.time.compacting` or V2 `session.compaction.*`
- Remote success snackbar / context-delta toast

## Branch

`feature/ope-116-session-compact-status-chrome`
