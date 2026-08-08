# Session Compact

## User Goal

When the user runs Commands → **compact**, Session Chat should make compaction
feel intentional: show **Compacting…** while the request is in flight, then
confirm context was reduced (with a before→after delta when measurable).

## In Scope

- Richer **status chrome only** (prototype variant B) on iOS and Android.
- Optimistic **Compacting…** label while `summarizeSession` /
  `POST /session/{id}/summarize` is in flight (wins over generic Working).
- **Remote observers:** Compacting… when session is busy and the active turn
  has a kept compaction part or assistant mode/agent `"compaction"`.
- Success snackbar with context usage delta when before/after fractions are
  available; stable fallback copy otherwise (initiator only).
- Clear failure snackbar; clear local compacting chrome on success or failure.
- Keep existing Commands `[compact]` entry, visibility gates, and repository
  summarize call from OPE-58.
- Canonical docs under this directory; prototype verdict B.

## Out Of Scope

- Separate Summarize product or Commands row (`/summarize` ≡ `/compact`).
- Timeline checkpoint / compaction part rendering (prototype A).
- Float pill + summary sheet (prototype C).
- Extra entry points (overflow menu, etc.).
- Changing the summarize HTTP contract or adding a second compact API.
- Listening to V2-only `session.compaction.*` events (follow-up if the server
  stops awaiting compaction inside `POST …/summarize`).
- Context-ring pulse animation unless it stays a cheap feature-local polish
  with no design-system API change.

## Locked Product Rules

| Rule | Locked behavior |
| --- | --- |
| Verb | One action: Commands `[compact]` only |
| Prototype | **B — Status chrome only** |
| Running copy | **Compacting…** while local compacting **or** server compaction turn is active |
| Working | Compacting wins over generic Working for Session Chat chrome |
| Stop | Remains available while compacting / busy (same as other busy work) |
| Done snackbar (delta) | `Context compacted · {before}% → {after}%` when both fractions exist |
| Done snackbar (fallback) | `Session compacted.` when before or after is unavailable |
| Failure | Existing error snackbar; clear compacting chrome |
| Timeline | No new compaction row; keep compaction parts in domain for presence only |
| Float stack | No compact pill |
| Remote snackbar | No success toast on observers that did not start compact |

## Server APIs Involved

- `POST /session/{id}/summarize` with `{ providerID, modelID }` (existing thin
  compact path). Mobile awaits this call today; compacting chrome covers that
  in-flight window.

## Expected Platform Logs

- Compact start (session id, provider/model).
- Compact success / failure.
- Optional: before/after context fraction when logging success delta.

## Domain Objects

- Keep `MessagePart.compaction` / `MessagePart.Compaction` in the domain for
  live presence; do not project them into timeline rows.
- Reuse Session Repository `summarizeSession` and existing context usage
  fraction from assistant message tokens + model limit.
- `SessionCompactionPresence` detects a busy active compaction turn.

## Screen Model

Session Chat merges local **compacting** (summarize in flight) with
`SessionCompactionPresence`. Displayed session status / turn-active chrome
prefers **Compacting…** when either is true. Success snackbar payload may
include formatted before→after percents on the initiator only.

User action unchanged: Commands → compact.

## Native Entry Points

- Session Chat Commands sheet `[compact]` on iOS and Android (already shipped).

## Error States

- Summarize failure: clear compacting; show couldn't-compact error snackbar;
  leave session otherwise unchanged.
- Missing context fractions: success still toasts fallback copy after refresh.

## Permission Behavior

- Not applicable (compact is a client session action, not a permission flow).

## Design-System Components

| Element | Classification |
| --- | --- |
| Session status / Working label | Existing (`OpenCodeSessionStatusBar` / session status label) — copy override only |
| Composer Stop while busy | Existing |
| Success / error snackbar | Existing `OpenCodeSnackbar` |
| Context ring | Existing; optional local pulse polish only |
| Timeline checkpoint / float pill | Not used (rejected prototypes) |

## Prototype

- `docs/features/session-compact/prototype/`
- Run: `npm run prototype:session-compact`
- Verdict: [prototype/VERDICT.md](./prototype/VERDICT.md)

## Child / Related Tickets

| Ticket | Focus |
| --- | --- |
| [OPE-58](https://linear.app/opencode-mobile/issue/OPE-58/slash-actions) | Thin Commands compact (done) |
| [OPE-116](https://linear.app/opencode-mobile/issue/OPE-116/session-compact-richer-status-chrome) | This richer status-chrome slice |
| [OPE-80](https://linear.app/opencode-mobile/issue/OPE-80/session-compact) | Canceled placeholder; superseded by OPE-116 for chrome |
| [OPE-79](https://linear.app/opencode-mobile/issue/OPE-79/session-summarize) | Canceled duplicate of compact |
