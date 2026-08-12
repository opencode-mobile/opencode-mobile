# Server API v2 Compatibility

Related research:
[`../server-connection/research-opencode-api-v2.md`](../server-connection/research-opencode-api-v2.md)

## User Goal

Connect OpenCode Mobile to both OpenCode **v1** (unprefixed) and **v2**
(`/api/*`) servers without rewriting feature UI. Detect protocol on connect;
route under repositories; keep Screen Models on stable domain types.

## In Scope

- Dual-stack behind existing repository interfaces on **both** iOS and Android.
- Protocol detection on connection test / home probe; cache on active connection.
- Domain adapters / projectors that map v1 or v2 wire shapes into today’s domain
  models (`Session`, `Message`, `PermissionRequest`, …).
- Capability flags for product features that v2 cannot support yet (notably
  Review Changes “Last turn” via `GET /session/{id}/diff`).
- Phased vertical slices that keep v1 fully working while v2 grows.

## Out Of Scope

- Regenerating OpenAPI clients into UI or Screen Models.
- UI feature-flags that fork Screen Models into “v1 mode” vs “v2 mode”.
- Migrating dead repository methods (`share`, `init`, `listChildSessions`,
  sync `sendMessage`, `getMessage`, `listProjects`) except optional cleanup.
- App-managed tunnel / multi-server accounts.
- Waiting for every v2 gap to close before shipping partial v2 support.

## Work Split

Ship as synchronized Feature Slices. Each slice is done only when **both**
platforms implement it (or a parity gap is recorded). Prefer vertical value
over “finish all REST then do SSE.”

```text
P0 Foundation
  └─ detect + cache protocol; dual health; capability surface
P1 Read home on v2
  └─ project/current, session list/get, status, rename/archive/delete/create
P2 Open chat (read)
  └─ message list seed → domain Message; no live stream required yet
P3 Live + send
  └─ /api/event projector + MessageSync; prompt_async → prompt
P4 Interactions
  └─ permissions, questions, abort/interrupt, slash commands, agents, models
P5 Secondary
  └─ compact, revert, todos, VCS changes, path/file add-project
P6 Degrade / cleanup
  └─ hide last-turn diff on v2; optional dead-API removal; matrix tests
```

### P0 — Foundation (unblocks everything)

| Work | Seam |
| --- | --- |
| `ServerProtocol` = `v1` \| `v2` | Connection / probe |
| Detect like desktop: `/global/health` then `/api/health` (`pid`) | `ConnectionProbeService` |
| Cache on `ServerConnection` / active connection (not UI state) | Network + DI |
| `ServerCapabilities` (e.g. `supportsSessionMessageDiff`) | Domain |
| Dual probe so Test Connection works against either server | Server Connection UI unchanged |

**Exit:** connect/save against a v2-only server succeeds; protocol visible in
diagnostic logs; v1 still works.

### P1 — Read-only home on v2

| Work | Notes |
| --- | --- |
| `project/current`, session list/get/create | Envelope `{ data, cursor }`, `location` |
| Status: `/session/status` ↔ `/api/session/active` + later events | May be coarse at first |
| Rename / archive / delete | `PATCH` vs `POST .../rename` / archive path TBD |
| Fork | Boundary object mapping |

**Exit:** home lists sessions on v2; open a session shell (even empty) without
send/stream.

**UI change:** none expected beyond capability-driven empty/error copy if
needed.

### P2 — Chat history (read)

| Work | Notes |
| --- | --- |
| `GET .../message` → domain `Message` / `MessagePart` | **Hard:** v2 projected messages |
| Cursor pagination if v2 pages | MessageSync seed / full reload |
| Keep `InMemoryMessageStore` domain API stable | Projector lives under repository or a dedicated `MessageWireProjector` |

**Exit:** open existing v2 session and see history (static). No live deltas yet.

**UI change:** none if domain `Message` stays stable; timeline keeps working.

### P3 — Live stream + send (largest slice)

| Work | Notes |
| --- | --- |
| `GET /api/event` subscribe | Replace `/global/event` when protocol=v2 |
| Event normalizer → today’s `OpencodeEvent` **or** protocol-agnostic patches | Prefer normalizing into existing MessageSync inputs first |
| Remap `session.next.*` / durable events into part/message patches | Highest risk |
| `prompt_async` ↔ `POST .../prompt` (`text`/`files`/`agents`/`delivery`) | Composer stays on `PromptSendOptions` |
| Reconnect / seed interaction with new stream | MessageSync health |

**Exit:** send a prompt on v2 and see streaming updates; reconnect recovers.

**UI change:** avoid if possible. Only if stream semantics force visible status
copy.

Split P3 further if needed:

1. **P3a** — event pipe + server.connected / session lifecycle only  
2. **P3b** — message/part projection into MessageSync  
3. **P3c** — prompt send + attachments  

### P4 — Session interactions

| Work | Product |
| --- | --- |
| Permissions list/reply | Permission dock |
| Questions list/reply/reject | Question dock |
| Abort → interrupt | Stop button |
| Commands list + run | Slash commands |
| Agents list | @ mentions |
| `config/providers` → `/api/provider` + `/api/model` | Model picker |

**Exit:** approve permission, answer question, stop, slash, pick model on v2.

### P5 — Secondary features

| Work | Product |
| --- | --- |
| Summarize → compact | Compact command |
| Revert → stage/clear/commit | Revert / unrevert commands |
| Todos REST or event-only seed | Todos pill |
| `/vcs` + `/vcs/diff` | Changes working tree |
| `/path` + `/file` → location/fs | Add project |

**Exit:** compact/revert/todos/changes/add-project work on both protocols where
v2 exposes equivalents.

### P6 — Degrade, cleanup, hardening

| Work | Notes |
| --- | --- |
| On v2, hide or disable Review Changes “Last turn” | No `session/{id}/diff` twin |
| Keep working-tree VCS diffs | Already ~score 4 |
| Optional: delete dead repo methods | share, init, children, sync send, getMessage, listProjects |
| Protocol matrix unit tests | Probe, adapters, event projector fixtures |
| Diagnostic logs include `protocol=` | Existing diagnostic areas |

## Server APIs

See research inventory. Product-used only for slice scope; dead APIs out of
migration critical path.

## Domain Objects

- `ServerProtocol` — `v1` \| `v2`
- `ServerCapabilities` — feature availability derived from protocol (+ future
  probes)
- Existing: `ServerConnection`, `Session`, `Message`, `MessagePart`,
  `PermissionRequest`, `QuestionRequest`, `ForegroundEventStream`, MessageSync
  health types

New optional: `MessageWireProjector` / `EventWireProjector` (protocol → domain
patches).

## Screen Model / UI

- **Default:** no Screen Model fork by protocol.
- **Allowed UI:** capability-gated actions (e.g. hide last-turn review on v2)
  using `ServerCapabilities`, not duplicated ViewModels.
- Entry points unchanged: connection setup, home, session chat, changes,
  settings.

## Error States

- Unknown / mixed protocol (health ambiguous) → treat as unreachable or
  unsupported with clear copy.
- v2 feature missing → disable action + snackbar / empty state, not crash.
- Projector decode failure → MessageSync recovery path (existing stale/recover).

## Design-System Components

- None new for protocol itself.
- Reuse existing empty states / snackbars for capability degradation.
- Feature-local copy only if needed for “not supported on this server.”

## Platform Entry Points

- iOS: `ConnectionProbeService` / `ProjectsAPIClient` health; DI in
  `AppContainer`; repositories under `Domain/*`.
- Android: `ConnectionProbeService` / `HealthApi`; Hilt modules; repositories
  under `domain/*`.

## Linear

Epic: [OPE-146](https://linear.app/opencode-mobile/issue/OPE-146/server-api-v2-compatibility)

| Phase | Issue |
| --- | --- |
| P0 | [OPE-147](https://linear.app/opencode-mobile/issue/OPE-147/p0-detect-server-api-protocol) |
| P1 | [OPE-148](https://linear.app/opencode-mobile/issue/OPE-148/p1-read-only-home-on-v2) |
| P2 | [OPE-149](https://linear.app/opencode-mobile/issue/OPE-149/p2-chat-history-on-v2) |
| P3 | [OPE-150](https://linear.app/opencode-mobile/issue/OPE-150/p3-live-stream-and-prompt-on-v2) (a/b/c inside) |
| P4 | [OPE-151](https://linear.app/opencode-mobile/issue/OPE-151/p4-permissions-questions-and-composer-catalogs-on-v2) |
| P5 | [OPE-152](https://linear.app/opencode-mobile/issue/OPE-152/p5-compact-revert-todos-vcs-and-add-project-on-v2) |
| P6 | [OPE-153](https://linear.app/opencode-mobile/issue/OPE-153/p6-capability-gates-cleanup-and-protocol-matrix-tests) |

Each ticket: both platforms, tests, progress update, no UI rewrite unless
capability gate.

## Dependencies / Order

```text
P0 → P1 → P2 → P3 → P4
              ↘ P5 (can parallelize after P1 where independent)
P3 → P6 (capability gates need live v2 chat to validate)
```

P5 VCS/path can start after P0 once remotes are dual-aware; do not block on P3.
