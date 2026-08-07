# Research: Session Running / Waiting Indicator Gap (Web vs Mobile)

**Date:** 2026-08-07  
**Linear:** [OPE-113 Session working indicator](https://linear.app/opencode-mobile/issue/OPE-113/session-working-indicator)  
**Question:** Why can mobile wait on an assistant response with no Stop (and no other
waiting UI), while web/desktop always feel “in progress”?  
**Primary sources:** local `opencode` monorepo (`/Users/ivan/Projects/opencode`,
inspected around `1882c33827`) and this repo’s Session Chat / LiveStore
implementations.  
**Canonical note:** this file (committed under `docs/features/session-chat/`). A
parallel draft under gitignored `docs/research/` may exist locally; prefer this
path for handoff.

## Verdict

Web/desktop treat “session is working” as a **local, immediately updated
`session_status`**, drive **several** waiting surfaces from the same
`session_working` bit (animated progress dots on the session tab / related
chrome, composer Stop, in-chat Thinking shimmer, sidebar spinner), and
**optimistically set busy on send**.

Mobile drives **only the composer Stop** from `sessionStatusLabel`, which becomes
non-null only after **server status** arrives through
`SessionStatusLiveStore` (SSE → 300 ms debounce → `GET /session/status`). There is
**no optimistic busy**, **no progress-dot chrome**, and **no in-timeline
Thinking/waiting row**. Between `promptAsync` accept and a successful status
refresh, the UI can look idle while work is already underway.

What users call web “syncing” in chat is usually the **`SessionProgressIndicatorV2`
dot grid** and/or the **Thinking** TextShimmer — not a separate server status and
not the MCP/LSP status popover. There is no product string “syncing” for busy.

---

## Web / desktop: how “working” is decided

### Canonical predicate

```207:209:/Users/ivan/Projects/opencode/packages/app/src/context/server-session.ts
    session_working(id: string) {
      return (this.session_status[id]?.type ?? "idle") !== "idle"
    },
```

Any non-`idle` status (`busy`, `retry`) is “working”. Composer Stop and timeline
activity both use this.

### Status is applied locally and immediately

On SSE / v2 events, web **writes `session_status` in-memory** — it does not wait
for a bulk status poll to show busy:

```964:1029:/Users/ivan/Projects/opencode/packages/app/src/context/server-session.ts
    if (event.type === "session.execution.started") setData("session_status", sessionID, { type: "busy" })
    // ...
    if (event.type === "session.execution.succeeded" || ...)
      setData("session_status", sessionID, { type: "idle" })
    // ...
      case "session.status": {
        const props = event.properties as { sessionID: string; status: SessionStatus }
        setData("session_status", props.sessionID, reconcile(props.status))
```

Bootstrap also seeds active sessions (`seedActiveSessionStatuses` in
`server-sync.tsx`), normalizing v2 `running` → `{ type: "busy" }`.

Server status types themselves are only `idle` | `busy` | `retry`
(`packages/schema/src/session-status-event.ts`).

### Optimistic busy on send

Submit sets busy **before** the network round-trip finishes, then reverts on
failure:

```60:68:/Users/ivan/Projects/opencode/packages/app/src/components/prompt-input/submit.ts
  const setBusy = () => {
    if (!input.optimisticBusy) return
    input.serverSync.session.set("session_status", input.draft.sessionID, { type: "busy" })
  }
  const setIdle = () => {
    if (!input.optimisticBusy) return
    input.serverSync.session.set("session_status", input.draft.sessionID, { type: "idle" })
  }
```

Normal prompts pass `optimisticBusy: sessionDirectory === projectDirectory`
(`submit.ts` around the `sendFollowupDraft` call). Slash commands and
worktree-wait paths also `set("session_status", …, { type: "busy" })` eagerly.

### Composer Stop

```255:286:/Users/ivan/Projects/opencode/packages/app/src/components/prompt-input.tsx
  const working = createMemo(() => sync().data.session_working(props.controls.session.id ?? ""))
  // ...
  const stopping = createMemo(() => working() && blank())
```

When the draft is blank and the session is working, the primary button is Stop.
Same `working()` gate on prompt-input-v2.

### Progress dots (“syncing” chrome)

`SessionProgressIndicatorV2`
(`packages/session-ui/src/v2/components/session-progress-indicator-v2.tsx`) is an
animated 5×5 dot grid. It appears when `session_working` holds, including:

- session **tab avatar** (`session-tab-avatar.tsx` via `project-avatar-state.ts`) —
  hidden when permission/question attention takes priority;
- **task tool** running chrome (`message-part.tsx`);
- sidebar session rows use a related spinner on the same predicate
  (`sidebar-items.tsx`).

E2E: `packages/app/e2e/regression/remote-tab-busy.spec.ts`.

### In-chat waiting indicator (“Thinking”)

Timeline rows insert a Thinking row while the active turn is `busy`:

```191:205:/Users/ivan/Projects/opencode/packages/app/src/pages/session/timeline/rows.ts
    if (isActive && status === "busy" && !error && (showReasoning ? assistantPartRefs.length === 0 : true)) {
      // ...
      rows.push(new TimelineRow.Thinking({ ... }))
    }
    if (isActive && status === "retry") rows.push(new TimelineRow.Retry({ ... }))
```

Rendered as a shimmer label (`ui.sessionTurn.status.thinking`) in session-ui:

```372:424:/Users/ivan/Projects/opencode/packages/session-ui/src/components/session-turn.tsx
  const showThinking = createMemo(() => {
    if (!working() || !!error()) return false
    if (status().type === "retry") return false
    // ...
  })
  // ...
              <Show when={showThinking()}>
                <div data-slot="session-turn-thinking">
                  <TextShimmer text={i18n.t("ui.sessionTurn.status.thinking")} />
```

Tool rows also use TextShimmer while running.

**Progress dots and Stop share `session_working`.** Stop additionally requires a
blank prompt (typed follow-up while busy keeps Send / queue behavior).

### Not the chat waiting indicator

`status-popover` / `status-popover-indicator.ts` reflect **server health + MCP +
LSP**, not turn progress. Do not conflate that with session busy UI.

### Server map detail

On idle, the server publishes `session.idle`, then **deletes** the session from
the in-memory status map (`packages/opencode/src/session/status.ts`), so
`GET /session/status` omits idle sessions. Clients that treat “missing” as idle
match that model.

---

## Mobile: how “working” is decided

### Single UI gate for Stop

Android Session Chat:

```500:500:android/app/src/main/java/dev/opencode/mobile/ui/sessionchat/SessionChatScreen.kt
                            showStop = uiState.sessionStatusLabel != null,
```

iOS:

```667:667:ios/OpenCode Mobile/OpenCode Mobile/UI/SessionChat/SessionChatView.swift
                    showStop: viewModel.uiState.sessionStatusLabel != nil,
```

`sessionStatusLabel` is mapped only from raw status `busy` → `"Working"` and
`retry` → `"Retrying"` (`EventDomainAdapter`). Anything else (including missing
status) hides Stop.

`isTurnActive` is the same predicate (`sessionStatusLabel != null`) — used for
review-changes eligibility, not a separate waiting chrome.

### No optimistic busy

Send path sets `isSending = true`, appends an optimistic **user** row, awaits
`sendPromptAsync` / command run, then clears `isSending` on accept/fail
(`SessionChatComposer.sendPrompt`). On accept it calls
`sessionInfo.refreshStatus()` (one-shot `GET /session/status`).

**`isSending` does not drive `showStop`.** After HTTP accept, if the status
refresh still returns idle (or has not completed), the composer shows Send again
with no waiting affordance.

### Status path: poll-after-SSE, not local apply

`DefaultSessionStatusLiveStore` (Android/iOS):

1. Initial `getAllSessionStatuses`.
2. On `session.status` / `session.idle` (or reconnect), **debounce 300 ms**.
3. Re-fetch **bulk** `GET /session/status` and emit the map.

It does **not** apply `event.rawProperties.status` from the SSE payload the way
web does. Session Chat’s `SessionInfo` observes the LiveStore map; it only
special-cases `session.error` / title updates from the raw event stream — not
busy/idle. Documented intent: REST remains source of truth for status labels
(`docs/features/foreground-event-stream/decisions.md`, ADR 0002).

**Android failure asymmetry:** LiveStore uses
`runCatching { … }.getOrDefault(emptyMap())`. A failed refetch can publish an
**empty** map and clear a previously known busy label mid-turn. iOS returns
`nil` on load failure and skips yield (keeps prior emission).

Focused Session Chat also one-shots `refreshStatus()` on bind and after send
accept; both still depend on the server map, not local optimistic state.

### No Thinking / waiting timeline row

Mobile timeline tracks streaming text / tool parts when messages arrive. There
is no product equivalent of web’s `TimelineRow.Thinking` / session-turn Thinking
shimmer keyed off session busy. If status is idle and no assistant parts have
landed yet, the timeline looks finished aside from the optimistic user bubble.

---

## Side-by-side gap

| Concern | Web / desktop | Mobile |
| --- | --- | --- |
| Working predicate | `session_status[id].type !== "idle"` | `sessionStatusLabel != null` (`busy`/`retry` only) |
| On send | Optimistic `{ type: "busy" }` | Optimistic user message + short `isSending`; **no** busy |
| Status from SSE | Direct store write (`session.status`, `execution.started`, …) | Debounced bulk GET after event |
| Progress / “syncing” chrome | `SessionProgressIndicatorV2` + sidebar spinner | None |
| Composer waiting UI | Stop when working ∧ blank | Stop only when label non-null |
| In-chat waiting UI | Thinking / Retry rows + tool shimmers | None for “busy but no parts yet” |
| Failed status refetch | Optimistic busy until reverted; SSE still applies | Android can emit `emptyMap()` and wipe busy; iOS keeps prior |

Conceptually mobile stop ≈ web `session_working` (same server types). The
user-visible gap is **latency/optimism** plus **missing non-stop waiting chrome**.

---

## Likely root causes of “waiting, no Stop”

1. **Post-accept gap (most structural)**  
   After `promptAsync` succeeds, `isSending` clears. Stop appears only when
   status becomes busy via refresh/LiveStore. Any delay or miss in that path
   leaves Send with no progress chrome, while the server is already working.

2. **Indirect status updates**  
   Web paints busy from the status event itself. Mobile waits 300 ms then
   re-lists statuses. A failed GET, empty map entry, or race where the server
   briefly reports idle yields a null label even though a turn is in flight
   (messages may already stream via MessageSync).

3. **Android empty-map on fetch failure**  
   Transient `getAllSessionStatuses` failure can clear a known busy map → Stop
   disappears mid-turn.

4. **Missing secondary indicator**  
   Web still shows progress dots and/or Thinking when Stop’s blank-prompt rule
   does not apply, and Thinking covers “busy before first assistant content.”
   Mobile has only Stop, so a missed label is a complete blank.

5. **Terminology**  
   Web “syncing” reports likely mean `SessionProgressIndicatorV2` / Thinking,
   not MCP status-popover and not a distinct server type.

---

## Suggested fix directions (research only; not implemented)

Ordered by how closely they match web behavior:

1. **Optimistic busy on send** (and command run): set local working state when
   the user submits; clear on send failure or when authoritative status becomes
   idle. Drive `showStop` / `isTurnActive` from that local working flag ∪ server
   status.
2. **Apply `session.status` payload locally** in SessionStatusLiveStore (or
   SessionInfo) instead of only triggering a debounced bulk GET; keep GET for
   reconnect/bootstrap.
3. **Do not clear busy on failed refetch** (Android): skip yield or keep prior
   map on error, matching iOS.
4. **Add non-stop waiting chrome** while working — in-chat Thinking/Working row
   and/or a small progress treatment — so Stop is not the only signal.

---

## Source index

| Claim | Source |
| --- | --- |
| `session_working` | `opencode/packages/app/src/context/server-session.ts` |
| Direct status SSE apply + execution.started | same file `apply` / v2 handlers |
| Optimistic busy | `opencode/packages/app/src/components/prompt-input/submit.ts` |
| Composer Stop | `opencode/packages/app/src/components/prompt-input.tsx` |
| Progress dots | `session-ui/.../session-progress-indicator-v2.tsx`, `session-tab-avatar.tsx` |
| Thinking row | `…/timeline/rows.ts`, `session-ui/.../session-turn.tsx` |
| Status schema / idle delete | `packages/schema/src/session-status-event.ts`, `packages/opencode/src/session/status.ts` |
| Mobile `showStop` | `SessionChatScreen.kt`, `SessionChatView.swift` |
| Mobile status LiveStore | `DefaultSessionStatusLiveStore` (kt/swift), `LiveStoreRefreshPolicy` |
| Mobile send / `isSending` | `SessionChatComposer.kt` (+ iOS peer), ViewModel `onSendAccepted` → `refreshStatus` |
| Label mapping | `EventDomainAdapter` busy/retry only |
